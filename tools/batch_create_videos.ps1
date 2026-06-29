param(
    [string]$InputFile = 'data\batch_videos.txt',
    [int]$Count = 10,
    [string]$Model = 'llama3.1:8b',
    [string]$Voice = 'af_heart'
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$TtsEngine = 'kokoro'
$KokoroPython = Join-Path $RepoRoot '.venv-kokoro\Scripts\python.exe'
$OutputRoot = Join-Path $RepoRoot ('output\batch_videos\' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
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
        'Finish this story in the comments',
        'I opened my fridge at 3 AM and found a sticky note in someone else''s handwriting.',
        'Wrong answers only',
        'Why is there a shopping cart in my living room?',
        'What would you do?',
        'My neighbor keeps leaving one orange on my porch every morning.'
    ) | Set-Content -Path $Path -Encoding UTF8
}

Write-Step 'ThreadGens batch video creator'
Write-Host "Input file: $InputPath"
Write-Host "Output root: $OutputRoot"
Write-Host "Defaults: model=$Model, count=$Count, tts=$TtsEngine, voice=$Voice"

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
    throw 'Batch file needs at least 2 non-empty lines: title first, body second.'
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

    $jobRoot = Join-Path $OutputRoot ("video_$jobLabel")
    $imageDir = Join-Path $jobRoot 'images'
    $audioDir = Join-Path $jobRoot 'audio'
    $videoDir = Join-Path $jobRoot 'video'
    $scriptDir = Join-Path $jobRoot 'script'
    $scriptOut = Join-Path $scriptDir 'generated_comments.txt'

    New-Item -ItemType Directory -Force -Path $imageDir, $audioDir, $videoDir, $scriptDir | Out-Null

    Write-Step "[$jobLabel/$jobCountLabel] $title"
    Write-Host "Body: $body"

    $javaArgs = @(
        '-cp', 'out', 'redditTxtToImg.RedditScreenshotGenerator',
        'data\comments.txt', $imageDir,
        '--auto',
        '--post-title', $title,
        '--topic', $body,
        '--count', $Count,
        '--llm-model', $Model,
        '--tts', $TtsEngine,
        '--tts-command', $KokoroPython,
        '--voice', $Voice,
        '--audio-dir', $audioDir,
        '--video',
        '--concat-video',
        '--video-dir', $videoDir,
        '--script-out', $scriptOut,
        '--final-video', 'final.mp4',
        '--no-watermark',
        '--top'
    )

    & java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Video job $jobLabel failed with exit code $LASTEXITCODE."
    }

    $finalVideo = Join-Path $videoDir 'final.mp4'
    if (-not (Test-Path $finalVideo)) {
        throw "Video job $jobLabel finished but final video was not found: $finalVideo"
    }

    $copyTo = Join-Path $FinalDir ("${jobLabel}_${safeTitle}.mp4")
    Copy-Item -Force -Path $finalVideo -Destination $copyTo
    Write-Host "Saved final copy: $copyTo" -ForegroundColor Green
}

Write-Step 'Batch complete'
Write-Host "All per-video folders: $OutputRoot" -ForegroundColor Green
Write-Host "Final MP4 copies:      $FinalDir" -ForegroundColor Green
