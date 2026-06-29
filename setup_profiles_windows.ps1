$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$ProfileDir = Join-Path $PSScriptRoot 'assets\pfp'
$DataDir = Join-Path $PSScriptRoot 'data'
$AuthorNamesFile = Join-Path $DataDir 'author_names.txt'
$ComfyProfileScript = Join-Path $PSScriptRoot 'tools\generate_comfy_profiles.py'
$ProceduralProfileScript = Join-Path $PSScriptRoot 'tools\generate_profiles.py'

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function Test-Command($Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

Write-Step 'Setting up profile generator'

if (-not (Test-Command 'python')) {
    throw 'python was not found. Run setup_windows.bat again or install Python 3.12 with winget.'
}

python --version

New-Item -ItemType Directory -Force -Path $ProfileDir | Out-Null
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

if (-not (Test-Path $ComfyProfileScript)) {
    throw "Missing AI profile generator script: $ComfyProfileScript"
}
if (-not (Test-Path $ProceduralProfileScript)) {
    throw "Missing fallback profile generator script: $ProceduralProfileScript"
}

Write-Host 'Checking profile generator Python scripts...'
python -m py_compile $ComfyProfileScript
python -m py_compile $ProceduralProfileScript

if (-not (Test-Path $AuthorNamesFile)) {
    Write-Host "Creating username file: $AuthorNamesFile"
    New-Item -ItemType File -Force -Path $AuthorNamesFile | Out-Null
}

Write-Host "Profile output folder: $ProfileDir"
Write-Host "Username file: $AuthorNamesFile"

Write-Host 'Checking whether ComfyUI is reachable at http://127.0.0.1:8188 ...'
try {
    Invoke-RestMethod -Uri 'http://127.0.0.1:8188/system_stats' -TimeoutSec 3 | Out-Null
    Write-Host 'ComfyUI is reachable. AI selfie profile generation should work.' -ForegroundColor Green
} catch {
    Write-Host 'ComfyUI is not reachable right now. That is okay for setup.' -ForegroundColor Yellow
    Write-Host 'Start ComfyUI before running generate_profiles_windows.bat in AI mode.' -ForegroundColor Yellow
}

Write-Step 'Profile generator setup complete'
Write-Host 'Run this to create profile pictures and usernames:' -ForegroundColor Green
Write-Host '  generate_profiles_windows.bat' -ForegroundColor Green
