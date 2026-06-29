$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$Model = 'llama3.1:8b'
$VoiceName = 'en_US-lessac-medium'
$VoiceDir = Join-Path $PSScriptRoot 'voices'
$VoiceFile = Join-Path $VoiceDir "$VoiceName.onnx"
$VoiceConfigFile = Join-Path $VoiceDir "$VoiceName.onnx.json"
$PiperDir = Join-Path $PSScriptRoot 'piper'
$PiperExe = Join-Path $PiperDir 'piper.exe'
$KokoroRequirements = Join-Path $PSScriptRoot 'requirements-kokoro.txt'
$KokoroVenvDir = Join-Path $PSScriptRoot '.venv-kokoro'
$KokoroPython = Join-Path $KokoroVenvDir 'Scripts\python.exe'

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function Test-Command($Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Refresh-Path {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machinePath;$userPath;$env:Path"

    $commonJavaRoots = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java"
    )

    foreach ($root in $commonJavaRoots) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like 'jdk-*' } |
                ForEach-Object { $env:Path = "$($_.FullName)\bin;$env:Path" }
        }
    }

    $ollamaUser = Join-Path $env:LOCALAPPDATA 'Programs\Ollama'
    $ollamaProgram = Join-Path $env:ProgramFiles 'Ollama'
    foreach ($path in @($ollamaUser, $ollamaProgram)) {
        if (Test-Path $path) {
            $env:Path = "$path;$env:Path"
        }
    }
}

function Install-WithWinget($Id, $Name) {
    if (-not (Test-Command 'winget')) {
        throw "winget is not available. Install $Name manually, then rerun setup_windows.bat."
    }

    Write-Host "Installing $Name with winget..."
    winget install --id $Id -e --accept-package-agreements --accept-source-agreements
    Refresh-Path
}

function Ensure-Java {
    Write-Step 'Checking Java JDK'
    Refresh-Path
    if (-not (Test-Command 'javac')) {
        Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK' 'Java JDK 21'
    }
    Refresh-Path
    if (-not (Test-Command 'javac')) {
        throw 'javac was not found after install. Close this window, open a new terminal, and rerun setup_windows.bat.'
    }
    javac -version
}

function Ensure-Python {
    Write-Step 'Checking Python for Kokoro TTS venv'
    Refresh-Path
    if (-not (Test-Command 'python')) {
        Install-WithWinget 'Python.Python.3.12' 'Python 3.12'
    }
    Refresh-Path
    if (-not (Test-Command 'python')) {
        throw 'python was not found after install. Close this window, open a new terminal, and rerun setup_windows.bat.'
    }
    python --version
}

function Ensure-Ollama {
    Write-Step 'Checking Ollama'
    Refresh-Path
    if (-not (Test-Command 'ollama')) {
        Install-WithWinget 'Ollama.Ollama' 'Ollama'
    }
    Refresh-Path
    if (-not (Test-Command 'ollama')) {
        throw 'ollama was not found after install. Close this window, open a new terminal, and rerun setup_windows.bat.'
    }

    Write-Host 'Starting Ollama server if it is not already running...'
    try {
        Start-Process -FilePath (Get-Command ollama).Source -ArgumentList 'serve' -WindowStyle Minimized -ErrorAction SilentlyContinue | Out-Null
        Start-Sleep -Seconds 5
    } catch {
        Write-Host 'Ollama may already be running. Continuing.'
    }

    Write-Host "Pulling local LLM model: $Model"
    ollama pull $Model
}

function Ensure-Piper {
    Write-Step 'Checking Piper TTS'
    if (Test-Path $PiperExe) {
        Write-Host "Using local Piper: $PiperExe"
        return
    }
    if (Test-Command 'piper') {
        Write-Host 'Piper found in PATH.'
        return
    }

    Write-Host 'Downloading latest Piper Windows release from GitHub...'
    New-Item -ItemType Directory -Force -Path $PiperDir | Out-Null
    $headers = @{ 'User-Agent' = 'ThreadGens-Setup' }
    $release = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/rhasspy/piper/releases/latest'
    $asset = $release.assets |
        Where-Object { $_.name -match 'windows.*(amd64|x64).*\.zip$' } |
        Select-Object -First 1

    if (-not $asset) {
        throw 'Could not find a Windows x64 Piper zip in the latest release. Install Piper manually or place piper.exe in the piper folder.'
    }

    $zipPath = Join-Path $PSScriptRoot 'piper_download.zip'
    $tmpPath = Join-Path $PSScriptRoot 'piper_tmp'
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $tmpPath -Recurse -Force -ErrorAction SilentlyContinue

    Invoke-WebRequest -Headers $headers -Uri $asset.browser_download_url -OutFile $zipPath
    Expand-Archive $zipPath -DestinationPath $tmpPath -Force

    $downloadedExe = Get-ChildItem $tmpPath -Filter 'piper.exe' -Recurse | Select-Object -First 1
    if (-not $downloadedExe) {
        throw 'Downloaded Piper zip did not contain piper.exe.'
    }

    Copy-Item -Path (Join-Path $downloadedExe.Directory.FullName '*') -Destination $PiperDir -Recurse -Force
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $tmpPath -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Piper installed to: $PiperDir"
}

function Ensure-PiperVoice {
    Write-Step 'Checking Piper voice model'
    New-Item -ItemType Directory -Force -Path $VoiceDir | Out-Null

    $base = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium'
    if (-not (Test-Path $VoiceFile)) {
        Write-Host "Downloading voice model: $VoiceName"
        Invoke-WebRequest -Uri "$base/$VoiceName.onnx" -OutFile $VoiceFile
    } else {
        Write-Host "Voice model already exists: $VoiceFile"
    }

    if (-not (Test-Path $VoiceConfigFile)) {
        Write-Host "Downloading voice config: $VoiceName.onnx.json"
        Invoke-WebRequest -Uri "$base/$VoiceName.onnx.json" -OutFile $VoiceConfigFile
    } else {
        Write-Host "Voice config already exists: $VoiceConfigFile"
    }
}

function Ensure-Kokoro {
    Write-Step 'Installing Kokoro TTS in isolated venv'
    Ensure-Python

    if (-not (Test-Path $KokoroRequirements)) {
        Write-Host 'requirements-kokoro.txt not found. Creating fallback requirements list.'
        @('kokoro', 'soundfile', 'numpy') | Set-Content -Path $KokoroRequirements -Encoding UTF8
    }

    if (-not (Test-Path $KokoroPython)) {
        Write-Host "Creating Kokoro venv: $KokoroVenvDir"
        python -m venv $KokoroVenvDir
    } else {
        Write-Host "Using existing Kokoro venv: $KokoroVenvDir"
    }

    if (-not (Test-Path $KokoroPython)) {
        throw "Kokoro venv python was not created: $KokoroPython"
    }

    Write-Host 'Upgrading pip inside Kokoro venv...'
    & $KokoroPython -m pip install --upgrade pip

    Write-Host 'Installing Kokoro Python packages inside Kokoro venv...'
    & $KokoroPython -m pip install --upgrade -r $KokoroRequirements

    Write-Host 'Testing Kokoro imports inside Kokoro venv...'
    & $KokoroPython -c "from kokoro import KPipeline; import soundfile; import numpy; print('Kokoro venv import test passed')"
}

function Build-ThreadGens {
    Write-Step 'Building ThreadGens'
    New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'out') | Out-Null
    $javaFiles = Get-ChildItem -Path (Join-Path $PSScriptRoot 'src\redditTxtToImg') -Filter '*.java' | ForEach-Object { $_.FullName }
    if (-not $javaFiles -or $javaFiles.Count -eq 0) {
        throw 'No Java source files found in src\redditTxtToImg.'
    }
    javac -d (Join-Path $PSScriptRoot 'out') $javaFiles
    Write-Host 'Build complete.'
}

Ensure-Java
Ensure-Ollama
Ensure-Piper
Ensure-PiperVoice
Ensure-Kokoro
Build-ThreadGens

Write-Step 'Setup complete'
Write-Host 'Run this next:' -ForegroundColor Green
Write-Host '  run_ai_windows.bat' -ForegroundColor Green
Write-Host ''
Write-Host 'Kokoro now uses this isolated Python:' -ForegroundColor Green
Write-Host "  $KokoroPython" -ForegroundColor Green
Write-Host ''
Write-Host 'Generated images will go to output\'
Write-Host 'Generated audio will go to output\audio\'
Write-Host 'Generated videos will go to output\video\'
