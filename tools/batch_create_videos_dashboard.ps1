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
Set-Location $RepoRoot
$EngineScript = Join-Path $RepoRoot 'tools\batch_create_videos_parallel.ps1'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:runStarted = Get-Date
$script:workersByAttempt = @{}
$script:slotStates = @{}
$script:recentEvents = New-Object System.Collections.ArrayList
$script:approved = 0
$script:rejected = 0
$script:attempts = 0
$script:lastStartedAttempt = ''
$script:outputRoot = ''
$script:debugPath = ''
$script:debugBuffer = New-Object System.Collections.ArrayList
$script:encoder = if ([string]::IsNullOrWhiteSpace($env:THREADGENS_VIDEO_ENCODER)) { 'auto / waiting for probe' } else { "$($env:THREADGENS_VIDEO_ENCODER) / waiting for probe" }
$script:masterStage = 'STARTING'
$script:ollamaState = 'serialized / starting'
$script:lastDashboardLineCount = 0
$script:cpuHistory = New-Object System.Collections.ArrayList
$script:gpuHistory = New-Object System.Collections.ArrayList
$script:lastSystemSample = $null
$script:lastSystemSampleAt = [DateTime]::MinValue

for ($slot = 1; $slot -le $TargetVideos; $slot++) {
    $script:slotStates[[string]$slot] = 'waiting'
}

function Limit-Percent($Value) {
    if ($null -eq $Value) { return -1 }
    $number = [double]$Value
    if ($number -lt 0) { return 0 }
    if ($number -gt 100) { return 100 }
    return $number
}

function Format-Duration([TimeSpan]$Value) {
    if ($Value.TotalHours -ge 1) { return $Value.ToString('hh\:mm\:ss') }
    return $Value.ToString('mm\:ss')
}

function Format-Bar($Percent, [int]$Width = 14) {
    if ($Width -lt 3) { $Width = 3 }
    if ($Percent -lt 0) { return '[' + ('?' * $Width) + ']' }
    $safe = Limit-Percent $Percent
    $filled = [int][Math]::Round(($safe / 100.0) * $Width)
    if ($filled -gt $Width) { $filled = $Width }
    return '[' + ('#' * $filled) + ('-' * ($Width - $filled)) + ']'
}

function Add-HistoryPoint($List, $Value, [int]$Limit = 36) {
    if ($Value -lt 0) { return }
    [void]$List.Add([double](Limit-Percent $Value))
    while ($List.Count -gt $Limit) { $List.RemoveAt(0) }
}

function Format-History($List, [int]$Width = 36) {
    $chars = ' .:-=+*#%@'
    if ($null -eq $List -or $List.Count -eq 0) { return (' ' * $Width) }
    $start = [Math]::Max(0, $List.Count - $Width)
    $text = ''
    for ($i = $start; $i -lt $List.Count; $i++) {
        $value = Limit-Percent $List[$i]
        $index = [int][Math]::Round(($value / 100.0) * ($chars.Length - 1))
        if ($index -lt 0) { $index = 0 }
        if ($index -ge $chars.Length) { $index = $chars.Length - 1 }
        $text += [string]$chars[$index]
    }
    if ($text.Length -lt $Width) { $text = (' ' * ($Width - $text.Length)) + $text }
    return $text
}

function Add-RecentEvent($Message) {
    $clean = (([string]$Message) -replace '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($clean)) { return }
    $stamp = Get-Date -Format 'HH:mm:ss'
    [void]$script:recentEvents.Add("$stamp  $clean")
    while ($script:recentEvents.Count -gt 8) { $script:recentEvents.RemoveAt(0) }
}

function Append-DebugLine($Line, [switch]$StdErr) {
    $text = [string]$Line
    if ($StdErr) { $text = '[STDERR] ' + $text }
    if (-not [string]::IsNullOrWhiteSpace($script:debugPath)) {
        [System.IO.File]::AppendAllText($script:debugPath, $text + [Environment]::NewLine, $Utf8NoBom)
    } else {
        [void]$script:debugBuffer.Add($text)
    }
}

function Set-DebugRoot($Root) {
    if (-not [string]::IsNullOrWhiteSpace($script:debugPath)) { return }
    if ([string]::IsNullOrWhiteSpace([string]$Root)) { return }
    $script:outputRoot = [string]$Root
    New-Item -ItemType Directory -Force -Path $script:outputRoot | Out-Null
    $script:debugPath = Join-Path $script:outputRoot 'debug.log'
    if (Test-Path $script:debugPath) { Remove-Item -Force $script:debugPath }
    if ($script:debugBuffer.Count -gt 0) {
        [System.IO.File]::WriteAllLines($script:debugPath, [string[]]$script:debugBuffer, $Utf8NoBom)
        $script:debugBuffer.Clear()
    } else {
        [System.IO.File]::WriteAllText($script:debugPath, '', $Utf8NoBom)
    }
}

function New-WorkerState($Slot, $Attempt) {
    $slotNumber = [int]$Slot
    $attemptText = '{0:D4}' -f ([int]$Attempt)
    $state = [pscustomobject]@{
        Slot = $slotNumber
        Attempt = $attemptText
        Stage = 'STARTING'
        Current = 0
        Total = 0
        Detail = 'worker launching'
        Title = ''
        Theme = ''
        Palette = ''
        Encoder = ''
        Started = Get-Date
        LastUpdate = Get-Date
        Active = $true
        Failed = $false
        Approved = $false
    }
    $script:workersByAttempt[$attemptText] = $state
    $script:slotStates[[string]$slotNumber] = 'active'
    $script:lastStartedAttempt = $attemptText
    if ([int]$Attempt -gt $script:attempts) { $script:attempts = [int]$Attempt }
    return $state
}

function Get-OrCreateWorker($Slot, $Attempt) {
    $attemptText = '{0:D4}' -f ([int]$Attempt)
    if ($script:workersByAttempt.ContainsKey($attemptText)) { return $script:workersByAttempt[$attemptText] }
    return New-WorkerState $Slot $Attempt
}

function Set-WorkerStage($Worker, $Stage, $Current, $Total, $Detail) {
    if ($null -eq $Worker) { return }
    $Worker.Stage = [string]$Stage
    if ($null -ne $Current) { $Worker.Current = [int]$Current }
    if ($null -ne $Total) { $Worker.Total = [int]$Total }
    if ($null -ne $Detail) { $Worker.Detail = [string]$Detail }
    $Worker.LastUpdate = Get-Date
}

function Get-ItemNumberFromPath($Message, $Extension) {
    $pattern = '\\(?<index>\d+)aithread\.' + [Regex]::Escape($Extension)
    if ([string]$Message -match $pattern) { return ([int]$Matches.index) + 1 }
    return -1
}

function Update-WorkerFromMessage($Worker, $Message) {
    $messageText = [string]$Message
    if ($messageText -match '^P0 hidden-prompt generation attempt (?<current>\d+)/(?<total>\d+)') {
        Set-WorkerStage $Worker 'OLLAMA' ([int]$Matches.current) ([int]$Matches.total) 'generating script'
        $script:ollamaState = "W$('{0:D3}' -f $Worker.Slot)/A$($Worker.Attempt) generating"
        return
    }
    if ($messageText -match '^P0 pre-render novelty score: (?<score>\d+)/100') {
        Set-WorkerStage $Worker 'NOVELTY' 1 1 ("score " + $Matches.score + '/100')
        return
    }
    if ($messageText -match '^P0 semantic similarity: (?<score>\d+)%') {
        Set-WorkerStage $Worker 'NOVELTY' 1 1 ("semantic " + $Matches.score + '%')
        return
    }
    if ($messageText -match '^P0 format:\s*(?<format>[^\s]+)') {
        Set-WorkerStage $Worker 'SCRIPT READY' 1 1 $Matches.format
        $script:ollamaState = 'serialized / gate released'
        return
    }
    if ($messageText -match '^AI profile pool enabled:') {
        Set-WorkerStage $Worker 'PROFILES' 1 1 'profiles assigned'
        return
    }
    if ($messageText -match '^Phase 1/4:') {
        Set-WorkerStage $Worker 'IMAGES' 0 $Count 'rendering social frames'
        return
    }
    if ($messageText -match '^Generated image:') {
        $item = Get-ItemNumberFromPath $messageText 'png'
        if ($item -lt 0) { $item = [Math]::Min($Count, $Worker.Current + 1) }
        Set-WorkerStage $Worker 'IMAGES' $item $Count ("frame $item/$Count")
        return
    }
    if ($messageText -match '^Phase 2/4:') {
        Set-WorkerStage $Worker 'TTS' 0 $Count 'Kokoro narration'
        return
    }
    if ($messageText -match '^Starting Kokoro TTS:') {
        $item = Get-ItemNumberFromPath $messageText 'wav'
        if ($item -gt 0) { Set-WorkerStage $Worker 'TTS' ([Math]::Max(0, $item - 1)) $Count ("speaking $item/$Count") }
        return
    }
    if ($messageText -match '^Generated audio:') {
        $item = Get-ItemNumberFromPath $messageText 'wav'
        if ($item -lt 0) { $item = [Math]::Min($Count, $Worker.Current + 1) }
        Set-WorkerStage $Worker 'TTS' $item $Count ("audio $item/$Count")
        return
    }
    if ($messageText -match '^P0 integrity:') {
        Set-WorkerStage $Worker 'VALIDATE' 0 1 'checking rendered frames'
        return
    }
    if ($messageText -match '^P0/P1 video:') {
        Set-WorkerStage $Worker 'VIDEO' 0 $Count 'rendering MP4 segments'
        return
    }
    if ($messageText -match '^P0/P1 video encoder:\s*(?<encoder>.+)$') {
        $Worker.Encoder = $Matches.encoder.Trim()
        $script:encoder = $Worker.Encoder
        Set-WorkerStage $Worker 'VIDEO' $Worker.Current $Count $Worker.Encoder
        return
    }
    if ($messageText -match '^Generated timed-state clip:') {
        $item = Get-ItemNumberFromPath $messageText 'mp4'
        if ($item -lt 0) { $item = [Math]::Min($Count, $Worker.Current + 1) }
        Set-WorkerStage $Worker 'VIDEO' $item $Count ("clip $item/$Count")
        return
    }
    if ($messageText -match '^Generated format-specific final video:') {
        Set-WorkerStage $Worker 'FINALIZE' 0 1 'final MP4 rendered'
        return
    }
    if ($messageText -match '^P1 provenance manifest:' -or $messageText -match '^P0 novelty: accepted') {
        Set-WorkerStage $Worker 'FINALIZE' 1 1 'metadata/history'
        return
    }
    if ($messageText -match '^P0 pipeline complete') {
        Set-WorkerStage $Worker 'P2 AUDIT' 0 1 'publish audit'
        return
    }
    if ($messageText -match '^P2 ') {
        $detail = 'publish audit'
        if ($messageText -match '^P2 publish audit: (?<result>.+)$') { $detail = $Matches.result.Trim() }
        $done = 0
        if ($messageText -match 'approved history recorded') { $done = 1 }
        Set-WorkerStage $Worker 'P2 AUDIT' $done 1 $detail
        return
    }
    if ($messageText -match '^\[worker\] java exit code=(?<code>\d+)') {
        $code = [int]$Matches.code
        if ($code -eq 0) {
            Set-WorkerStage $Worker 'SAVE' 0 1 'awaiting master approval copy'
        } else {
            $Worker.Failed = $true
            Set-WorkerStage $Worker 'FAILED' 0 1 ("java exit $code")
        }
        return
    }
    if ($messageText -match '^\[worker\] launcher failure:\s*(?<reason>.+)$') {
        $Worker.Failed = $true
        Set-WorkerStage $Worker 'FAILED' 0 1 $Matches.reason.Trim()
        Add-RecentEvent ("slot $('{0:D3}' -f $Worker.Slot) attempt $($Worker.Attempt) failed: $($Matches.reason.Trim())")
        return
    }
    if ($messageText -match '^ThreadGens P2 failed:') {
        $Worker.Failed = $true
        Set-WorkerStage $Worker 'FAILED' 0 1 (($messageText -replace '^ThreadGens P2 failed:\s*', '').Trim())
        return
    }
}

function Process-EngineLine($Line, [switch]$StdErr) {
    Append-DebugLine $Line -StdErr:$StdErr
    $text = [string]$Line
    if ([string]::IsNullOrWhiteSpace($text)) { return }

    if ($text -match '^Output root:\s*(?<path>.+)$') {
        Set-DebugRoot $Matches.path.Trim()
        $script:masterStage = 'RUNNING'
        return
    }
    if ($text -match '^Ollama request gate:') {
        $script:ollamaState = 'serialized / ready'
        return
    }
    if ($text -match '^== Building Java files ==') {
        $script:masterStage = 'BUILDING JAVA'
        return
    }
    if ($text -match '^== \[slot (?<slot>\d{3})/\d+ \| attempt (?<attempt>\d{4}) \| active \d+/\d+\] .* worker started ==$') {
        $worker = New-WorkerState ([int]$Matches.slot) ([int]$Matches.attempt)
        Set-WorkerStage $worker 'STARTING' 0 0 'worker launched'
        Add-RecentEvent ("slot $($Matches.slot) started as attempt $($Matches.attempt)")
        return
    }
    if ($text -match '^\[W(?<slot>\d{3}) A(?<attempt>\d{4})\]\s*(?<message>.*)$') {
        $worker = Get-OrCreateWorker ([int]$Matches.slot) ([int]$Matches.attempt)
        Update-WorkerFromMessage $worker $Matches.message
        return
    }
    if (-not [string]::IsNullOrWhiteSpace($script:lastStartedAttempt) -and $script:workersByAttempt.ContainsKey($script:lastStartedAttempt)) {
        $last = $script:workersByAttempt[$script:lastStartedAttempt]
        if ($text -match '^Reddit title:\s*(?<title>.+)$') { $last.Title = $Matches.title.Trim(); return }
        if ($text -match '^X style:\s*(?<title>.+)$') { $last.Title = $Matches.title.Trim(); return }
        if ($text -match '^Idea family:\s*(?<theme>.+)$') { $last.Theme = $Matches.theme.Trim(); return }
        if ($text -match '^Background palette:\s*(?<palette>.+)$') { $last.Palette = $Matches.palette.Trim(); return }
    }
    if ($text -match '^Worker A(?<attempt>\d{4}) finished its serialized Ollama generation') {
        if ($script:workersByAttempt.ContainsKey($Matches.attempt)) {
            $worker = $script:workersByAttempt[$Matches.attempt]
            if ($worker.Stage -eq 'SCRIPT READY' -or $worker.Stage -eq 'NOVELTY' -or $worker.Stage -eq 'OLLAMA') {
                Set-WorkerStage $worker 'RENDER QUEUE' 0 0 'Ollama released; media starting'
            }
        }
        $script:ollamaState = 'serialized / gate released'
        return
    }
    if ($text -match '^Approved slot (?<slot>\d{3})\. Progress (?<approved>\d+)/(?<target>\d+)\.') {
        $slotNumber = [int]$Matches.slot
        $script:approved = [int]$Matches.approved
        $script:slotStates[[string]$slotNumber] = 'approved'
        $candidate = @($script:workersByAttempt.Values | Where-Object { $_.Slot -eq $slotNumber -and $_.Active } | Sort-Object Started -Descending | Select-Object -First 1)
        if ($candidate.Count -gt 0) {
            $candidate[0].Approved = $true
            $candidate[0].Active = $false
            Set-WorkerStage $candidate[0] 'APPROVED' 1 1 'saved to final_videos'
        }
        Add-RecentEvent ("APPROVED slot $($Matches.slot) - overall $($Matches.approved)/$($Matches.target)")
        return
    }
    if ($text -match '^Attempt (?<attempt>\d{4}) did not fill slot (?<slot>\d{3}):\s*(?<reason>.+)$') {
        $script:rejected++
        $slotNumber = [int]$Matches.slot
        $script:slotStates[[string]$slotNumber] = 'retry'
        if ($script:workersByAttempt.ContainsKey($Matches.attempt)) {
            $worker = $script:workersByAttempt[$Matches.attempt]
            $worker.Failed = $true
            $worker.Active = $false
            Set-WorkerStage $worker 'FAILED' 0 1 $Matches.reason.Trim()
        }
        Add-RecentEvent ("FAILED attempt $($Matches.attempt) for slot $($Matches.slot); replacement queued")
        return
    }
    if ($text -match '^Attempt (?<attempt>\d{4}) exhausted all .* slot (?<slot>\d{3}):\s*(?<reason>.+)$') {
        $script:rejected++
        $script:slotStates[[string]([int]$Matches.slot)] = 'retry'
        Add-RecentEvent ("SEED FAILED attempt $($Matches.attempt) for slot $($Matches.slot); retrying")
        return
    }
    if ($text -match '^Idea generation try .* failed') {
        Add-RecentEvent $text
        return
    }
    if ($text -match '^== Batch complete ==') {
        $script:masterStage = 'COMPLETE'
        return
    }
    if ($StdErr -and -not [string]::IsNullOrWhiteSpace($text)) {
        Add-RecentEvent ("stderr: " + $text)
    }
}

function Get-SystemSnapshot {
    $snapshot = [ordered]@{
        Cpu = -1.0
        RamPercent = -1.0
        RamUsedGb = -1.0
        RamTotalGb = -1.0
        Gpu = -1.0
        Encoder = -1.0
        VramUsedMb = -1.0
        VramTotalMb = -1.0
        Temp = -1.0
    }
    if ($env:OS -eq 'Windows_NT') {
        try {
            $cpuRows = @(Get-CimInstance Win32_Processor -ErrorAction Stop)
            if ($cpuRows.Count -gt 0) {
                $snapshot.Cpu = [double](($cpuRows | Measure-Object -Property LoadPercentage -Average).Average)
            }
        } catch { }
        try {
            $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
            $totalKb = [double]$os.TotalVisibleMemorySize
            $freeKb = [double]$os.FreePhysicalMemory
            if ($totalKb -gt 0) {
                $usedKb = $totalKb - $freeKb
                $snapshot.RamTotalGb = $totalKb / 1MB
                $snapshot.RamUsedGb = $usedKb / 1MB
                $snapshot.RamPercent = ($usedKb / $totalKb) * 100.0
            }
        } catch { }
    }
    $nvidia = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    if ($null -ne $nvidia) {
        $line = $null
        try {
            $line = & $nvidia.Source '--query-gpu=utilization.gpu,utilization.encoder,memory.used,memory.total,temperature.gpu' '--format=csv,noheader,nounits' 2>$null | Select-Object -First 1
        } catch { }
        if ([string]::IsNullOrWhiteSpace([string]$line)) {
            try {
                $line = & $nvidia.Source '--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu' '--format=csv,noheader,nounits' 2>$null | Select-Object -First 1
                if (-not [string]::IsNullOrWhiteSpace([string]$line)) {
                    $parts = @($line -split ',')
                    if ($parts.Count -ge 4) {
                        $snapshot.Gpu = [double]$parts[0].Trim()
                        $snapshot.VramUsedMb = [double]$parts[1].Trim()
                        $snapshot.VramTotalMb = [double]$parts[2].Trim()
                        $snapshot.Temp = [double]$parts[3].Trim()
                    }
                }
            } catch { }
        } else {
            try {
                $parts = @($line -split ',')
                if ($parts.Count -ge 5) {
                    $snapshot.Gpu = [double]$parts[0].Trim()
                    $encoderText = $parts[1].Trim()
                    if ($encoderText -match '^\d+(\.\d+)?$') { $snapshot.Encoder = [double]$encoderText }
                    $snapshot.VramUsedMb = [double]$parts[2].Trim()
                    $snapshot.VramTotalMb = [double]$parts[3].Trim()
                    $snapshot.Temp = [double]$parts[4].Trim()
                }
            } catch { }
        }
    }
    return [pscustomobject]$snapshot
}

function Update-SystemSnapshot {
    if (((Get-Date) - $script:lastSystemSampleAt).TotalSeconds -lt 2 -and $null -ne $script:lastSystemSample) { return }
    $script:lastSystemSample = Get-SystemSnapshot
    $script:lastSystemSampleAt = Get-Date
    Add-HistoryPoint $script:cpuHistory $script:lastSystemSample.Cpu
    Add-HistoryPoint $script:gpuHistory $script:lastSystemSample.Gpu
}

function Get-StageProgressText($Worker) {
    if ($Worker.Total -gt 0) {
        $percent = 0
        if ($Worker.Total -gt 0) { $percent = ([double]$Worker.Current / [double]$Worker.Total) * 100.0 }
        return (Format-Bar $percent 10) + (' {0,2}/{1,-2}' -f $Worker.Current, $Worker.Total)
    }
    $phase = [int](((Get-Date) - $Worker.LastUpdate).TotalSeconds) % 10
    return '[' + ('>' * ($phase + 1)).PadRight(10, '-') + ']  --  '
}

function Get-SlotMap {
    if ($TargetVideos -gt 60) {
        $activeCount = @($script:slotStates.Values | Where-Object { $_ -eq 'active' }).Count
        $retryCount = @($script:slotStates.Values | Where-Object { $_ -eq 'retry' }).Count
        return "Slots: approved=$script:approved active=$activeCount retry=$retryCount waiting=$([Math]::Max(0, $TargetVideos - $script:approved - $activeCount - $retryCount))"
    }
    $chars = ''
    for ($i = 1; $i -le $TargetVideos; $i++) {
        $state = [string]$script:slotStates[[string]$i]
        switch ($state) {
            'approved' { $chars += '#' }
            'active' { $chars += '>' }
            'retry' { $chars += '!' }
            default { $chars += '.' }
        }
    }
    return "Slots: [$chars]  # approved  > active  ! replacement  . waiting"
}

function Truncate-Line($Value, [int]$Width) {
    $text = [string]$Value
    if ($Width -lt 1) { return '' }
    if ($text.Length -le $Width) { return $text }
    if ($Width -le 3) { return $text.Substring(0, $Width) }
    return $text.Substring(0, $Width - 3) + '...'
}

function Render-Dashboard([switch]$Final) {
    Update-SystemSnapshot
    $width = 120
    $height = 30
    try { $width = [Math]::Max(40, [Console]::WindowWidth - 1) } catch { }
    try { $height = [Math]::Max(20, [Console]::WindowHeight - 1) } catch { }

    $elapsed = (Get-Date) - $script:runStarted
    $overallPercent = ([double]$script:approved / [double][Math]::Max(1, $TargetVideos)) * 100.0
    $activeWorkers = @($script:workersByAttempt.Values | Where-Object { $_.Active } | Sort-Object Slot, Started)
    $pending = [Math]::Max(0, $TargetVideos - $script:approved - $activeWorkers.Count)
    $rate = 0.0
    $etaText = '--'
    if ($elapsed.TotalMinutes -gt 0.05 -and $script:approved -gt 0) {
        $rate = $script:approved / $elapsed.TotalMinutes
        if ($rate -gt 0) {
            $etaMinutes = ($TargetVideos - $script:approved) / $rate
            $etaText = Format-Duration ([TimeSpan]::FromMinutes([Math]::Max(0, $etaMinutes)))
        }
    }

    $lines = New-Object System.Collections.ArrayList
    $statusWord = if ($Final) { 'FINAL' } else { $script:masterStage }
    [void]$lines.Add("ThreadGens LIVE BATCH MONITOR  |  $statusWord  |  runtime $(Format-Duration $elapsed)")
    [void]$lines.Add((Format-Bar $overallPercent 24) + ("  APPROVED {0}/{1} ({2,3:N0}%)  active {3}/{4}  pending {5}  attempts {6}  rejected {7}  rate {8:N2}/min  ETA {9}" -f $script:approved, $TargetVideos, $overallPercent, $activeWorkers.Count, $Workers, $pending, $script:attempts, $script:rejected, $rate, $etaText))
    [void]$lines.Add((Get-SlotMap))

    $sys = $script:lastSystemSample
    if ($null -eq $sys) { $sys = [pscustomobject]@{Cpu=-1;RamPercent=-1;RamUsedGb=-1;RamTotalGb=-1;Gpu=-1;Encoder=-1;VramUsedMb=-1;VramTotalMb=-1;Temp=-1} }
    $cpuText = if ($sys.Cpu -ge 0) { '{0,3:N0}%' -f $sys.Cpu } else { ' n/a' }
    $ramText = if ($sys.RamPercent -ge 0) { '{0,3:N0}% {1:N1}/{2:N1} GB' -f $sys.RamPercent, $sys.RamUsedGb, $sys.RamTotalGb } else { 'n/a' }
    [void]$lines.Add("CPU $(Format-Bar $sys.Cpu 16) $cpuText  history $(Format-History $script:cpuHistory 28)   RAM $(Format-Bar $sys.RamPercent 12) $ramText")
    if ($sys.Gpu -ge 0) {
        $nvencText = if ($sys.Encoder -ge 0) { ('{0,3:N0}%' -f $sys.Encoder) } else { ' n/a' }
        $vramText = if ($sys.VramTotalMb -gt 0) { ('{0:N1}/{1:N1} GB' -f ($sys.VramUsedMb / 1024.0), ($sys.VramTotalMb / 1024.0)) } else { 'n/a' }
        [void]$lines.Add("GPU $(Format-Bar $sys.Gpu 16) $('{0,3:N0}%' -f $sys.Gpu)  history $(Format-History $script:gpuHistory 28)   NVENC $nvencText  VRAM $vramText  TEMP $('{0:N0}C' -f $sys.Temp)")
    } else {
        [void]$lines.Add("GPU [not available from nvidia-smi]   encoder: $script:encoder")
    }
    [void]$lines.Add("Ollama gate: 1 serialized request  |  $script:ollamaState  |  video encoder: $script:encoder")
    [void]$lines.Add(('-' * [Math]::Min($width, 120)))
    [void]$lines.Add('SLOT ATT   STAGE        STAGE PROGRESS       ELAPSED  TITLE / CURRENT DETAIL')

    foreach ($worker in $activeWorkers) {
        $progress = Get-StageProgressText $worker
        $workerElapsed = Format-Duration ((Get-Date) - $worker.Started)
        $title = if ([string]::IsNullOrWhiteSpace($worker.Title)) { $worker.Detail } else { $worker.Title + ' | ' + $worker.Detail }
        $row = ('{0:D3}  {1}  {2,-12} {3,-18} {4,7}  {5}' -f $worker.Slot, $worker.Attempt, (Truncate-Line $worker.Stage 12), $progress, $workerElapsed, $title)
        [void]$lines.Add($row)
    }
    if ($activeWorkers.Count -eq 0) { [void]$lines.Add('(no active video workers)') }

    [void]$lines.Add(('-' * [Math]::Min($width, 120)))
    [void]$lines.Add('RECENT EVENTS')
    $eventBudget = [Math]::Max(2, [Math]::Min(5, $height - $lines.Count - 2))
    $events = @($script:recentEvents | Select-Object -Last $eventBudget)
    if ($events.Count -eq 0) { [void]$lines.Add('  waiting for first worker event...') }
    foreach ($event in $events) { [void]$lines.Add('  ' + $event) }
    $debugDisplay = if ([string]::IsNullOrWhiteSpace($script:debugPath)) { 'waiting for output root...' } else { $script:debugPath }
    [void]$lines.Add("DEBUG LOG: $debugDisplay")

    while ($lines.Count -gt $height) { $lines.RemoveAt($lines.Count - 2) }

    $firstDraw = $script:lastDashboardLineCount -eq 0
    if ($firstDraw) {
        try { Clear-Host } catch { }
    } else {
        try { [Console]::SetCursorPosition(0, 0) } catch { try { Clear-Host } catch { } }
    }
    $renderCount = [Math]::Max($lines.Count, $script:lastDashboardLineCount)
    for ($i = 0; $i -lt $renderCount; $i++) {
        $line = if ($i -lt $lines.Count) { [string]$lines[$i] } else { '' }
        $line = Truncate-Line $line $width
        try { [Console]::WriteLine($line.PadRight($width)) } catch { Write-Host $line }
    }
    $script:lastDashboardLineCount = $lines.Count
}

function Quote-NativeArgument($Value) {
    $text = [string]$Value
    if ($text -notmatch '[\s"]') { return $text }
    return '"' + ($text -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Build-EngineArgumentLine {
    $tokens = New-Object System.Collections.ArrayList
    foreach ($token in @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $EngineScript,
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
        '-MaxAttempts', [string]$MaxAttempts)) { [void]$tokens.Add($token) }
    if (-not [string]::IsNullOrWhiteSpace($SeriesId)) { [void]$tokens.Add('-SeriesId'); [void]$tokens.Add($SeriesId) }
    if ($KeepOllamaLoaded) { [void]$tokens.Add('-KeepOllamaLoaded') }
    if ($GenerateOpImage) { [void]$tokens.Add('-GenerateOpImage') }
    if ($StopOnError) { [void]$tokens.Add('-StopOnError') }
    return (@($tokens | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
}

function Run-DashboardSelfTest {
    $script:workersByAttempt = @{}
    $script:slotStates = @{'1'='waiting'}
    $worker = New-WorkerState 1 1
    Update-WorkerFromMessage $worker 'P0 hidden-prompt generation attempt 1/5 using format thread_story'
    if ($worker.Stage -ne 'OLLAMA' -or $worker.Total -ne 5) { throw 'Dashboard parser did not recognize Ollama generation.' }
    Update-WorkerFromMessage $worker 'Phase 1/4: rendering all images without synthetic engagement...'
    Update-WorkerFromMessage $worker 'Generated image: C:\temp\3aithread.png'
    if ($worker.Stage -ne 'IMAGES' -or $worker.Current -ne 4 -or $worker.Total -ne $Count) { throw 'Dashboard parser did not track image progress.' }
    Update-WorkerFromMessage $worker 'Phase 2/4: generating all audio with kokoro...'
    Update-WorkerFromMessage $worker 'Generated audio: C:\temp\7aithread.wav'
    if ($worker.Stage -ne 'TTS' -or $worker.Current -ne 8) { throw 'Dashboard parser did not track TTS progress.' }
    Update-WorkerFromMessage $worker 'P0/P1 video: building caption-aligned multi-state thread_story compositions...'
    Update-WorkerFromMessage $worker 'Generated timed-state clip: C:\temp\4aithread.mp4 [states=3, captions=off]'
    if ($worker.Stage -ne 'VIDEO' -or $worker.Current -ne 5) { throw 'Dashboard parser did not track video progress.' }
    Update-WorkerFromMessage $worker 'P2 publish audit: approved history recorded as WARN.'
    if ($worker.Stage -ne 'P2 AUDIT' -or $worker.Current -ne 1) { throw 'Dashboard parser did not recognize P2 completion.' }
    Write-Host 'Live dashboard parser self-test passed.' -ForegroundColor Green
}

if ($SelfTest) {
    Run-DashboardSelfTest
    if (-not (Test-Path $EngineScript)) { throw "Parallel batch engine not found: $EngineScript" }
    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
    & $powerShellExe -NoProfile -ExecutionPolicy Bypass -File $EngineScript -SelfTest
    if ($LASTEXITCODE -ne 0) { throw "Parallel batch engine self-test failed with exit code $LASTEXITCODE." }
    Write-Host 'Live dashboard + parallel engine self-test passed.' -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $EngineScript)) { throw "Parallel batch engine not found: $EngineScript" }

$monitorTemp = Join-Path ([System.IO.Path]::GetTempPath()) ('threadgens-dashboard-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $monitorTemp | Out-Null
$rawStdout = Join-Path $monitorTemp 'engine.stdout.log'
$rawStderr = Join-Path $monitorTemp 'engine.stderr.log'
$powerShellExe = Join-Path $PSHOME 'powershell.exe'
if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
$argumentLine = Build-EngineArgumentLine

Append-DebugLine 'ThreadGens live dashboard wrapper'
Append-DebugLine ("Started: " + $script:runStarted.ToString('o'))
Append-DebugLine ("Dashboard settings: target=$TargetVideos count=$Count workers=$Workers platform=$Platform format=$Format formatSelection=$FormatSelection formatSeries=$FormatSeries formatVariant=$FormatVariant model=$Model voice=$Voice voiceSelection=$VoiceSelection captions=$Captions encoder=$env:THREADGENS_VIDEO_ENCODER")
Append-DebugLine ("Engine: $EngineScript")

$process = $null
$stdoutStream = $null
$stderrStream = $null
$stdoutReader = $null
$stderrReader = $null
$exitCode = 1

try {
    $process = Start-Process -FilePath $powerShellExe -ArgumentList $argumentLine -PassThru -WindowStyle Hidden -RedirectStandardOutput $rawStdout -RedirectStandardError $rawStderr
    $deadline = (Get-Date).AddSeconds(10)
    while ((-not (Test-Path $rawStdout) -or -not (Test-Path $rawStderr)) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 50 }
    if (-not (Test-Path $rawStdout)) { New-Item -ItemType File -Force -Path $rawStdout | Out-Null }
    if (-not (Test-Path $rawStderr)) { New-Item -ItemType File -Force -Path $rawStderr | Out-Null }
    $stdoutStream = New-Object System.IO.FileStream($rawStdout, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $stderrStream = New-Object System.IO.FileStream($rawStderr, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $stdoutReader = New-Object System.IO.StreamReader($stdoutStream, [System.Text.Encoding]::UTF8, $true)
    $stderrReader = New-Object System.IO.StreamReader($stderrStream, [System.Text.Encoding]::UTF8, $true)

    Add-RecentEvent 'parallel engine started; verbose output redirected to debug.log'
    while (-not $process.HasExited) {
        while ($null -ne ($line = $stdoutReader.ReadLine())) { Process-EngineLine $line }
        while ($null -ne ($line = $stderrReader.ReadLine())) { Process-EngineLine $line -StdErr }
        Render-Dashboard
        Start-Sleep -Milliseconds 500
        try { $process.Refresh() } catch { }
    }
    $process.WaitForExit()
    while ($null -ne ($line = $stdoutReader.ReadLine())) { Process-EngineLine $line }
    while ($null -ne ($line = $stderrReader.ReadLine())) { Process-EngineLine $line -StdErr }
    $exitCode = $process.ExitCode
    if ([string]::IsNullOrWhiteSpace($script:debugPath)) {
        $fallbackRoot = Join-Path $RepoRoot ('output\batch_videos\dashboard_failed_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
        Set-DebugRoot $fallbackRoot
    }
    Append-DebugLine ("Dashboard wrapper observed engine exit code: $exitCode")
    $script:masterStage = if ($exitCode -eq 0) { 'COMPLETE' } else { "STOPPED ($exitCode)" }
    Render-Dashboard -Final
} catch {
    if ([string]::IsNullOrWhiteSpace($script:debugPath)) {
        $fallbackRoot = Join-Path $RepoRoot ('output\batch_videos\dashboard_failed_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
        Set-DebugRoot $fallbackRoot
    }
    Append-DebugLine ("Dashboard wrapper failure: " + $_.Exception.Message) -StdErr
    Add-RecentEvent ("DASHBOARD ERROR: " + $_.Exception.Message)
    $script:masterStage = 'DASHBOARD ERROR'
    Render-Dashboard -Final
    throw
} finally {
    if ($null -ne $stdoutReader) { $stdoutReader.Dispose() }
    if ($null -ne $stderrReader) { $stderrReader.Dispose() }
    if ($null -ne $stdoutStream) { $stdoutStream.Dispose() }
    if ($null -ne $stderrStream) { $stderrStream.Dispose() }
    try { Remove-Item -Recurse -Force -Path $monitorTemp -ErrorAction SilentlyContinue } catch { }
}

exit $exitCode
