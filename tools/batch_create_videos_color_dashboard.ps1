param(
    [int]$TargetVideos = 30,
    [int]$Count = 10,
    [ValidateRange(1, 10)]
    [int]$Workers = 4,
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
    [string]$GenerationHistoryFile = 'data\generation_history.jsonl',
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
$DashboardCore = Join-Path $PSScriptRoot 'batch_create_videos_dashboard.ps1'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Quote-NativeArgument($Value) {
    $text = [string]$Value
    if ($text -notmatch '[\s"]') { return $text }
    return '"' + ($text -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Build-ForwardArgumentLine {
    $tokens = New-Object System.Collections.ArrayList
    foreach ($token in @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script:patchedDashboard,
        '-TargetVideos', [string]$TargetVideos,
        '-Count', [string]$Count,
        '-Workers', [string]$Workers,
        '-Model', $Model,
        '-Voice', $Voice,
        '-VoiceSeries', $VoiceSeries,
        '-VoiceSelection', $VoiceSelection,
        '-TtsDelivery', $TtsDelivery,
        '-Captions', $Captions,
        '-Platform', $Platform,
        '-Format', $Format,
        '-IdeaHistoryFile', $IdeaHistoryFile,
        '-GenerationHistoryFile', $GenerationHistoryFile,
        '-IdeaHistoryLimit', [string]$IdeaHistoryLimit,
        '-IdeaRetries', [string]$IdeaRetries,
        '-MaxAttempts', [string]$MaxAttempts
    )) { [void]$tokens.Add($token) }
    if (-not [string]::IsNullOrWhiteSpace($SeriesId)) { [void]$tokens.Add('-SeriesId'); [void]$tokens.Add($SeriesId) }
    if ($KeepOllamaLoaded) { [void]$tokens.Add('-KeepOllamaLoaded') }
    if ($GenerateOpImage) { [void]$tokens.Add('-GenerateOpImage') }
    if ($StopOnError) { [void]$tokens.Add('-StopOnError') }
    if ($SelfTest) { [void]$tokens.Add('-SelfTest') }
    return (@($tokens | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
}

function New-ColoredDashboardSource($Source) {
    $marker = 'function Render-Dashboard([switch]$Final) {'
    if (-not $Source.Contains($marker)) {
        throw 'Live dashboard colorizer could not find Render-Dashboard marker.'
    }

    $colorHelpers = @'
function Get-DashboardLineColor($Line) {
    $text = [string]$Line
    if ([string]::IsNullOrWhiteSpace($text)) { return [ConsoleColor]::Gray }

    if ($text -match '^ThreadGens LIVE BATCH MONITOR') { return [ConsoleColor]::Cyan }
    if ($text -match '^\[' -and $text -match 'APPROVED\s+\d+/\d+') {
        if ($text -match 'rejected\s+[1-9]') { return [ConsoleColor]::Yellow }
        return [ConsoleColor]::Green
    }
    if ($text -match '^Slots:') { return [ConsoleColor]::DarkCyan }
    if ($text -match '^CPU ') {
        if ($text -match '\]\s+(?<pct>\d+)%') {
            $pct = [int]$Matches.pct
            if ($pct -ge 92) { return [ConsoleColor]::Red }
            if ($pct -ge 78) { return [ConsoleColor]::Yellow }
        }
        return [ConsoleColor]::Green
    }
    if ($text -match '^GPU ') { return [ConsoleColor]::Magenta }
    if ($text -match '^Ollama gate:') { return [ConsoleColor]::Yellow }
    if ($text -match '^SLOT ATT\s+STAGE') { return [ConsoleColor]::Cyan }
    if ($text -match '^-{8,}$') { return [ConsoleColor]::DarkGray }
    if ($text -match '^RECENT EVENTS$') { return [ConsoleColor]::Cyan }
    if ($text -match '^DEBUG LOG:') { return [ConsoleColor]::DarkGray }

    if ($text -match '^\d{3}\s+\d{4}\s+(?<stage>[A-Z0-9 ]{2,12})\s+') {
        $stage = $Matches.stage.Trim()
        switch -Regex ($stage) {
            '^FAILED$' { return [ConsoleColor]::Red }
            '^APPROVED$' { return [ConsoleColor]::Green }
            '^OLLAMA$|^NOVELTY$' { return [ConsoleColor]::Yellow }
            '^SCRIPT READY$|^PROFILES$|^IMAGES$|^RENDER QUEUE$|^STARTING$' { return [ConsoleColor]::Cyan }
            '^TTS$' { return [ConsoleColor]::DarkYellow }
            '^VIDEO$' { return [ConsoleColor]::Magenta }
            '^VALIDATE$' { return [ConsoleColor]::White }
            '^FINALIZE$|^P2 AUDIT$|^SAVE$' { return [ConsoleColor]::Green }
            default { return [ConsoleColor]::Gray }
        }
    }

    if ($text -match '^\s+\d{2}:\d{2}:\d{2}\s+') {
        if ($text -match '(?i)FAILED|ERROR|rejected|too long|timed out|did not fill|replacement queued|SEED FAILED') { return [ConsoleColor]::Red }
        if ($text -match '(?i)APPROVED|saved') { return [ConsoleColor]::Green }
        if ($text -match '(?i)started|launch') { return [ConsoleColor]::DarkCyan }
        if ($text -match '(?i)retry|regenerat|render-fit guard') { return [ConsoleColor]::Yellow }
        return [ConsoleColor]::Gray
    }

    return [ConsoleColor]::Gray
}

function Write-DashboardColorLine($Text, $Color, [int]$Width) {
    $line = Truncate-Line ([string]$Text) $Width
    $padded = $line.PadRight($Width)
    $oldColor = [Console]::ForegroundColor
    try {
        [Console]::ForegroundColor = $Color
        [Console]::WriteLine($padded)
    } catch {
        Write-Host $line -ForegroundColor $Color
    } finally {
        try { [Console]::ForegroundColor = $oldColor } catch { }
    }
}

'@

    $patched = $Source.Replace($marker, $colorHelpers + $marker)

    $oldLoop = @'
    for ($i = 0; $i -lt $renderCount; $i++) {
        $line = if ($i -lt $lines.Count) { [string]$lines[$i] } else { '' }
        $line = Truncate-Line $line $width
        try { [Console]::WriteLine($line.PadRight($width)) } catch { Write-Host $line }
    }
'@
    $newLoop = @'
    for ($i = 0; $i -lt $renderCount; $i++) {
        $line = if ($i -lt $lines.Count) { [string]$lines[$i] } else { '' }
        $color = Get-DashboardLineColor $line
        Write-DashboardColorLine $line $color $width
    }
'@
    if (-not $patched.Contains($oldLoop)) {
        throw 'Live dashboard colorizer could not find the dashboard render loop.'
    }
    return $patched.Replace($oldLoop, $newLoop)
}

if (-not (Test-Path $DashboardCore)) {
    throw "Live dashboard core was not found: $DashboardCore"
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('threadgens-color-dashboard-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$script:patchedDashboard = Join-Path $tempRoot 'batch_create_videos_dashboard_colored.ps1'

try {
    $source = Get-Content -Raw -Path $DashboardCore -Encoding UTF8
    $patched = New-ColoredDashboardSource $source
    [System.IO.File]::WriteAllText($script:patchedDashboard, $patched, $Utf8NoBom)

    if ($SelfTest) {
        if ($patched -notmatch 'Get-DashboardLineColor' -or $patched -notmatch 'Write-DashboardColorLine') {
            throw 'Dashboard color self-test failed to inject color helpers.'
        }
    }

    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
    $argumentLine = Build-ForwardArgumentLine
    $process = Start-Process -FilePath $powerShellExe -ArgumentList $argumentLine -PassThru -NoNewWindow -Wait
    exit $process.ExitCode
} finally {
    Remove-Item -Recurse -Force -Path $tempRoot -ErrorAction SilentlyContinue
}
