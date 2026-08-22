$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Model = 'llama3.1:8b'
$Count = 10
$Platform = 'reddit'
$Format = 'auto'
$Tts = 'kokoro'
$Voice = 'af_heart'
$VideoFlags = @()
$ImageFlags = @()
$KeepOllamaFlags = @('--keep-ollama-loaded')
$KokoroPython = Join-Path $RepoRoot '.venv-kokoro\Scripts\python.exe'
$PythonCmd = 'python'
$TtsCmd = ''

$env:THREADGENS_KOKORO_VERBOSE = '0'
$env:PYTHONWARNINGS = 'ignore'
$env:HF_HUB_DISABLE_PROGRESS_BARS = '1'
$env:TOKENIZERS_PARALLELISM = 'false'

Write-Host ''
Write-Host 'ThreadGens local AI runner'
Write-Host 'Pipeline: P0 content originality + dynamic video'
Write-Host ''

Write-Host 'Choose platform/thread style:'
Write-Host '1. Reddit thread'
Write-Host '2. X post and replies'
$platformChoice = Read-Host 'Choice [1/2, default 1]'
if ($platformChoice -eq '2' -or $platformChoice.ToLowerInvariant() -eq 'x') { $Platform = 'x' }

Write-Host ''
Write-Host 'Choose TTS engine:'
Write-Host '1. Kokoro - recommended/default'
Write-Host '2. Piper  - fallback'
$ttsChoice = Read-Host 'Choice [1/2, default 1]'
if ($ttsChoice -eq '2' -or $ttsChoice.ToLowerInvariant() -eq 'piper') { $Tts = 'piper' }

if ($Tts -eq 'piper') {
    $piperCmd = Join-Path $RepoRoot 'piper\piper.exe'
    $TtsCmd = if (Test-Path $piperCmd) { $piperCmd } else { 'piper' }
    $voiceInput = Read-Host 'Voice name or ONNX path [en_US-lessac-medium]'
    if (-not [string]::IsNullOrWhiteSpace($voiceInput)) { $Voice = $voiceInput } else { $Voice = 'en_US-lessac-medium' }
} else {
    if (Test-Path $KokoroPython) {
        $TtsCmd = $KokoroPython
    } else {
        Write-Host "Kokoro venv was not found: $KokoroPython" -ForegroundColor Yellow
        $useSystem = Read-Host 'Use system Python anyway? y/N'
        if ($useSystem.ToLowerInvariant() -ne 'y') { exit 1 }
        $TtsCmd = $PythonCmd
    }
    Write-Host 'Common Kokoro voices: af_heart af_bella af_nicole am_adam am_michael bf_emma bm_george'
    $voiceInput = Read-Host 'Kokoro voice [af_heart]'
    if (-not [string]::IsNullOrWhiteSpace($voiceInput)) { $Voice = $voiceInput }
}

Write-Host ''
Write-Host 'Building Java files...'
New-Item -ItemType Directory -Force -Path (Join-Path $RepoRoot 'out') | Out-Null
$javaFiles = Get-ChildItem -Path (Join-Path $RepoRoot 'src\redditTxtToImg') -Filter '*.java' | ForEach-Object { $_.FullName }
& javac -d (Join-Path $RepoRoot 'out') $javaFiles
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }

if ($Platform -eq 'x') {
    $postTitle = Read-Host 'Optional X reply style, ex wrong answers only/advice/finish story [normal replies]'
    $topic = Read-Host 'Visible original X post text [I just saw something weird and I need someone else to explain it.]'
    if ([string]::IsNullOrWhiteSpace($topic)) { $topic = 'I just saw something weird and I need someone else to explain it.' }
} else {
    $postTitle = Read-Host 'Reddit post title [Finish this story in the comments]'
    if ([string]::IsNullOrWhiteSpace($postTitle)) { $postTitle = 'Finish this story in the comments' }
    $topic = Read-Host 'Original post/body [weird everyday stories]'
    if ([string]::IsNullOrWhiteSpace($topic)) { $topic = 'weird everyday stories' }
}

Write-Host ''
Write-Host 'Choose P0 content/video format:'
Write-Host '1. Auto - fit the prompt and rotate recent formats (recommended)'
Write-Host '2. Thread story'
Write-Host '3. Confession'
Write-Host '4. Debate'
Write-Host '5. Best answers'
Write-Host '6. Escalating conversation'
$formatChoice = Read-Host 'Choice [1-6, default 1]'
switch ($formatChoice.ToLowerInvariant()) {
    '2' { $Format = 'thread_story' }
    'thread_story' { $Format = 'thread_story' }
    'thread' { $Format = 'thread_story' }
    '3' { $Format = 'confession' }
    'confession' { $Format = 'confession' }
    '4' { $Format = 'debate' }
    'debate' { $Format = 'debate' }
    '5' { $Format = 'best_answers' }
    'best_answers' { $Format = 'best_answers' }
    'answers' { $Format = 'best_answers' }
    '6' { $Format = 'escalating_conversation' }
    'escalating_conversation' { $Format = 'escalating_conversation' }
    'conversation' { $Format = 'escalating_conversation' }
    default { $Format = 'auto' }
}

$countInput = Read-Host 'How many total slides/posts [10]'
if (-not [string]::IsNullOrWhiteSpace($countInput)) { $Count = [int]$countInput }

$makeImage = Read-Host "Generate an OP image for this $Platform post with local ComfyUI RealVisXL? y/N"
if ($makeImage.ToLowerInvariant() -eq 'y' -or $makeImage.ToLowerInvariant() -eq 'yes') {
    $ImageFlags = @('--image-mode', 'comfyui')
}

$makeVideo = Read-Host 'Make stitched MP4 video with dynamic P0 compositions? y/N'
if ($makeVideo.ToLowerInvariant() -eq 'y' -or $makeVideo.ToLowerInvariant() -eq 'yes') {
    $VideoFlags = @('--video', '--concat-video')
}

$unloadOllama = Read-Host 'Unload Ollama after text generation? y/N [default N, keeps model loaded]'
if ($unloadOllama.ToLowerInvariant() -eq 'y' -or $unloadOllama.ToLowerInvariant() -eq 'yes') {
    $KeepOllamaFlags = @()
}

Write-Host ''
Write-Host "Platform:     $Platform"
Write-Host "Format:       $Format"
Write-Host "Reply style:  $postTitle"
Write-Host "Original:     $topic"
Write-Host "Count:        $Count"
Write-Host "TTS:          $Tts"
Write-Host "Voice:        $Voice"
Write-Host "Cmd:          $TtsCmd"
Write-Host "OP image:     $($ImageFlags -join ' ')"
Write-Host "Video:        $($VideoFlags -join ' ')"
Write-Host "Ollama:       $(if ($KeepOllamaFlags.Count -gt 0) { 'keep loaded' } else { 'unload after text' })"
Write-Host ''

$javaArgs = @(
    '-cp', 'out', 'redditTxtToImg.OpImageVideoSafeRunner',
    '--platform', $Platform,
    '--auto',
    '--topic', $topic,
    '--count', $Count,
    '--format', $Format,
    '--llm-model', $Model,
    '--tts', $Tts,
    '--tts-command', $TtsCmd,
    '--voice', $Voice,
    '--no-watermark',
    '--top'
)

if ($Platform -ne 'x' -or -not [string]::IsNullOrWhiteSpace($postTitle)) {
    $javaArgs += @('--post-title', $postTitle)
}
$javaArgs += $ImageFlags
$javaArgs += $VideoFlags
$javaArgs += $KeepOllamaFlags

& java @javaArgs
if ($LASTEXITCODE -ne 0) { throw "Generation failed with exit code $LASTEXITCODE." }

Write-Host ''
Write-Host 'Done.'
Write-Host 'Text:        output\script\generated_comments.txt'
Write-Host 'Images:      output\'
Write-Host 'OP images:   output\images\'
Write-Host 'Audio:       output\audio\'
Write-Host 'Clips:       output\video\'
Write-Host 'Final video: output\video\final.mp4'
