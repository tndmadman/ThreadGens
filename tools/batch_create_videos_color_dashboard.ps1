param(
    [int]$TargetVideos = 30,
    [int]$Count = 10,
    [ValidateRange(1, 10)]
    [int]$Workers = 4,
    [string]$Model = 'llama3.1:8b',
    [string]$Voice = 'af_heart',
    [string]$VoiceSeries = 'af_heart,af_bella,af_nicole,bf_emma',
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
    [ValidateSet('auto', 'single', 'series', 'per-slot')]
    [string]$FormatSelection = 'per-slot',
    [string]$FormatSeries = 'thread_story,confession,debate,best_answers,escalating_conversation',
    [string]$FormatVariant = 'auto',
    [string]$IdeaHistoryFile = 'data\batch_idea_history.jsonl',
    [string]$GenerationHistoryFile = 'data\generation_history.jsonl',
    [string]$PublishHistoryFile = 'data\publish_history.jsonl',
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
$script:patchedDashboard = ''
$script:dashboardProcess = $null
$script:killJobHandle = [IntPtr]::Zero

function Quote-NativeArgument($Value) {
    $text = [string]$Value
    if ($text -notmatch '[\s"]') { return $text }
    return '"' + ($text -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Normalize-ProcessPathEnvironment {
    $variables = [Environment]::GetEnvironmentVariables([EnvironmentVariableTarget]::Process)
    $pathKeys = @($variables.Keys | Where-Object { ([string]$_).Equals('PATH', [StringComparison]::OrdinalIgnoreCase) })
    if ($pathKeys.Count -le 1) { return }

    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $segments = New-Object System.Collections.ArrayList
    foreach ($key in $pathKeys) {
        foreach ($segment in @(([string]$variables[$key]) -split ';')) {
            $value = $segment.Trim()
            if (-not [string]::IsNullOrWhiteSpace($value) -and $seen.Add($value)) { [void]$segments.Add($value) }
        }
    }
    foreach ($key in $pathKeys) {
        [Environment]::SetEnvironmentVariable([string]$key, $null, [EnvironmentVariableTarget]::Process)
    }
    [Environment]::SetEnvironmentVariable('Path', ($segments -join ';'), [EnvironmentVariableTarget]::Process)
}

function Stop-ProcessTree($Process) {
    if ($null -eq $Process) { return }
    try {
        $Process.Refresh()
        if (-not $Process.HasExited) {
            if ($env:OS -eq 'Windows_NT') {
                & taskkill.exe /PID $Process.Id /T /F *> $null
            } else {
                $Process.Kill()
            }
        }
    } catch { }
}

function Initialize-KillOnCloseJobType {
    if ($env:OS -ne 'Windows_NT') { return }
    if ('ThreadGensKillOnCloseJob' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class ThreadGensKillOnCloseJob
{
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private const int JobObjectExtendedLimitInformation = 9;

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(
        IntPtr hJob,
        int jobObjectInfoClass,
        ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION lpJobObjectInfo,
        uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr hObject);

    public static IntPtr CreateKillOnClose()
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero)
            throw new Win32Exception(Marshal.GetLastWin32Error());

        var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        uint length = (uint)Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, ref info, length))
        {
            int error = Marshal.GetLastWin32Error();
            CloseHandle(job);
            throw new Win32Exception(error);
        }
        return job;
    }

    public static void AssignOrThrow(IntPtr job, IntPtr processHandle)
    {
        if (!AssignProcessToJobObject(job, processHandle))
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}
'@
}

function New-KillOnCloseJob {
    if ($env:OS -ne 'Windows_NT') { return [IntPtr]::Zero }
    Initialize-KillOnCloseJobType
    return [ThreadGensKillOnCloseJob]::CreateKillOnClose()
}

function Add-ProcessToKillOnCloseJob([IntPtr]$JobHandle, $Process) {
    if ($env:OS -ne 'Windows_NT' -or $JobHandle -eq [IntPtr]::Zero -or $null -eq $Process) { return }
    [ThreadGensKillOnCloseJob]::AssignOrThrow($JobHandle, $Process.Handle)
}

function Close-KillOnCloseJob([IntPtr]$JobHandle) {
    if ($env:OS -ne 'Windows_NT' -or $JobHandle -eq [IntPtr]::Zero) { return }
    try { [void][ThreadGensKillOnCloseJob]::CloseHandle($JobHandle) } catch { }
}

function Test-KillOnCloseJob {
    if ($env:OS -ne 'Windows_NT') { return }
    $testJob = [IntPtr]::Zero
    $testProcess = $null
    try {
        $testJob = New-KillOnCloseJob
        $powerShellExe = Join-Path $PSHOME 'powershell.exe'
        if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
        $testProcess = Start-Process -FilePath $powerShellExe -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 30"' -PassThru -WindowStyle Hidden
        Add-ProcessToKillOnCloseJob $testJob $testProcess
        Close-KillOnCloseJob $testJob
        $testJob = [IntPtr]::Zero
        if (-not $testProcess.WaitForExit(4000)) {
            throw 'Kill-on-close Job Object did not terminate its test child process.'
        }
    } finally {
        if ($testJob -ne [IntPtr]::Zero) { Close-KillOnCloseJob $testJob }
        Stop-ProcessTree $testProcess
    }
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
        '-FormatSelection', $FormatSelection,
        '-FormatSeries', $FormatSeries,
        '-FormatVariant', $FormatVariant,
        '-IdeaHistoryFile', $IdeaHistoryFile,
        '-GenerationHistoryFile', $GenerationHistoryFile,
        '-PublishHistoryFile', $PublishHistoryFile,
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
    $repoLiteral = $RepoRoot.Replace("'", "''")
    $patched = $patched.Replace('$RepoRoot = Split-Path -Parent $PSScriptRoot', ('$RepoRoot = ''' + $repoLiteral + ''''))
    $patched = $patched.Replace(
        'ThreadGens LIVE BATCH MONITOR  |  $statusWord  |  runtime $(Format-Duration $elapsed)',
        'ThreadGens LIVE BATCH MONITOR  |  $statusWord  |  runtime $(Format-Duration $elapsed)  |  Q/Esc=STOP')

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
    $patched = $patched.Replace($oldLoop, $newLoop)

    $oldFinally = @'
} finally {
    if ($null -ne $stdoutReader) { $stdoutReader.Dispose() }
    if ($null -ne $stderrReader) { $stderrReader.Dispose() }
    if ($null -ne $stdoutStream) { $stdoutStream.Dispose() }
    if ($null -ne $stderrStream) { $stderrStream.Dispose() }
    try { Remove-Item -Recurse -Force -Path $monitorTemp -ErrorAction SilentlyContinue } catch { }
}
'@
    $newFinally = @'
} finally {
    if ($null -ne $process) {
        try {
            $process.Refresh()
            if (-not $process.HasExited) {
                if ($env:OS -eq 'Windows_NT') {
                    & taskkill.exe /PID $process.Id /T /F *> $null
                } else {
                    $process.Kill()
                }
            }
        } catch { }
    }
    if ($null -ne $stdoutReader) { $stdoutReader.Dispose() }
    if ($null -ne $stderrReader) { $stderrReader.Dispose() }
    if ($null -ne $stdoutStream) { $stdoutStream.Dispose() }
    if ($null -ne $stderrStream) { $stderrStream.Dispose() }
    try { Remove-Item -Recurse -Force -Path $monitorTemp -ErrorAction SilentlyContinue } catch { }
}
'@
    if (-not $patched.Contains($oldFinally)) {
        throw 'Live dashboard colorizer could not find the dashboard cleanup block.'
    }
    return $patched.Replace($oldFinally, $newFinally)
}

if (-not (Test-Path $DashboardCore)) {
    throw "Live dashboard core was not found: $DashboardCore"
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('threadgens-color-dashboard-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$script:patchedDashboard = Join-Path $tempRoot 'batch_create_videos_dashboard_colored.ps1'
$exitCode = 1
$stoppedByUser = $false

try {
    Normalize-ProcessPathEnvironment
    $source = Get-Content -Raw -Path $DashboardCore -Encoding UTF8
    $patched = New-ColoredDashboardSource $source
    [System.IO.File]::WriteAllText($script:patchedDashboard, $patched, $Utf8NoBom)

    if ($SelfTest) {
        if ($patched -notmatch 'Get-DashboardLineColor' -or $patched -notmatch 'Write-DashboardColorLine') {
            throw 'Dashboard color self-test failed to inject color helpers.'
        }
        if ($patched -match '\$RepoRoot = Split-Path -Parent \$PSScriptRoot') {
            throw 'Dashboard color self-test failed to preserve the real repository root.'
        }
        if ($patched -notmatch 'taskkill\.exe /PID \$process\.Id /T /F') {
            throw 'Dashboard shutdown self-test failed to inject engine process-tree cleanup.'
        }
        Test-KillOnCloseJob
    }

    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
    $argumentLine = Build-ForwardArgumentLine
    $script:dashboardProcess = Start-Process -FilePath $powerShellExe -ArgumentList $argumentLine -PassThru -NoNewWindow

    if ($env:OS -eq 'Windows_NT') {
        try {
            $script:killJobHandle = New-KillOnCloseJob
            Add-ProcessToKillOnCloseJob $script:killJobHandle $script:dashboardProcess
        } catch {
            if ($script:killJobHandle -ne [IntPtr]::Zero) {
                Close-KillOnCloseJob $script:killJobHandle
                $script:killJobHandle = [IntPtr]::Zero
            }
            Write-Warning "Windows kill-on-close process guard could not be enabled; Ctrl+C cleanup will use taskkill fallback: $($_.Exception.Message)"
        }
    }

    while (-not $script:dashboardProcess.HasExited) {
        if (-not $SelfTest) {
            try {
                if ([Console]::KeyAvailable) {
                    $key = [Console]::ReadKey($true)
                    if ($key.Key -eq [ConsoleKey]::Q -or $key.Key -eq [ConsoleKey]::Escape) {
                        $stoppedByUser = $true
                        Write-Host "`nStopping ThreadGens and all active workers..." -ForegroundColor Yellow
                        Stop-ProcessTree $script:dashboardProcess
                        break
                    }
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 200
        try { $script:dashboardProcess.Refresh() } catch { }
    }

    try { $script:dashboardProcess.WaitForExit() } catch { }
    $exitCode = if ($stoppedByUser) { 130 } else { $script:dashboardProcess.ExitCode }
} finally {
    Stop-ProcessTree $script:dashboardProcess
    if ($script:killJobHandle -ne [IntPtr]::Zero) {
        Close-KillOnCloseJob $script:killJobHandle
        $script:killJobHandle = [IntPtr]::Zero
    }
    Remove-Item -Recurse -Force -Path $tempRoot -ErrorAction SilentlyContinue
}

exit $exitCode
