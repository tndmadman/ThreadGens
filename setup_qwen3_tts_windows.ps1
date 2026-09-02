$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$QwenVenvDir = Join-Path $PSScriptRoot '.venv-qwen3-tts'
$QwenPython = Join-Path $QwenVenvDir 'Scripts\python.exe'
$Requirements = Join-Path $PSScriptRoot 'requirements-qwen3-tts.txt'
$ServerScript = Join-Path $PSScriptRoot 'tools\qwen3_tts_server.py'
$PyTorchIndex = if ([string]::IsNullOrWhiteSpace($env:THREADGENS_PYTORCH_INDEX)) {
    'https://download.pytorch.org/whl/cu124'
} else {
    $env:THREADGENS_PYTORCH_INDEX
}

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function Test-Command($Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function New-QwenVenv {
    if (Test-Command 'py') {
        & py -3.12 -c "import sys; print(sys.version)" *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'Creating Qwen3-TTS venv with Python 3.12...'
            & py -3.12 -m venv $QwenVenvDir
            return
        }
    }
    if (-not (Test-Command 'python')) {
        throw 'Python was not found. Run setup_windows.bat first so Python 3.12 is installed.'
    }
    Write-Host 'Creating Qwen3-TTS venv with the default Python...'
    & python -m venv $QwenVenvDir
}

Write-Step 'Setting up Qwen3-TTS 1.7B for NVIDIA CUDA'

if (-not (Test-Path $Requirements)) {
    throw "Missing Qwen3-TTS requirements file: $Requirements"
}
if (-not (Test-Path $ServerScript)) {
    throw "Missing Qwen3-TTS server helper: $ServerScript"
}

if (-not (Test-Path $QwenPython)) {
    New-QwenVenv
} else {
    Write-Host "Using existing Qwen3-TTS venv: $QwenVenvDir"
}
if (-not (Test-Path $QwenPython)) {
    throw "Qwen3-TTS venv Python was not created: $QwenPython"
}

Write-Step 'Upgrading pip'
& $QwenPython -m pip install --upgrade pip setuptools wheel
if ($LASTEXITCODE -ne 0) { throw 'Failed to upgrade pip in Qwen3-TTS venv.' }

Write-Step 'Installing CUDA-enabled PyTorch'
Write-Host "PyTorch wheel index: $PyTorchIndex"
& $QwenPython -m pip install --upgrade torch torchaudio --index-url $PyTorchIndex
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install CUDA PyTorch/torchaudio from $PyTorchIndex"
}

Write-Step 'Installing Qwen3-TTS dependencies'
& $QwenPython -m pip install --upgrade -r $Requirements
if ($LASTEXITCODE -ne 0) { throw 'Failed to install Qwen3-TTS support dependencies.' }

# qwen-tts declares torchaudio without a CUDA wheel source. Install the package
# without dependencies after the CUDA stack above so pip cannot silently replace
# torch/torchaudio with a different build.
& $QwenPython -m pip install --upgrade --no-deps qwen-tts==0.1.1
if ($LASTEXITCODE -ne 0) { throw 'Failed to install qwen-tts 0.1.1.' }

Write-Step 'Validating NVIDIA CUDA access'
& $QwenPython -c "import torch; from qwen_tts import Qwen3TTSModel; assert torch.cuda.is_available(), 'CUDA is not available to PyTorch'; print('CUDA:', torch.version.cuda); print('GPU:', torch.cuda.get_device_name(0)); print('VRAM GB:', round(torch.cuda.get_device_properties(0).total_memory / 1024**3, 1))"
if ($LASTEXITCODE -ne 0) {
    throw 'Qwen3-TTS CUDA validation failed. The Qwen venv cannot see the NVIDIA GPU.'
}

Write-Step 'Caching and validating Qwen3-TTS 1.7B CustomVoice'
Write-Host 'The first setup downloads the Qwen model weights. Later runs reuse the local Hugging Face cache.'
& $QwenPython $ServerScript --check-only
if ($LASTEXITCODE -ne 0) {
    throw 'Qwen3-TTS model load test failed. See the error above before running ThreadGens.'
}

Write-Step 'Qwen3-TTS setup complete'
Write-Host 'ThreadGens Qwen Python:' -ForegroundColor Green
Write-Host "  $QwenPython" -ForegroundColor Green
Write-Host 'Default model: Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice' -ForegroundColor Green
Write-Host 'Default English speakers: Ryan, Aiden' -ForegroundColor Green
Write-Host 'The model service starts automatically on localhost:8765 when narration is first requested.' -ForegroundColor Green
