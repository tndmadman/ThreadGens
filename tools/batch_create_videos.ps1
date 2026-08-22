param(
    [int]$TargetVideos = 30,
    [int]$Count = 10,
    [string]$Model = 'llama3.1:8b',
    [string]$Voice = 'af_heart',
    [string]$VoiceSeries = 'af_heart,af_bella,af_nicole,am_adam,am_michael,bf_emma,bm_george',
    [ValidateSet('single', 'series', 'per-slide')]
    [string]$VoiceSelection = 'series',
    [string]$SeriesId = '',
    [ValidateSet('natural', 'calm', 'energetic', 'dramatic')]
    [string]$TtsDelivery = 'natural',
    [ValidateSet('off', 'word', 'sentence')]
    [string]$Captions = 'off',
    [string]$Platform = 'reddit',
    [ValidateSet('auto', 'thread_story', 'confession', 'debate', 'best_answers', 'escalating_conversation')]
    [string]$Format = 'auto',
    [string]$IdeaHistoryFile = 'data\batch_idea_history.jsonl',
    [int]$IdeaHistoryLimit = 80,
    [int]$IdeaRetries = 8,
    [int]$MaxAttempts = 0,
    [switch]$KeepOllamaLoaded,
    [switch]$GenerateOpImage,
    [switch]$StopOnError,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if (-not $PSBoundParameters.ContainsKey('KeepOllamaLoaded')) {
    $KeepOllamaLoaded = $true
}

$env:THREADGENS_KOKORO_VERBOSE = '0'
$env:PYTHONWARNINGS = 'ignore'
$env:HF_HUB_DISABLE_PROGRESS_BARS = '1'
$env:TOKENIZERS_PARALLELISM = 'false'

$Platform = $Platform.ToLowerInvariant()
if ($Platform -notin @('reddit', 'x')) {
    throw "Unsupported platform: $Platform. Use reddit or x."
}
if ($TargetVideos -lt 1) {
    throw 'TargetVideos must be at least 1.'
}
if ($Count -lt 1) {
    throw 'Count must be at least 1.'
}
if ($IdeaHistoryLimit -lt 1) {
    $IdeaHistoryLimit = 1
}
if ($IdeaRetries -lt 1) {
    $IdeaRetries = 1
}

$TtsEngine = 'kokoro'
$KokoroPython = Join-Path $RepoRoot '.venv-kokoro\Scripts\python.exe'
$OutputRoot = Join-Path $RepoRoot ('output\batch_videos\' + $Platform + '_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
$FinalDir = Join-Path $OutputRoot 'final_videos'
$IdeaHistoryPath = if ([System.IO.Path]::IsPathRooted($IdeaHistoryFile)) {
    $IdeaHistoryFile
} else {
    Join-Path $RepoRoot $IdeaHistoryFile
}
$OllamaGenerateUrl = 'http://127.0.0.1:11434/api/generate'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function New-SafeFileName($Value) {
    $name = [string]$Value
    $name = $name.ToLowerInvariant()
    $name = $name -replace '[^a-z0-9]+', '_'
    $name = $name.Trim('_')
    if ($name.Length -gt 36) {
        $name = $name.Substring(0, 36).Trim('_')
    }
    if ([string]::IsNullOrWhiteSpace($name)) {
        return 'video'
    }
    return $name
}

function Normalize-IdeaText($Value) {
    if ($null -eq $Value) {
        return ''
    }
    return (([string]$Value).ToLowerInvariant() -replace '[^a-z0-9]+', ' ').Trim()
}

function Get-IdeaKey($Title, $Body, $IdeaPlatform) {
    $normalized = (Normalize-IdeaText $IdeaPlatform) + '|' +
        (Normalize-IdeaText $Title) + '|' +
        (Normalize-IdeaText $Body)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($bytes)
    } finally {
        $sha.Dispose()
    }
    return -join ($hash | ForEach-Object { $_.ToString('x2') })
}

function Add-IdeaHistoryEvent($EventData, $Path = $IdeaHistoryPath) {
    $folder = Split-Path -Parent $Path
    if ($folder) {
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
    }
    $json = $EventData | ConvertTo-Json -Compress -Depth 8
    [System.IO.File]::AppendAllText($Path, $json + [Environment]::NewLine, $Utf8NoBom)
}

function Read-IdeaHistory($Path = $IdeaHistoryPath) {
    if (-not (Test-Path $Path)) {
        return @()
    }

    $result = @()
    $lineNumber = 0
    foreach ($rawLine in Get-Content -Path $Path -Encoding UTF8) {
        $lineNumber++
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        try {
            $entry = $line | ConvertFrom-Json
        } catch {
            throw "Batch idea history is malformed at line $lineNumber in $Path."
        }
        if ($entry.event -eq 'generated' -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.title) -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.body)) {
            $result += $entry
        }
    }
    return @($result)
}

function ConvertFrom-IdeaResponse($Text) {
    $raw = ([string]$Text).Trim()
    $firstBrace = $raw.IndexOf('{')
    $lastBrace = $raw.LastIndexOf('}')
    if ($firstBrace -lt 0 -or $lastBrace -le $firstBrace) {
        throw 'Ollama idea response did not contain a JSON object.'
    }
    $json = $raw.Substring($firstBrace, $lastBrace - $firstBrace + 1)
    try {
        return $json | ConvertFrom-Json
    } catch {
        throw "Ollama idea response contained invalid JSON: $json"
    }
}

function Get-RecentIdeaBlock($History) {
    if (-not $History -or $History.Count -eq 0) {
        return '(none yet)'
    }

    $start = [Math]::Max(0, $History.Count - $IdeaHistoryLimit)
    $lines = @()
    for ($i = $start; $i -lt $History.Count; $i++) {
        $title = (([string]$History[$i].title) -replace '\s+', ' ').Trim()
        $body = (([string]$History[$i].body) -replace '\s+', ' ').Trim()
        if ($body.Length -gt 180) {
            $body = $body.Substring(0, 177) + '...'
        }
        $lines += "- $title :: $body"
    }
    return ($lines -join [Environment]::NewLine)
}

function Invoke-NewBatchIdea($AttemptNumber, [ref]$HistoryRef, [hashtable]$SeenKeys) {
    $themes = @(
        'awkward social situation',
        'workplace problem',
        'family story',
        'neighbor conflict',
        'travel surprise',
        'technology mishap',
        'relationship misunderstanding',
        'unexpected discovery',
        'pet or animal story',
        'school memory',
        'customer service story',
        'money or purchase dilemma',
        'wholesome surprise',
        'two-sided debate',
        'confession or regret',
        'ordinary situation that becomes strange'
    )

    for ($ideaTry = 1; $ideaTry -le $IdeaRetries; $ideaTry++) {
        $history = @($HistoryRef.Value)
        $theme = $themes[(($AttemptNumber + $ideaTry - 2) % $themes.Count)]
        $recentBlock = Get-RecentIdeaBlock $history

        if ($Platform -eq 'x') {
            $shape = @"
Create one NEW ThreadGens X seed.
Return JSON with exactly these string fields:
{"title":"hidden reply style","body":"visible X post"}

"title" is a short hidden reply instruction such as normal replies, wrong answers only, give practical advice, finish this story, or another natural reply style.
"body" is the visible X post: concise, specific, natural, and under about 280 characters.
"@
        } else {
            $shape = @"
Create one NEW ThreadGens Reddit seed.
Return JSON with exactly these string fields:
{"title":"reddit post title","body":"reddit post body"}

"title" should be a natural Reddit-style question or prompt, usually 6-18 words.
"body" should be 1-3 concise sentences with a concrete setup that gives generated replies something specific to react to.
"@
        }

        $prompt = @"
$shape

Target idea family for this attempt: $theme

Hard rules:
- Invent a materially new premise, not a paraphrase of anything in RECENT IDEAS.
- Vary hook, setting, people, objects, conflict, emotional tone, and likely reply structure.
- Do not keep producing horror/mystery prompts; use the requested idea family and broad variety across attempts.
- Make the setup easy to narrate aloud.
- Do not mention ThreadGens, AI, prompts, engagement counts, verification, moderation actions, or platform algorithms.
- Do not make accusations about identifiable real people.
- No markdown, no code fence, no explanation: output only the JSON object.

RECENT IDEAS THAT MUST NOT BE RECYCLED:
$recentBlock
"@

        $payload = @{
            model = $Model
            prompt = $prompt
            stream = $false
            format = 'json'
            keep_alive = if ($KeepOllamaLoaded) { '30m' } else { '0s' }
            options = @{
                temperature = 1.02
                top_p = 0.95
            }
        } | ConvertTo-Json -Depth 8

        try {
            $response = Invoke-RestMethod `
                -Uri $OllamaGenerateUrl `
                -Method Post `
                -ContentType 'application/json' `
                -Body $payload `
                -TimeoutSec 300
            $idea = ConvertFrom-IdeaResponse $response.response
        } catch {
            if ($ideaTry -ge $IdeaRetries) {
                throw "Could not generate a valid batch idea after $IdeaRetries tries: $($_.Exception.Message)"
            }
            Write-Host "Idea generation try $ideaTry/$IdeaRetries failed: $($_.Exception.Message)" -ForegroundColor Yellow
            continue
        }

        $title = (([string]$idea.title) -replace '\s+', ' ').Trim()
        $body = (([string]$idea.body) -replace '\s+', ' ').Trim()

        if ([string]::IsNullOrWhiteSpace($title) -or
            [string]::IsNullOrWhiteSpace($body) -or
            $title.Length -lt 3 -or
            $body.Length -lt 12) {
            Write-Host "Idea generation try $ideaTry/$IdeaRetries returned an unusable title/body; regenerating." -ForegroundColor Yellow
            continue
        }

        $key = Get-IdeaKey $title $body $Platform
        if ($SeenKeys.ContainsKey($key)) {
            Write-Host "Idea generation try $ideaTry/$IdeaRetries repeated a previously attempted idea; regenerating." -ForegroundColor Yellow
            continue
        }

        $id = [Guid]::NewGuid().ToString('N')
        $entry = [pscustomobject]@{
            event = 'generated'
            id = $id
            created = (Get-Date).ToUniversalTime().ToString('o')
            platform = $Platform
            model = $Model
            theme = $theme
            key = $key
            title = $title
            body = $body
        }
        Add-IdeaHistoryEvent $entry
        $SeenKeys[$key] = $true
        $HistoryRef.Value = @($history + $entry)
        return $entry
    }

    throw "Could not generate a unique batch idea after $IdeaRetries tries."
}

function Run-SelfTest {
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('threadgens-batch-selftest-' + [Guid]::NewGuid().ToString('N'))
    $tempHistory = Join-Path $tempRoot 'ideas.jsonl'
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    try {
        $idea = [pscustomobject]@{
            event = 'generated'
            id = 'selftest'
            created = '2026-01-01T00:00:00Z'
            platform = 'reddit'
            model = 'test'
            theme = 'test'
            key = (Get-IdeaKey 'A test title' 'A concrete test body.' 'reddit')
            title = 'A test title'
            body = 'A concrete test body.'
        }
        Add-IdeaHistoryEvent $idea $tempHistory
        $loaded = @(Read-IdeaHistory $tempHistory)
        if ($loaded.Count -ne 1 -or $loaded[0].title -ne 'A test title') {
            throw 'Idea history round-trip failed.'
        }

        $parsed = ConvertFrom-IdeaResponse 'prefix {"title":"T","body":"B body text"} suffix'
        if ($parsed.title -ne 'T' -or $parsed.body -ne 'B body text') {
            throw 'Idea JSON extraction failed.'
        }

        $approved = 0
        $attempts = 0
        foreach ($status in @('rejected', 'approved', 'rejected', 'approved')) {
            $attempts++
            if ($status -eq 'approved') {
                $approved++
            }
        }
        if ($approved -ne 2 -or $attempts -ne 4) {
            throw 'Approved-target loop accounting failed.'
        }

        Write-Host 'Self-filling batch controller self-test passed.'
    } finally {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tempRoot
    }
}

if ($SelfTest) {
    Run-SelfTest
    exit 0
}

if (-not (Test-Path $KokoroPython)) {
    throw "Kokoro Python was not found: $KokoroPython. Run setup_windows.bat first."
}

Write-Step 'ThreadGens self-filling batch video creator'
Write-Host "Target approved videos: $TargetVideos"
Write-Host "Slides/replies per video: $Count"
Write-Host "Idea history: $IdeaHistoryPath"
Write-Host "Output root: $OutputRoot"
Write-Host "Defaults: platform=$Platform, format=$Format, model=$Model, tts=$TtsEngine"
Write-Host "P1 voice selection: $VoiceSelection from [$VoiceSeries], delivery=$TtsDelivery, captions=$Captions"
Write-Host 'Video style: locked/static social frame with subtle final grain'
Write-Host 'Self-filling mode: generate ideas until approved target is reached' -ForegroundColor Green
Write-Host 'Kokoro console: quiet'
if ($MaxAttempts -gt 0) {
    Write-Host "Attempt cap: $MaxAttempts total ideas"
} else {
    Write-Host 'Attempt cap: none; continue until approved target is reached'
}
if ($GenerateOpImage) {
    Write-Host 'OP image generation: enabled through local ComfyUI RealVisXL' -ForegroundColor Green
    Write-Host 'ComfyUI must already be running at http://127.0.0.1:8188 with RealVisXL_V5.0_fp32.safetensors installed.' -ForegroundColor Yellow
} else {
    Write-Host 'OP image generation: disabled'
}
if ($KeepOllamaLoaded) {
    Write-Host 'Ollama unload: disabled, keeping model loaded between ideas/videos' -ForegroundColor Green
} else {
    Write-Host 'Ollama unload: enabled between generation calls'
}
if ($StopOnError) {
    Write-Host 'Batch error mode: stop on first rejected/failed video' -ForegroundColor Yellow
} else {
    Write-Host 'Batch error mode: rejected ideas are replaced automatically' -ForegroundColor Green
}

Write-Step 'Building Java files'
New-Item -ItemType Directory -Force -Path (Join-Path $RepoRoot 'out') | Out-Null
$javaFiles = Get-ChildItem -Path (Join-Path $RepoRoot 'src\redditTxtToImg') -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $javaFiles -or $javaFiles.Count -eq 0) {
    throw 'No Java files found in src\redditTxtToImg.'
}
& javac -d (Join-Path $RepoRoot 'out') $javaFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java build failed with exit code $LASTEXITCODE."
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
New-Item -ItemType Directory -Force -Path $FinalDir | Out-Null

$ideaHistory = @(Read-IdeaHistory $IdeaHistoryPath)
$seenIdeaKeys = @{}
foreach ($oldIdea in $ideaHistory) {
    $oldKey = [string]$oldIdea.key
    if ([string]::IsNullOrWhiteSpace($oldKey)) {
        $oldKey = Get-IdeaKey $oldIdea.title $oldIdea.body $oldIdea.platform
    }
    $seenIdeaKeys[$oldKey] = $true
}
Write-Host "Previously attempted ideas loaded: $($ideaHistory.Count)"

$failedAttempts = @()
$succeededVideos = 0
$totalAttempts = 0
$targetLabel = '{0:D3}' -f $TargetVideos

while ($succeededVideos -lt $TargetVideos) {
    if ($MaxAttempts -gt 0 -and $totalAttempts -ge $MaxAttempts) {
        break
    }

    $totalAttempts++
    $approvedSlot = $succeededVideos + 1
    $slotLabel = '{0:D3}' -f $approvedSlot
    $attemptLabel = '{0:D4}' -f $totalAttempts

    $historyRef = [ref]$ideaHistory
    $idea = Invoke-NewBatchIdea $totalAttempts $historyRef $seenIdeaKeys
    $ideaHistory = @($historyRef.Value)
    $title = [string]$idea.title
    $body = [string]$idea.body

    $safeTitle = New-SafeFileName $title
    $finalVideoName = "${slotLabel}_${safeTitle}.mp4"
    $jobRoot = Join-Path $OutputRoot ("attempt_${attemptLabel}_slot_${slotLabel}")
    $imageDir = Join-Path $jobRoot 'images'
    $audioDir = Join-Path $jobRoot 'audio'
    $videoDir = Join-Path $jobRoot 'video'
    $scriptDir = Join-Path $jobRoot 'script'
    $opImageDir = Join-Path $jobRoot 'op_images'
    $opImageCacheDir = Join-Path $jobRoot 'image_cache'
    $metadataDir = Join-Path $jobRoot 'metadata'
    $scriptOut = Join-Path $scriptDir 'generated_comments.txt'

    New-Item -ItemType Directory -Force -Path $imageDir, $audioDir, $videoDir, $scriptDir | Out-Null
    if ($GenerateOpImage) {
        New-Item -ItemType Directory -Force -Path $opImageDir, $opImageCacheDir | Out-Null
    }

    if ($Platform -eq 'x') {
        Write-Step "[approved slot $slotLabel/$targetLabel | attempt $attemptLabel] X style: $title"
        Write-Host "Visible X post: $body"
    } else {
        Write-Step "[approved slot $slotLabel/$targetLabel | attempt $attemptLabel] Reddit title: $title"
        Write-Host "Reddit body: $body"
    }
    Write-Host "Idea family: $($idea.theme)"
    Write-Host "P0 format: $Format"
    Write-Host "Final MP4 if approved: $finalVideoName"

    $javaArgs = @(
        '-cp', 'out', 'redditTxtToImg.OpImageVideoSafeRunner',
        'data\comments.txt', $imageDir,
        '--platform', $Platform,
        '--auto',
        '--post-title', $title,
        '--topic', $body,
        '--count', $Count,
        '--format', $Format,
        '--llm-model', $Model,
        '--tts', $TtsEngine,
        '--tts-command', $KokoroPython,
        '--voice', $Voice,
        '--voice-series', $VoiceSeries,
        '--voice-selection', $VoiceSelection,
        '--tts-delivery', $TtsDelivery,
        '--audio-dir', $audioDir,
        '--video',
        '--concat-video',
        '--video-dir', $videoDir,
        '--script-out', $scriptOut,
        '--final-video', $finalVideoName,
        '--captions', $Captions,
        '--metadata-dir', $metadataDir,
        '--no-watermark',
        '--top'
    )

    if ($GenerateOpImage) {
        $javaArgs += @(
            '--image-mode', 'comfyui',
            '--image-dir', $opImageDir,
            '--image-cache-dir', $opImageCacheDir
        )
    }
    if ($KeepOllamaLoaded) {
        $javaArgs += '--keep-ollama-loaded'
    }
    if (-not [string]::IsNullOrWhiteSpace($SeriesId)) {
        $javaArgs += @('--series-id', $SeriesId)
    }

    try {
        & java @javaArgs
        $javaExitCode = $LASTEXITCODE
        if ($javaExitCode -ne 0) {
            throw "Video attempt $attemptLabel failed with exit code $javaExitCode."
        }

        $finalVideo = Join-Path $videoDir $finalVideoName
        if (-not (Test-Path $finalVideo)) {
            throw "Video attempt $attemptLabel finished but final video was not found: $finalVideo"
        }

        $provenanceSidecar = "$finalVideo.provenance.json"
        if (-not (Test-Path $provenanceSidecar)) {
            throw "Video attempt $attemptLabel finished but provenance sidecar was not found: $provenanceSidecar"
        }

        $copyTo = Join-Path $FinalDir $finalVideoName
        Copy-Item -Force -Path $finalVideo -Destination $copyTo
        Copy-Item -Force -Path $provenanceSidecar -Destination "$copyTo.provenance.json"

        $succeededVideos++
        Add-IdeaHistoryEvent ([pscustomobject]@{
            event = 'outcome'
            id = $idea.id
            created = (Get-Date).ToUniversalTime().ToString('o')
            status = 'approved'
            attempt = $totalAttempts
            approvedSlot = $succeededVideos
            output = $copyTo
        })
        Write-Host "Approved $succeededVideos/$TargetVideos. Saved final copy: $copyTo" -ForegroundColor Green
    } catch {
        $reason = $_.Exception.Message
        Add-IdeaHistoryEvent ([pscustomobject]@{
            event = 'outcome'
            id = $idea.id
            created = (Get-Date).ToUniversalTime().ToString('o')
            status = 'rejected'
            attempt = $totalAttempts
            approvedSlot = $approvedSlot
            reason = $reason
        })
        $failedAttempts += [pscustomobject]@{
            Attempt = $attemptLabel
            Slot = $slotLabel
            Title = $title
            Reason = $reason
        }

        Write-Host "Attempt $attemptLabel did not fill approved slot $slotLabel: $reason" -ForegroundColor Red
        if ($StopOnError) {
            throw
        }
        Write-Host 'Continuing to the next batch job.' -ForegroundColor Yellow
        Write-Host "Generating a replacement idea; approved progress remains $succeededVideos/$TargetVideos." -ForegroundColor Yellow
    }
}

Write-Step 'Batch complete'
Write-Host "Approved final videos: $succeededVideos/$TargetVideos" -ForegroundColor Green
Write-Host "Total ideas attempted:  $totalAttempts"
Write-Host "Rejected attempts:      $($failedAttempts.Count)"
Write-Host "Persistent idea history: $IdeaHistoryPath"
Write-Host "All attempt folders:     $OutputRoot"
Write-Host "Final MP4 copies:        $FinalDir" -ForegroundColor Green

if ($failedAttempts.Count -gt 0) {
    $failureReport = Join-Path $OutputRoot 'rejected_attempts.txt'
    $reportLines = @('ThreadGens rejected/failed replacement attempts', '')
    foreach ($failure in $failedAttempts) {
        $line = "[attempt $($failure.Attempt) -> slot $($failure.Slot)] $($failure.Title) :: $($failure.Reason)"
        $reportLines += $line
    }
    $reportLines | Set-Content -Path $failureReport -Encoding UTF8
    Write-Host "Rejected-attempt report: $failureReport" -ForegroundColor Yellow
}

if ($succeededVideos -lt $TargetVideos) {
    Write-Host "Batch stopped before reaching the approved target ($succeededVideos/$TargetVideos)." -ForegroundColor Red
    exit 2
}

Write-Host 'Approved target reached. Rejected attempts did not reduce the final video count.' -ForegroundColor Green
exit 0
