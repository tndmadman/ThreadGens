$script:ThreadGensContentFormats = @(
    'thread_story',
    'confession',
    'debate',
    'best_answers',
    'escalating_conversation'
)

function Resolve-BatchFormatPool([string]$Series) {
    $pool = New-Object System.Collections.ArrayList
    foreach ($item in @($Series -split '[,;]')) {
        $format = ([string]$item).Trim().ToLowerInvariant() -replace '[- ]', '_'
        if ([string]::IsNullOrWhiteSpace($format)) { continue }
        if ($format -notin $script:ThreadGensContentFormats) {
            throw "Unsupported format in FormatSeries: $item"
        }
        if ($format -notin $pool) { [void]$pool.Add($format) }
    }
    if ($pool.Count -eq 0) { throw 'FormatSeries must contain at least one concrete format.' }
    return @($pool)
}

function Get-BatchFormatOffset($Pool, [string]$PublishHistoryPath) {
    if (-not (Test-Path -LiteralPath $PublishHistoryPath)) { return 0 }
    $formats = New-Object System.Collections.ArrayList
    $lineNumber = 0
    foreach ($rawLine in Get-Content -LiteralPath $PublishHistoryPath -Encoding UTF8) {
        $lineNumber++
        $line = ([string]$rawLine).Trim()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $entry = $line | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "Publish history is malformed at line $lineNumber in $PublishHistoryPath." }
        $format = ([string]$entry.format).Trim().ToLowerInvariant()
        if ($format -in $Pool) { [void]$formats.Add($format) }
    }
    if ($formats.Count -eq 0) { return 0 }

    $recent = @($formats | Select-Object -Last 8)
    [array]::Reverse($recent)
    $immediatelyPrevious = [string]$recent[0]
    for ($index = 0; $index -lt $Pool.Count; $index++) {
        $candidate = [string]$Pool[$index]
        $recentCount = @($recent | Where-Object { $_ -eq $candidate }).Count
        if ($candidate -ne $immediatelyPrevious -and $recentCount -lt 2) { return $index }
    }
    $previousIndex = [array]::IndexOf([object[]]$Pool, $immediatelyPrevious)
    return (($previousIndex + 1) % $Pool.Count)
}

function Select-BatchFormat(
    [string]$Selection,
    [string]$RequestedFormat,
    $Pool,
    [int]$Slot,
    [int]$Attempt,
    [int]$Offset = 0
) {
    switch ($Selection) {
        'auto' { return 'auto' }
        'single' { return $RequestedFormat }
        'series' { $sequenceNumber = [Math]::Max(1, $Attempt) }
        'per-slot' { $sequenceNumber = [Math]::Max(1, $Slot) }
        default { throw "Unsupported FormatSelection: $Selection" }
    }
    $index = (($sequenceNumber - 1 + $Offset) % $Pool.Count)
    return [string]$Pool[$index]
}

function Get-BatchSeriesId(
    [string]$VoiceSelection,
    [string]$ConfiguredSeriesId,
    [string]$Platform,
    [int]$Slot
) {
    if (-not [string]::IsNullOrWhiteSpace($ConfiguredSeriesId)) { return $ConfiguredSeriesId.Trim() }
    if ($VoiceSelection -ne 'series') { return '' }
    return ('threadgens-{0}-slot-{1:D4}' -f $Platform.ToLowerInvariant(), [Math]::Max(1, $Slot))
}

function Test-BatchFormatRotation {
    $pool = @(Resolve-BatchFormatPool 'thread_story,confession,debate,best_answers,escalating_conversation')
    $cycle = @(1..5 | ForEach-Object { Select-BatchFormat 'per-slot' 'auto' $pool $_ 99 0 })
    if (@($cycle | Select-Object -Unique).Count -ne 5) {
        throw 'Per-slot format rotation did not cover all five formats before repeating.'
    }
    $firstAttempt = Select-BatchFormat 'per-slot' 'auto' $pool 3 1 0
    $replacement = Select-BatchFormat 'per-slot' 'auto' $pool 3 500 0
    if ($firstAttempt -ne $replacement) {
        throw 'Replacement attempts did not preserve the target slot format.'
    }
    if ((Select-BatchFormat 'series' 'auto' $pool 1 1 0) -eq
        (Select-BatchFormat 'series' 'auto' $pool 1 2 0)) {
        throw 'Attempt-series format rotation did not advance between attempts.'
    }
    if ((Get-BatchSeriesId 'series' '' 'reddit' 1) -eq (Get-BatchSeriesId 'series' '' 'reddit' 2)) {
        throw 'Automatic per-video voice series IDs did not change between target slots.'
    }
    if ((Get-BatchSeriesId 'series' 'named-series' 'reddit' 2) -ne 'named-series') {
        throw 'An explicit voice series ID must remain stable across target slots.'
    }
}
