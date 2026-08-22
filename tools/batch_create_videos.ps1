param(
    [string]$InputFile = 'data\batch_videos.txt',
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
    [string]$Captions = 'word',
    [string]$Platform = 'reddit',
    [ValidateSet('auto', 'thread_story', 'confession', 'debate', 'best_answers', 'escalating_conversation')]
    [string]$Format = 'auto',
    [switch]$KeepOllamaLoaded,
    [switch]$GenerateOpImage
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

$TtsEngine = 'kokoro'
$KokoroPython = Join-Path $RepoRoot '.venv-kokoro\Scripts\python.exe'
$OutputRoot = Join-Path $RepoRoot ('output\batch_videos\' + $Platform + '_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
$FinalDir = Join-Path $OutputRoot 'final_videos'
$InputPath = if ([System.IO.Path]::IsPathRooted($InputFile)) { $InputFile } else { Join-Path $RepoRoot $InputFile }

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function New-SafeFileName($Value) {
    $name = $Value.ToLowerInvariant()
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

function Create-SampleInput($Path) {
    $folder = Split-Path -Parent $Path
    if ($folder) {
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
    }
    @(
        'Wrong answers only',
        'Why is there a shopping cart in my living room?',
        'Give practical advice',
        'My neighbor keeps leaving one orange on my porch every morning.',
        'Finish this story in the replies',
        'I opened my fridge at 3 AM and found a sticky note in someone else''s handwriting.'
    ) | Set-Content -Path $Path -Encoding UTF8
}

Write-Step 'ThreadGens batch video creator'
Write-Host "Input file: $InputPath"
Write-Host 'Input format: 2 non-empty lines per video.'
if ($Platform -eq 'x') {
    Write-Host 'X format: line 1 = hidden reply style, line 2 = visible X post text.'
} else {
    Write-Host 'Reddit format: line 1 = post title, line 2 = post body.'
}
Write-Host "Output root: $OutputRoot"
Write-Host "Defaults: platform=$Platform, format=$Format, model=$Model, count=$Count, tts=$TtsEngine"
Write-Host "P1 voice selection: $VoiceSelection from [$VoiceSeries], delivery=$TtsDelivery, captions=$Captions"
Write-Host 'Kokoro console: quiet'
if ($GenerateOpImage) {
    Write-Host 'OP image generation: enabled through local ComfyUI RealVisXL' -ForegroundColor Green
    Write-Host 'ComfyUI must already be running at http://127.0.0.1:8188 with RealVisXL_V5.0_fp32.safetensors installed.' -ForegroundColor Yellow
} else {
    Write-Host 'OP image generation: disabled'
}
if ($KeepOllamaLoaded) {
    Write-Host 'Ollama unload: disabled, keeping model loaded between videos' -ForegroundColor Green
} else {
    Write-Host 'Ollama unload: enabled after each script'
}

if (-not (Test-Path $InputPath)) {
    Create-SampleInput $InputPath
    Write-Host "Created sample input file: $InputPath" -ForegroundColor Yellow
    Write-Host 'Edit that file, then run batch_create_videos_windows.bat again.' -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $KokoroPython)) {
    throw "Kokoro Python was not found: $KokoroPython. Run setup_windows.bat first."
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

$rawLines = Get-Content -Path $InputPath -Encoding UTF8
$lines = @($rawLines | Where-Object { $_.Trim().Length -gt 0 })
if ($lines.Count -lt 2) {
    throw 'Batch file needs at least 2 non-empty lines: Reddit title/X style line first, Reddit body/X post text second.'
}

if (($lines.Count % 2) -ne 0) {
    Write-Host 'Warning: input has an odd number of non-empty lines. The last line will be ignored.' -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
New-Item -ItemType Directory -Force -Path $FinalDir | Out-Null

$jobCount = [int]([Math]::Floor($lines.Count / 2))
$jobCountLabel = '{0:D3}' -f $jobCount
Write-Step "Creating $jobCount video(s)"

for ($i = 0; $i -lt ($jobCount * 2); $i += 2) {
    $jobNumber = [int](($i / 2) + 1)
    $title = $lines[$i].Trim()
    $body = $lines[$i + 1].Trim()
    $jobLabel = '{0:D3}' -f $jobNumber
    $safeTitle = New-SafeFileName $title
    $finalVideoName = "${jobLabel}_${safeTitle}.mp4"

    $jobRoot = Join-Path $OutputRoot ("video_$jobLabel")
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
        Write-Step "[$jobLabel/$jobCountLabel] X style: $title"
        Write-Host "Visible X post: $body"
    } else {
        Write-Step "[$jobLabel/$jobCountLabel] Reddit title: $title"
        Write-Host "Reddit body: $body"
    }
    Write-Host "P0 format: $Format"
    if ($GenerateOpImage) {
        Write-Host 'OP image: ComfyUI RealVisXL enabled'
    }
    Write-Host "Final MP4: $finalVideoName"

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

    & java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Video job $jobLabel failed with exit code $LASTEXITCODE."
    }

    $finalVideo = Join-Path $videoDir $finalVideoName
    if (-not (Test-Path $finalVideo)) {
        throw "Video job $jobLabel finished but final video was not found: $finalVideo"
    }

    $copyTo = Join-Path $FinalDir $finalVideoName
    Copy-Item -Force -Path $finalVideo -Destination $copyTo
    $provenanceSidecar = "$finalVideo.provenance.json"
    if (-not (Test-Path $provenanceSidecar)) {
        throw "Video job $jobLabel finished but provenance sidecar was not found: $provenanceSidecar"
    }
    Copy-Item -Force -Path $provenanceSidecar -Destination "$copyTo.provenance.json"
    Write-Host "Saved final copy: $copyTo" -ForegroundColor Green
}

Write-Step 'Batch complete'
Write-Host "All per-video folders: $OutputRoot" -ForegroundColor Green
Write-Host "Final MP4 copies:      $FinalDir" -ForegroundColor Green
