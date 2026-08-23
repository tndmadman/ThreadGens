param(
    [Parameter(Mandatory = $true)]
    [string]$JobFile
)

$ErrorActionPreference = 'Stop'

function Append-WorkerLog($Path, $Message) {
    $folder = Split-Path -Parent $Path
    if ($folder) {
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
    }
    [System.IO.File]::AppendAllText(
        $Path,
        ([string]$Message) + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
}

try {
    if (-not (Test-Path $JobFile)) {
        throw "Parallel worker job file was not found: $JobFile"
    }

    $job = Get-Content -Raw -Path $JobFile -Encoding UTF8 | ConvertFrom-Json
    $repoRoot = [string]$job.repoRoot
    $logPath = [string]$job.logPath
    $palette = [string]$job.palette
    $javaArgs = @($job.javaArgs | ForEach-Object { [string]$_ })

    if ([string]::IsNullOrWhiteSpace($repoRoot) -or -not (Test-Path $repoRoot)) {
        throw "Parallel worker repo root is invalid: $repoRoot"
    }
    if ([string]::IsNullOrWhiteSpace($logPath)) {
        throw 'Parallel worker log path is empty.'
    }
    if ($javaArgs.Count -eq 0) {
        throw 'Parallel worker Java argument list is empty.'
    }

    Set-Location $repoRoot
    $env:THREADGENS_PALETTE = $palette

    Append-WorkerLog $logPath "[worker] starting pid=$PID palette=$palette"
    Append-WorkerLog $logPath "[worker] java argument count=$($javaArgs.Count)"

    & java @javaArgs 2>&1 | ForEach-Object {
        Append-WorkerLog $logPath ([string]$_)
    }
    $exitCode = $LASTEXITCODE
    Append-WorkerLog $logPath "[worker] java exit code=$exitCode"
    exit $exitCode
} catch {
    $message = $_.Exception.Message
    try {
        if ($null -ne $logPath -and -not [string]::IsNullOrWhiteSpace([string]$logPath)) {
            Append-WorkerLog $logPath "[worker] launcher failure: $message"
            Append-WorkerLog $logPath ($_ | Out-String)
        }
    } catch {
    }
    Write-Error $message
    exit 97
} finally {
    Remove-Item Env:THREADGENS_PALETTE -ErrorAction SilentlyContinue
}
