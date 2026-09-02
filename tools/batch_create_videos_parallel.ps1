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
. (Join-Path $PSScriptRoot 'batch_format_rotation.ps1')

if (-not $PSBoundParameters.ContainsKey('KeepOllamaLoaded')) {
    $KeepOllamaLoaded = $true
}

$env:THREADGENS_KOKORO_VERBOSE = '0'
$env:PYTHONWARNINGS = 'ignore'
$env:HF_HUB_DISABLE_PROGRESS_BARS = '1'
$env:TOKENIZERS_PARALLELISM = 'false'

$Platform = $Platform.ToLowerInvariant()
if ($Platform -notin @('reddit', 'x')) {
    throw "Unsupported platform: $Platform. Use reddit or x."
}
if ($TargetVideos -lt 1) {
    throw 'TargetVideos must be at least 1.'
}
if ($Count -lt 1) {
    throw 'Count must be at least 1.'
}
if ($IdeaHistoryLimit -lt 1) {
    $IdeaHistoryLimit = 1
}
if ($IdeaRetries -lt 1) {
    $IdeaRetries = 1
}
if ($GenerateOpImage -and $Workers -gt 1) {
    Write-Host 'ComfyUI OP images share the GPU compute lane with Ollama; parallel whole-video workers are clamped to 1 while OP image generation is enabled.' -ForegroundColor Yellow
    $Workers = 1
}

$TtsEngine = 'kokoro'
$KokoroPython = Join-Path $RepoRoot '.venv-kokoro\Scripts\python.exe'
$WorkerScript = Join-Path $RepoRoot 'tools\batch_parallel_worker.ps1'
$ProxyScript = Join-Path $RepoRoot 'tools\ollama_serial_proxy.py'
$OutputRoot = Join-Path $RepoRoot ('output\batch_videos\' + $Platform + '_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
$FinalDir = Join-Path $OutputRoot 'final_videos'
$IdeaHistoryPath = if ([System.IO.Path]::IsPathRooted($IdeaHistoryFile)) { $IdeaHistoryFile } else { Join-Path $RepoRoot $IdeaHistoryFile }
$GlobalGenerationHistoryPath = if ([System.IO.Path]::IsPathRooted($GenerationHistoryFile)) { $GenerationHistoryFile } else { Join-Path $RepoRoot $GenerationHistoryFile }
$PublishHistoryPath = if ([System.IO.Path]::IsPathRooted($PublishHistoryFile)) { $PublishHistoryFile } else { Join-Path $RepoRoot $PublishHistoryFile }
$FormatPool = @(Resolve-BatchFormatPool $FormatSeries)
$FormatOffset = Get-BatchFormatOffset $FormatPool $PublishHistoryPath
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Palettes = @('ember', 'ocean', 'forest', 'violet', 'teal', 'rose', 'amber', 'slate')
$script:OllamaGenerateUrl = ''
$script:proxyProcess = $null
$script:activeJobs = New-Object System.Collections.ArrayList
$script:pendingSlots = New-Object System.Collections.ArrayList
$script:pendingReservations = @{}
$script:completedSlots = @{}
$script:reservedFinalNames = @{}
$script:failedAttempts = New-Object System.Collections.ArrayList
$script:succeededVideos = 0
$script:totalAttempts = 0
$script:ideaHistory = @()
$script:seenIdeaKeys = @{}
$script:targetLabel = '{0:D3}' -f $TargetVideos

function Write-Step($Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function New-SafeFileName($Value) {
    $name = ([string]$Value).ToLowerInvariant() -replace '[^a-z0-9]+', '_'
    $name = $name.Trim('_')
    if ($name.Length -gt 48) {
        $name = $name.Substring(0, 48).Trim('_')
    }
    if ([string]::IsNullOrWhiteSpace($name)) { return 'video' }
    return $name
}

function Get-AlphaSuffix([int]$Value) {
    if ($Value -lt 1) { return '' }
    $result = ''
    $n = $Value
    while ($n -gt 0) {
        $n--
        $result = ([char](97 + ($n % 26))) + $result
        $n = [Math]::Floor($n / 26)
    }
    return $result
}

function Get-UniqueFinalVideoName($SafeTitle, $Directory, [hashtable]$Reserved = $script:reservedFinalNames) {
    $candidate = "$SafeTitle.mp4"
    $key = $candidate.ToLowerInvariant()
    if (-not (Test-Path (Join-Path $Directory $candidate)) -and -not $Reserved.ContainsKey($key)) {
        return $candidate
    }
    for ($i = 1; $i -le 702; $i++) {
        $suffix = Get-AlphaSuffix $i
        $candidate = "${SafeTitle}_${suffix}.mp4"
        $key = $candidate.ToLowerInvariant()
        if (-not (Test-Path (Join-Path $Directory $candidate)) -and -not $Reserved.ContainsKey($key)) {
            return $candidate
        }
    }
    throw "Could not create a unique alphabetic filename for $SafeTitle."
}

function Normalize-IdeaText($Value) {
    if ($null -eq $Value) { return '' }
    return (([string]$Value).ToLowerInvariant() -replace '[^a-z0-9]+', ' ').Trim()
}

function Get-IdeaKey($Title, $Body, $IdeaPlatform) {
    $normalized = (Normalize-IdeaText $IdeaPlatform) + '|' + (Normalize-IdeaText $Title) + '|' + (Normalize-IdeaText $Body)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash($bytes) } finally { $sha.Dispose() }
    return -join ($hash | ForEach-Object { $_.ToString('x2') })
}

function Add-IdeaHistoryEvent($EventData, $Path = $IdeaHistoryPath) {
    $folder = Split-Path -Parent $Path
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $json = $EventData | ConvertTo-Json -Compress -Depth 8
    [System.IO.File]::AppendAllText($Path, $json + [Environment]::NewLine, $Utf8NoBom)
}

function Read-IdeaHistory($Path = $IdeaHistoryPath) {
    if (-not (Test-Path $Path)) { return @() }
    $result = @()
    $lineNumber = 0
    foreach ($rawLine in Get-Content -Path $Path -Encoding UTF8) {
        $lineNumber++
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $entry = $line | ConvertFrom-Json } catch { throw "Batch idea history is malformed at line $lineNumber in $Path." }
        if ($entry.event -eq 'generated' -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.title) -and
            -not [string]::IsNullOrWhiteSpace([string]$entry.body)) {
            $result += $entry
        }
    }
    return @($result)
}

function ConvertFrom-IdeaResponse($Text) {
    $raw = ([string]$Text).Trim()
    $firstBrace = $raw.IndexOf('{')
    $lastBrace = $raw.LastIndexOf('}')
    if ($firstBrace -lt 0 -or $lastBrace -le $firstBrace) {
        throw 'Ollama idea response did not contain a JSON object.'
    }
    $json = $raw.Substring($firstBrace, $lastBrace - $firstBrace + 1)
    try { return $json | ConvertFrom-Json } catch { throw "Ollama idea response contained invalid JSON: $json" }
}

function Get-IdeaWordCount($Value) {
    $text = (([string]$Value) -replace '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return 0 }
    return @($text -split '\s+').Count
}

function Get-IdeaShapeProblem($Title, $Body, $IdeaPlatform) {
    $safeTitle = (([string]$Title) -replace '\s+', ' ').Trim()
    $safeBody = (([string]$Body) -replace '\s+', ' ').Trim()
    $platformName = (([string]$IdeaPlatform).ToLowerInvariant()).Trim()
    if ([string]::IsNullOrWhiteSpace($safeTitle) -or [string]::IsNullOrWhiteSpace($safeBody) -or $safeTitle.Length -lt 3 -or $safeBody.Length -lt 12) {
        return 'title/body is empty or too short'
    }
    $titleWords = Get-IdeaWordCount $safeTitle
    $bodyWords = Get-IdeaWordCount $safeBody
    if ($platformName -eq 'x') {
        if ($safeTitle.Length -gt 48 -or $titleWords -gt 8) { return "X hidden reply style is too long ($($safeTitle.Length) chars/$titleWords words; max 48 chars/8 words)" }
        if ($safeBody.Length -gt 280) { return "X visible post is too long ($($safeBody.Length) chars; max 280)" }
        return $null
    }
    if ($safeTitle.Length -gt 64 -or $titleWords -gt 11) { return "Reddit title is too long ($($safeTitle.Length) chars/$titleWords words; max 64 chars/11 words)" }
    if ($safeBody.Length -gt 300 -or $bodyWords -gt 52) { return "Reddit body is too long ($($safeBody.Length) chars/$bodyWords words; max 300 chars/52 words)" }
    $longestTitleWord = @($safeTitle -split '\s+' | Sort-Object Length -Descending | Select-Object -First 1)[0]
    if ($null -ne $longestTitleWord -and ([string]$longestTitleWord).Length -gt 24) {
        return 'Reddit title contains a word longer than 24 characters, which is unsafe for fixed-width rendering'
    }
    return $null
}

function Get-RecentIdeaBlock($History) {
    if (-not $History -or $History.Count -eq 0) { return '(none yet)' }
    $start = [Math]::Max(0, $History.Count - $IdeaHistoryLimit)
    $lines = @()
    for ($i = $start; $i -lt $History.Count; $i++) {
        $title = (([string]$History[$i].title) -replace '\s+', ' ').Trim()
        $body = (([string]$History[$i].body) -replace '\s+', ' ').Trim()
        if ($body.Length -gt 180) { $body = $body.Substring(0, 177) + '...' }
        $lines += "- $title :: $body"
    }
    return ($lines -join [Environment]::NewLine)
}

function Invoke-NewBatchIdea($AttemptNumber, $History, [hashtable]$SeenKeys) {
    $themes = @(
        'space exploration and orbital engineering',
        'SpaceX rockets, launch systems, Starship, Falcon, or reusable rocketry',
        'astronomy, planets, stars, black holes, or strange space phenomena',
        'physics and counterintuitive everyday physics',
        'chemistry, materials, unusual elements, or surprising reactions',
        'nuclear science, fission, fusion, reactors, radiation, or isotope uses',
        'clean energy, power grids, solar, wind, geothermal, storage, or transmission',
        'batteries, charging, energy density, and electric vehicle engineering',
        'Tesla vehicles, manufacturing, charging, autonomy engineering, or EV design',
        'cars, engines, transmissions, aerodynamics, tires, or automotive engineering',
        'motorsports, race engineering, unusual race cars, or speed records',
        'aircraft, aviation engineering, airports, or unusual airplanes',
        'ships, submarines, ports, maritime engineering, or ocean transport',
        'trains, rail engineering, transit systems, or unusual rail technology',
        'computers, CPUs, GPUs, memory, storage, cooling, or computer architecture',
        'semiconductors, chip fabrication, lithography, or transistor technology',
        'computer programming, algorithms, compilers, software design, or debugging',
        'operating systems, retro computing, terminals, or unusual computer history',
        'computer networking, the internet, data centers, cables, or infrastructure',
        'AI, machine learning, neural networks, robotics, or computer vision',
        'cybersecurity concepts, defensive computing, encryption, or privacy engineering',
        'robotics, automation, industrial machines, or factory engineering',
        '3D printing, CNC machining, manufacturing, tooling, or clever mechanisms',
        'electronics, circuit design, sensors, radio, or embedded systems',
        'quantum science, quantum computing, or strange quantum behavior',
        'biology, evolution, genetics, cells, or unusual living systems',
        'animal intelligence, behavior, senses, migration, or survival adaptations',
        'weird animals, deep-sea creatures, insects, birds, reptiles, or microscopic life',
        'plants, fungi, forests, ecology, or surprising plant behavior',
        'ocean science, deep sea, currents, reefs, hydrothermal vents, or marine life',
        'geology, volcanoes, earthquakes, minerals, caves, or strange landforms',
        'weather, storms, lightning, atmospheric optics, or unusual climate phenomena',
        'paleontology, dinosaurs, fossils, extinct animals, or ancient ecosystems',
        'archaeology, ancient engineering, lost techniques, or historical artifacts',
        'weird true historical event or overlooked moment in history',
        'niche travel location, remote region, unusual landscape, or little-known destination',
        'geography, borders, islands, enclaves, extreme places, or map oddities',
        'architecture, megaprojects, bridges, tunnels, dams, towers, or unusual buildings',
        'cities and infrastructure without defaulting to famous megacities',
        'mines, quarries, tunnels, underground infrastructure, or large industrial sites',
        'agriculture, food science, farming technology, or unusual crops',
        'art, visual design, sculpture, photography, animation, or creative techniques',
        'music technology, acoustics, instruments, recording, or sound science',
        'everyday object with surprising engineering or hidden complexity',
        'invention history, failed inventions, accidental discoveries, or clever patents',
        'mathematics, probability, geometry, patterns, or counterintuitive statistics',
        'psychology or perception phenomenon framed carefully as general curiosity',
        'human senses, biomechanics, sleep, memory, or non-medical body science',
        'logistics, warehouses, shipping networks, routing, or supply-chain engineering',
        'space habitats, Moon or Mars engineering, life support, or off-world construction',
        'future technology grounded in plausible engineering',
        'science myth versus reality',
        'unexpected scientific fact with a concrete mechanism behind it',
        'weird natural event that really can happen',
        'strange machine, vehicle, tool, or piece of industrial equipment',
        'museum object, preserved machine, historic vehicle, or unusual artifact',
        'travel surprise or cultural curiosity from a lesser-known place',
        'pet or animal story with an unusual but believable behavior',
        'wholesome human story',
        'awkward social situation',
        'workplace problem',
        'family story',
        'neighbor conflict',
        'relationship misunderstanding',
        'customer service story',
        'money or purchase dilemma',
        'two-sided debate where both positions have a real argument',
        'confession or regret',
        'ordinary situation that becomes unexpectedly interesting rather than horror'
    )
    $lenses = @(
        'surprising fact followed by the mechanism that explains it',
        'a question that sounds simple but has a non-obvious answer',
        'engineering tradeoff: why designers choose one compromise over another',
        'compare two technologies, animals, places, machines, or approaches',
        'hidden system people use every day without noticing',
        'myth versus reality without being smug or preachy',
        'what-if scenario that stays physically or technically plausible',
        'small design detail that creates a surprisingly large consequence',
        'niche historical connection that makes the modern world make more sense',
        'visual or sensory phenomenon that would make a strong short video hook',
        'counterintuitive behavior that invites explanations in the replies',
        'one unusually specific object, machine, animal, place, or process',
        'future possibility grounded in current science rather than fantasy',
        'practical curiosity: why something is built or operated that way',
        'unexpected comparison of scale, speed, energy, distance, cost, or complexity',
        'mini mystery with a real technical or natural explanation, not horror',
        'first-person observation that leads to a broader factual discussion',
        'debate prompt with two defensible technical or cultural perspectives',
        'overlooked failure, edge case, or unintended consequence',
        'beautiful, weird, elegant, or absurd detail that is still believable'
    )
    $settings = @(
        'No named city or country unless the subject genuinely needs one.',
        'Keep the setting location-neutral; focus on the object, mechanism, animal, or fact.',
        'Use a lab, workshop, garage, factory, launch site, data center, farm, port, mine, or field site if useful.',
        'Use space, orbit, the Moon, Mars, an observatory, or a launch range if the subject calls for it.',
        'Use a remote natural environment such as desert, tundra, rainforest, mountain, cave, reef, or deep ocean if relevant.',
        'If a real location materially improves the idea, choose a lesser-known and geographically varied location rather than a default famous megacity.',
        'Prefer a niche regional setting, small town, island, border region, industrial area, rural site, or unusual landscape when place matters.',
        'Use a road, racetrack, rail yard, airport, shipyard, charging station, power plant, or infrastructure site when it fits.',
        'No named location; make this one globally relatable.',
        'No named location; make the hook about scale, mechanism, design, behavior, or discovery.'
    )
    $sequence = [Math]::Max(1, [int]$AttemptNumber)
    $shapeFeedback = ''
    for ($ideaTry = 1; $ideaTry -le $IdeaRetries; $ideaTry++) {
        $axis = $sequence + $ideaTry - 1
        $theme = $themes[(($axis * 17 + 3) % $themes.Count)]
        $lens = $lenses[(($axis * 11 + 5) % $lenses.Count)]
        $setting = $settings[(($axis * 7 + 1) % $settings.Count)]
        $recentBlock = Get-RecentIdeaBlock $History
        if ($Platform -eq 'x') {
            $shape = @"
Create one NEW ThreadGens X seed.
Return JSON with exactly these string fields:
{"title":"hidden reply style","body":"visible X post"}

"title" is a short hidden reply instruction, at most 8 words and 48 characters.
"body" is the visible X post: concise, specific, natural, and at most 280 characters.
"@
        } else {
            $shape = @"
Create one NEW ThreadGens Reddit seed.
Return JSON with exactly these string fields:
{"title":"reddit post title","body":"reddit post body"}

The seed must fit a fixed 1080x1920 Reddit OP card without truncation.
"title" must be a natural Reddit-style question/prompt, 5-11 words and at most 64 characters total.
"body" must be 1-2 concise sentences, preferably 30-50 words, and at most 300 characters total.
Keep both fields compact. Do not pad the setup with extra adjectives, backstory, or repeated explanation.
"@
        }
        $correction = if ([string]::IsNullOrWhiteSpace($shapeFeedback)) { '' } else { "`nThe previous candidate was rejected before rendering because: $shapeFeedback`nCorrect that size problem in this retry." }
        $prompt = @"
$shape
$correction

Target subject family: $theme
Creative lens: $lens
Setting guidance: $setting

Diversity mandate:
- The subject family is the core of this attempt. It can be educational, technical, scientific, weird, factual, speculative, travel-focused, animal-focused, artistic, or personal. Do NOT force every seed into interpersonal conflict.
- A seed may be a question, startling fact, comparison, scenario, mini-explainer, engineering tradeoff, niche-location curiosity, observation, debate, or human story.
- Strongly vary domains across the batch. Avoid falling back to the same cities, jobs, relationship conflicts, horror beats, mysterious notes, abandoned buildings, or generic "something strange happened" setups.
- Named places are optional. Do not default to Tokyo, Japan, New York, Los Angeles, London, Paris, Dubai, or another famous megacity. Tokyo is not banned; it should simply be rare and only appear when genuinely relevant.
- When a location matters, choose it because the subject belongs there. Vary continents, climates, rural/urban scale, industrial/natural settings, and well-known versus obscure places. Do not reuse a place from RECENT IDEAS.
- For science, engineering, technology, animals, and history, prefer a concrete real mechanism, stable fact, or clearly framed question. Never invent a study, quote, discovery, record, accident, launch, product announcement, or breaking-news claim.
- SpaceX, Tesla, EVs, AI, nuclear science, energy, cars, and other real technologies are welcome subjects, but use stable/common knowledge or curiosity rather than pretending to know current announcements or live status.
- Be specific enough to create vivid replies: name the component, animal, material, machine, phenomenon, design constraint, landscape, or process when useful.
- Keep the premise understandable to a general audience without flattening it into generic trivia.

Hard rules:
- Invent a materially new premise, not a paraphrase of anything in RECENT IDEAS.
- Vary hook, setting, people, objects, mechanism, question type, emotional tone, and likely reply structure.
- Do not keep producing horror/mystery prompts; broad curiosity and useful knowledge should be common.
- Make the setup easy to narrate aloud.
- Do not mention ThreadGens, AI prompts, engagement counts, verification, moderation actions, or platform algorithms. AI itself may be the subject when the selected family calls for it.
- Do not make accusations about identifiable real people.
- Respect every title/body size limit above. Limits are hard validation rules, not suggestions.
- No markdown, no code fence, no explanation: output only the JSON object.

RECENT IDEAS THAT MUST NOT BE RECYCLED:
$recentBlock
"@
        $keepAlive = if ($KeepOllamaLoaded) { '30m' } else { '0s' }
        $payload = @{
            model = $Model
            prompt = $prompt
            stream = $false
            format = 'json'
            keep_alive = $keepAlive
            options = @{ temperature = 1.10; top_p = 0.97; top_k = 60; repeat_penalty = 1.12 }
        } | ConvertTo-Json -Depth 8
        try {
            $response = Invoke-RestMethod -Uri $script:OllamaGenerateUrl -Method Post -ContentType 'application/json' -Body $payload -TimeoutSec 300
            $idea = ConvertFrom-IdeaResponse $response.response
        } catch {
            if ($ideaTry -ge $IdeaRetries) { throw "Could not generate a valid batch idea after $IdeaRetries tries: $($_.Exception.Message)" }
            Write-Host "Idea generation try $ideaTry/$IdeaRetries failed: $($_.Exception.Message)" -ForegroundColor Yellow
            continue
        }
        $title = (([string]$idea.title) -replace '\s+', ' ').Trim()
        $body = (([string]$idea.body) -replace '\s+', ' ').Trim()
        $shapeProblem = Get-IdeaShapeProblem $title $body $Platform
        if (-not [string]::IsNullOrWhiteSpace([string]$shapeProblem)) {
            $shapeFeedback = [string]$shapeProblem
            Write-Host "Idea generation try $ideaTry/$IdeaRetries failed render-fit guard: $shapeProblem; regenerating before video attempt." -ForegroundColor Yellow
            continue
        }
        $shapeFeedback = ''
        $key = Get-IdeaKey $title $body $Platform
        if ($SeenKeys.ContainsKey($key)) {
            Write-Host "Idea generation try $ideaTry/$IdeaRetries repeated a prior seed; regenerating." -ForegroundColor Yellow
            continue
        }
        $id = [Guid]::NewGuid().ToString('N')
        $created = (Get-Date).ToUniversalTime().ToString('o')
        $entry = [pscustomobject]@{
            event = 'generated'; id = $id; created = $created; status = 'generated'; attempt = $AttemptNumber
            platform = $Platform; theme = $theme; lens = $lens; setting = $setting; title = $title; body = $body; key = $key
        }
        Add-IdeaHistoryEvent $entry
        $SeenKeys[$key] = $true
        return $entry
    }
    throw "Could not generate a unique render-fit batch idea after $IdeaRetries tries. Last size problem: $shapeFeedback"
}

function Invoke-NewBatchIdeaSafe($AttemptNumber, $History, [hashtable]$SeenKeys, [scriptblock]$GeneratorOverride = $null) {
    try {
        $idea = if ($null -ne $GeneratorOverride) { & $GeneratorOverride } else { Invoke-NewBatchIdea $AttemptNumber $History $SeenKeys }
        if ($null -eq $idea) { throw 'Idea generator returned no idea.' }
        return [pscustomobject]@{ succeeded = $true; idea = $idea; reason = '' }
    } catch {
        return [pscustomobject]@{ succeeded = $false; idea = $null; reason = $_.Exception.Message }
    }
}

function ConvertTo-Base64Url($Value) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$Value)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function ConvertFrom-Base64Url($Value) {
    $text = ([string]$Value).Replace('-', '+').Replace('_', '/')
    switch ($text.Length % 4) {
        2 { $text += '==' }
        3 { $text += '=' }
    }
    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($text))
}

function New-PendingHistoryLine($Reservation) {
    return ([ordered]@{
        created = (Get-Date).ToUniversalTime().ToString('o')
        format = if ([string]::IsNullOrWhiteSpace([string]$Reservation.Format)) { 'unknown' } else { [string]$Reservation.Format }
        variant = if ([string]::IsNullOrWhiteSpace([string]$Reservation.Variant)) { 'unknown' } else { [string]$Reservation.Variant }
        hash = 'pending'
        topic_b64 = ConvertTo-Base64Url $Reservation.Topic
        script_b64 = ConvertTo-Base64Url $Reservation.Script
    } | ConvertTo-Json -Compress)
}

function New-JobHistorySnapshot($Destination) {
    $folder = Split-Path -Parent $Destination
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $lines = @()
    if (Test-Path $GlobalGenerationHistoryPath) {
        $lines += @(Get-Content -Path $GlobalGenerationHistoryPath -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }
    foreach ($reservation in @($script:pendingReservations.Values)) {
        if ($reservation -and -not [string]::IsNullOrWhiteSpace([string]$reservation.Script)) {
            $lines += New-PendingHistoryLine $reservation
        }
    }
    [System.IO.File]::WriteAllLines($Destination, [string[]]$lines, $Utf8NoBom)
}

function Find-RecordedHistoryLine($HistoryPath, $Script) {
    if ([string]::IsNullOrWhiteSpace([string]$Script) -or -not (Test-Path $HistoryPath)) { return $null }
    $lines = @(Get-Content -Path $HistoryPath -Encoding UTF8)
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        $raw = [string]$lines[$i]
        if ([string]::IsNullOrWhiteSpace($raw)) { continue }
        try { $entry = $raw | ConvertFrom-Json } catch { continue }
        if ([string]::IsNullOrWhiteSpace([string]$entry.hash) -or [string]$entry.hash -eq 'pending') { continue }
        if ([string]::IsNullOrWhiteSpace([string]$entry.script_b64)) { continue }
        try { $decoded = ConvertFrom-Base64Url $entry.script_b64 } catch { continue }
        if ($decoded.Trim() -eq ([string]$Script).Trim()) { return $raw }
    }
    return $null
}

function Merge-GlobalHistoryLine($RawLine) {
    if ([string]::IsNullOrWhiteSpace([string]$RawLine)) { return }
    $folder = Split-Path -Parent $GlobalGenerationHistoryPath
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $existing = if (Test-Path $GlobalGenerationHistoryPath) { @(Get-Content -Path $GlobalGenerationHistoryPath -Encoding UTF8) } else { @() }
    $newHash = ''
    try { $newHash = [string](($RawLine | ConvertFrom-Json).hash) } catch { }
    if (-not [string]::IsNullOrWhiteSpace($newHash)) {
        foreach ($line in $existing) {
            try {
                if ([string](($line | ConvertFrom-Json).hash) -eq $newHash) { return }
            } catch { }
        }
    } elseif ($existing -contains $RawLine) {
        return
    }
    $existing += $RawLine
    if ($existing.Count -gt 1000) { $existing = @($existing | Select-Object -Last 500) }
    [System.IO.File]::WriteAllLines($GlobalGenerationHistoryPath, [string[]]$existing, $Utf8NoBom)
}

function Get-FreeLoopbackPort {
    $listener = New-Object System.Net.Sockets.TcpListener -ArgumentList ([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Start-OllamaSerialProxy {
    if (-not (Test-Path $ProxyScript)) { throw "Ollama serial proxy was not found: $ProxyScript" }
    $port = Get-FreeLoopbackPort
    $proxyLog = Join-Path $OutputRoot 'ollama_proxy.log'
    $proxyErr = Join-Path $OutputRoot 'ollama_proxy.error.log'
    $proxyCommandLine = "`"$ProxyScript`" --listen-port $port --upstream http://127.0.0.1:11434"
    $script:proxyProcess = Start-Process -FilePath $KokoroPython -ArgumentList $proxyCommandLine -PassThru -WindowStyle Hidden -RedirectStandardOutput $proxyLog -RedirectStandardError $proxyErr
    $health = "http://127.0.0.1:$port/health"
    $deadline = (Get-Date).AddSeconds(20)
    do {
        if ($script:proxyProcess.HasExited) {
            throw "Ollama serial proxy exited during startup. See $proxyErr"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $health -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $script:OllamaGenerateUrl = "http://127.0.0.1:$port/api/generate"
                Write-Host "Ollama request gate: serialized through local proxy on port $port" -ForegroundColor Green
                return
            }
        } catch { }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Ollama serial proxy did not become ready within 20 seconds. See $proxyErr"
}

function Stop-ProcessTree($Process) {
    if ($null -eq $Process) { return }
    try {
        if (-not $Process.HasExited) {
            if ($env:OS -eq 'Windows_NT') {
                & taskkill.exe /PID $Process.Id /T /F *> $null
            } else {
                $Process.Kill()
            }
        }
    } catch { }
}

function Write-NewWorkerLogLines($Job) {
    if (-not (Test-Path $Job.LogPath)) { return }
    try { $lines = @(Get-Content -Path $Job.LogPath -Encoding UTF8 -ErrorAction Stop) } catch { return }
    for ($i = [int]$Job.LogLineCount; $i -lt $lines.Count; $i++) {
        Write-Host "[W$($Job.SlotLabel) A$($Job.AttemptLabel)] $($lines[$i])"
    }
    $Job.LogLineCount = $lines.Count
}

function Update-WorkerGenerationState($Job) {
    if ($Job.GenerationSettled) { return }
    if (-not (Test-Path $Job.LogPath) -or -not (Test-Path $Job.ScriptOut)) { return }
    $formatMarker = $null
    $variantMarker = $null
    try {
        $formatMarker = Select-String -Path $Job.LogPath -Pattern '^P0 format:\s+([a-z_]+)' -ErrorAction Stop | Select-Object -Last 1
        $variantMarker = Select-String -Path $Job.LogPath -Pattern '^P0 format variant:\s+([a-z_]+)' -ErrorAction Stop | Select-Object -Last 1
    } catch { }
    if ($null -eq $formatMarker -or $null -eq $variantMarker) { return }
    try { $scriptText = (Get-Content -Raw -Path $Job.ScriptOut -Encoding UTF8).Trim() } catch { return }
    if ([string]::IsNullOrWhiteSpace($scriptText)) { return }
    $Job.Script = $scriptText
    $Job.Format = $formatMarker.Matches[0].Groups[1].Value
    $Job.Variant = $variantMarker.Matches[0].Groups[1].Value
    $Job.GenerationSettled = $true
    $script:pendingReservations[$Job.AttemptLabel] = [pscustomobject]@{
        Attempt = $Job.AttemptLabel
        Script = $scriptText
        Topic = [string]$Job.Body
        Format = $Job.Format
        Variant = $Job.Variant
    }
    Write-Host "Worker A$($Job.AttemptLabel) finished its serialized Ollama generation and is now free to render in parallel." -ForegroundColor DarkCyan
}

function Try-MergeWorkerGenerationHistory($Job) {
    if ($Job.HistoryMerged -or [string]::IsNullOrWhiteSpace([string]$Job.Script)) { return }
    $line = Find-RecordedHistoryLine $Job.JobHistoryPath $Job.Script
    if ($null -eq $line) { return }
    Merge-GlobalHistoryLine $line
    $Job.HistoryMerged = $true
    [void]$script:pendingReservations.Remove($Job.AttemptLabel)
    Write-Host "Worker A$($Job.AttemptLabel) committed its P0 generation history through the master." -ForegroundColor DarkGreen
}

function Test-AllActiveGenerationSettled {
    foreach ($job in @($script:activeJobs)) {
        if (-not $job.GenerationSettled -and -not $job.Process.HasExited) { return $false }
    }
    return $true
}

function Add-FailureRecord($AttemptLabel, $SlotLabel, $Title, $Reason) {
    [void]$script:failedAttempts.Add([pscustomobject]@{ Attempt = $AttemptLabel; Slot = $SlotLabel; Title = $Title; Reason = $Reason })
}

function Start-VideoWorker($Slot, $Idea, $AttemptLabel) {
    $slotLabel = '{0:D3}' -f $Slot
    $title = [string]$Idea.title
    $body = [string]$Idea.body
    $safeTitle = New-SafeFileName $title
    $finalVideoName = Get-UniqueFinalVideoName $safeTitle $FinalDir
    $script:reservedFinalNames[$finalVideoName.ToLowerInvariant()] = $true
    $jobRoot = Join-Path $OutputRoot ("attempt_${AttemptLabel}_slot_${slotLabel}")
    $imageDir = Join-Path $jobRoot 'images'
    $audioDir = Join-Path $jobRoot 'audio'
    $videoDir = Join-Path $jobRoot 'video'
    $scriptDir = Join-Path $jobRoot 'script'
    $historyDir = Join-Path $jobRoot 'history'
    $opImageDir = Join-Path $jobRoot 'op_images'
    $opImageCacheDir = Join-Path $jobRoot 'image_cache'
    $metadataDir = Join-Path $jobRoot 'metadata'
    $scriptOut = Join-Path $scriptDir 'generated_comments.txt'
    $jobHistoryPath = Join-Path $historyDir 'generation_history.jsonl'
    $logPath = Join-Path $jobRoot 'worker.log'
    $jobFile = Join-Path $jobRoot 'worker_job.json'
    $palette = $Palettes[(($Slot - 1) % $Palettes.Count)]
    $selectedFormat = Select-BatchFormat $FormatSelection $Format $FormatPool $Slot ([int]$AttemptLabel) $FormatOffset
    $effectiveSeriesId = Get-BatchSeriesId $VoiceSelection $SeriesId $Platform $Slot
    New-Item -ItemType Directory -Force -Path $imageDir, $audioDir, $videoDir, $scriptDir, $historyDir | Out-Null
    if ($GenerateOpImage) { New-Item -ItemType Directory -Force -Path $opImageDir, $opImageCacheDir | Out-Null }
    New-JobHistorySnapshot $jobHistoryPath

    $javaArgs = @(
        '-cp', 'out', 'redditTxtToImg.OpImageVideoSafeRunner',
        'data\comments.txt', $imageDir,
        '--platform', $Platform,
        '--auto',
        '--post-title', $title,
        '--topic', $body,
        '--count', [string]$Count,
        '--format', $selectedFormat,
        '--format-variant', $FormatVariant,
        '--llm-model', $Model,
        '--llm-url', $script:OllamaGenerateUrl,
        '--history-file', $jobHistoryPath,
        '--tts', $TtsEngine,
        '--tts-command', $KokoroPython,
        '--voice', $Voice,
        '--voice-series', $VoiceSeries,
        '--voice-selection', $VoiceSelection,
        '--tts-delivery', $TtsDelivery,
        '--audio-dir', $audioDir,
        '--video',
        '--concat-video',
        '--video-dir', $videoDir,
        '--script-out', $scriptOut,
        '--final-video', $finalVideoName,
        '--captions', $Captions,
        '--metadata-dir', $metadataDir,
        '--publish-history', $PublishHistoryPath,
        '--no-watermark',
        '--top'
    )
    if ($GenerateOpImage) { $javaArgs += @('--image-mode', 'comfyui', '--image-dir', $opImageDir, '--image-cache-dir', $opImageCacheDir) }
    if ($KeepOllamaLoaded) { $javaArgs += '--keep-ollama-loaded' }
    if (-not [string]::IsNullOrWhiteSpace($effectiveSeriesId)) { $javaArgs += @('--series-id', $effectiveSeriesId) }

    $jobConfig = [ordered]@{ repoRoot = $RepoRoot; logPath = $logPath; palette = $palette; javaArgs = $javaArgs }
    [System.IO.File]::WriteAllText($jobFile, ($jobConfig | ConvertTo-Json -Depth 8), $Utf8NoBom)
    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path $powerShellExe)) { $powerShellExe = 'powershell.exe' }
    $workerCommandLine = "-NoProfile -ExecutionPolicy Bypass -File `"$WorkerScript`" -JobFile `"$jobFile`""
    $process = Start-Process -FilePath $powerShellExe -ArgumentList $workerCommandLine -PassThru -WindowStyle Hidden
    $job = [pscustomobject]@{
        Process = $process; Slot = $Slot; SlotLabel = $slotLabel; AttemptLabel = $AttemptLabel; Idea = $Idea
        Title = $title; Body = $body; Palette = $palette; Format = $selectedFormat; FinalVideoName = $finalVideoName; VideoDir = $videoDir
        ScriptOut = $scriptOut; JobHistoryPath = $jobHistoryPath; LogPath = $logPath; LogLineCount = 0
        GenerationSettled = $false; HistoryMerged = $false; Script = ''; Variant = ''; Started = Get-Date
    }
    [void]$script:activeJobs.Add($job)

    Write-Step "[slot $slotLabel/$script:targetLabel | attempt $AttemptLabel | active $($script:activeJobs.Count)/$Workers] $Platform worker started"
    if ($Platform -eq 'x') { Write-Host "X style: $title"; Write-Host "Visible X post: $body" } else { Write-Host "Reddit title: $title"; Write-Host "Reddit body: $body" }
    Write-Host "Idea family: $($Idea.theme)"
    Write-Host "Background palette: $palette"
    Write-Host "P0 format: $selectedFormat / substyle $FormatVariant"
    if (-not [string]::IsNullOrWhiteSpace($effectiveSeriesId)) { Write-Host "Voice series key: $effectiveSeriesId" }
    Write-Host "Worker log: $logPath"
    Write-Host "Final MP4 if approved: $finalVideoName"
    return $job
}

function Complete-Worker($Job) {
    Write-NewWorkerLogLines $Job
    if ([string]::IsNullOrWhiteSpace([string]$Job.Script) -and (Test-Path $Job.ScriptOut)) {
        try { $Job.Script = (Get-Content -Raw -Path $Job.ScriptOut -Encoding UTF8).Trim() } catch { }
    }
    if (-not $Job.GenerationSettled -and -not [string]::IsNullOrWhiteSpace([string]$Job.Script)) {
        $Job.GenerationSettled = $true
        $script:pendingReservations[$Job.AttemptLabel] = [pscustomobject]@{
            Attempt = $Job.AttemptLabel; Script = $Job.Script; Topic = [string]$Job.Body
            Format = $Job.Format; Variant = $Job.Variant
        }
    }
    Try-MergeWorkerGenerationHistory $Job

    $exitCode = $Job.Process.ExitCode
    $finalVideo = Join-Path $Job.VideoDir $Job.FinalVideoName
    $provenance = "$finalVideo.provenance.json"
    $reason = ''
    if ($exitCode -ne 0) { $reason = "Video attempt $($Job.AttemptLabel) failed with exit code $exitCode. See $($Job.LogPath)" }
    elseif (-not (Test-Path $finalVideo)) { $reason = "Video attempt $($Job.AttemptLabel) finished but final video was not found: $finalVideo" }
    elseif (-not (Test-Path $provenance)) { $reason = "Video attempt $($Job.AttemptLabel) finished but provenance sidecar was not found: $provenance" }

    if ([string]::IsNullOrWhiteSpace($reason)) {
        $copyTo = Join-Path $FinalDir $Job.FinalVideoName
        Copy-Item -Force -Path $finalVideo -Destination $copyTo
        Copy-Item -Force -Path $provenance -Destination "$copyTo.provenance.json"
        $script:completedSlots[[string]$Job.Slot] = $true
        $script:succeededVideos = $script:completedSlots.Count
        Add-IdeaHistoryEvent ([pscustomobject]@{
            event = 'outcome'; id = $Job.Idea.id; created = (Get-Date).ToUniversalTime().ToString('o'); status = 'approved'
            attempt = [int]$Job.AttemptLabel; approvedSlot = $Job.Slot; palette = $Job.Palette; output = $copyTo
            format = $Job.Format
        })
        Write-Host "Approved slot $($Job.SlotLabel). Progress $script:succeededVideos/$TargetVideos. Saved: $copyTo" -ForegroundColor Green
    } else {
        Add-IdeaHistoryEvent ([pscustomobject]@{
            event = 'outcome'; id = $Job.Idea.id; created = (Get-Date).ToUniversalTime().ToString('o'); status = 'rejected'
            attempt = [int]$Job.AttemptLabel; approvedSlot = $Job.Slot; palette = $Job.Palette; reason = $reason
            format = $Job.Format
        })
        Add-FailureRecord $Job.AttemptLabel $Job.SlotLabel $Job.Title $reason
        [void]$script:reservedFinalNames.Remove($Job.FinalVideoName.ToLowerInvariant())
        [void]$script:pendingReservations.Remove($Job.AttemptLabel)
        [void]$script:pendingSlots.Insert(0, [int]$Job.Slot)
        Write-Host "Attempt $($Job.AttemptLabel) did not fill slot $($Job.SlotLabel): $reason" -ForegroundColor Red
        if ($StopOnError) {
            foreach ($other in @($script:activeJobs)) { if ($other -ne $Job) { Stop-ProcessTree $other.Process } }
            throw $reason
        }
        Write-Host "Replacement queued; approved progress remains $script:succeededVideos/$TargetVideos." -ForegroundColor Yellow
    }
    [void]$script:pendingReservations.Remove($Job.AttemptLabel)
    [void]$script:activeJobs.Remove($Job)
}

function Run-SelfTest {
    Test-BatchFormatRotation
    $compact = Get-IdeaShapeProblem 'Why does my cat steal socks?' 'Every morning my cat moves one clean sock from the basket to the hallway, then waits beside it like this is a job.' 'reddit'
    if ($null -ne $compact) { throw "Compact Reddit seed unexpectedly failed: $compact" }
    $oversized = Get-IdeaShapeProblem "Why do my neighbor's vintage synthesizer always produce a faint melody in perfect harmony with our building's fire alarm?" 'This body is intentionally short enough that only the title guard should fail.' 'reddit'
    if ($null -eq $oversized) { throw 'Render-fit self-test failed to reject the known oversized synthesizer title.' }
    $roundTrip = 'history reservation test — exact text'
    if ((ConvertFrom-Base64Url (ConvertTo-Base64Url $roundTrip)) -ne $roundTrip) { throw 'Base64url history helper round-trip failed.' }

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('threadgens-parallel-selftest-' + [Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        $oldGlobal = $script:GlobalGenerationHistoryPath
        $oldReservations = $script:pendingReservations
        $script:GlobalGenerationHistoryPath = Join-Path $temp 'global.jsonl'
        $script:pendingReservations = @{
            '0001' = [pscustomobject]@{
                Attempt = '0001'; Script = 'pending script one'; Topic = 'topic one'
                Format = 'thread_story'; Variant = 'witness_chain'
            }
        }
        $snapshot = Join-Path $temp 'job\history.jsonl'
        New-JobHistorySnapshot $snapshot
        $rows = @(Get-Content $snapshot -Encoding UTF8)
        if ($rows.Count -ne 1 -or $rows[0] -notmatch '"hash":"pending"') { throw 'Pending novelty reservation was not written to the worker history snapshot.' }
        if ($rows[0] -notmatch '"format":"thread_story"' -or $rows[0] -notmatch '"variant":"witness_chain"') {
            throw 'Pending novelty reservation did not preserve format and substyle.'
        }
        $reserved = @{ 'test.mp4' = $true }
        $name = Get-UniqueFinalVideoName 'test' $temp $reserved
        if ($name -ne 'test_a.mp4') { throw "Reserved final-name collision test failed: $name" }

        $markerLog = Join-Path $temp 'marker.log'
        $markerScript = Join-Path $temp 'generated.txt'
        Set-Content -Path $markerScript -Value 'stable generated script' -Encoding UTF8
        Set-Content -Path $markerLog -Value 'P0 hidden-prompt generation attempt 1/5' -Encoding UTF8
        $fakeJob = [pscustomobject]@{
            GenerationSettled = $false; LogPath = $markerLog; ScriptOut = $markerScript; Script = ''
            AttemptLabel = '9999'; Body = 'marker topic'; Format = ''; Variant = ''
        }
        Update-WorkerGenerationState $fakeJob
        if ($fakeJob.GenerationSettled) { throw 'Worker generation-ready marker fired before P0 entered the finalized render path.' }
        Add-Content -Path $markerLog -Value 'P0 format: debate (Two-sided debate)' -Encoding UTF8
        Add-Content -Path $markerLog -Value 'P0 format variant: skeptical_qa (qa pacing)' -Encoding UTF8
        Update-WorkerGenerationState $fakeJob
        if (-not $fakeJob.GenerationSettled -or $fakeJob.Script -ne 'stable generated script' -or
            $fakeJob.Format -ne 'debate' -or $fakeJob.Variant -ne 'skeptical_qa') {
            throw 'Worker generation-ready marker did not preserve the finalized format and substyle.'
        }
        [void]$script:pendingReservations.Remove('9999')
    } finally {
        if ($null -ne $oldGlobal) { $script:GlobalGenerationHistoryPath = $oldGlobal }
        if ($null -ne $oldReservations) { $script:pendingReservations = $oldReservations }
        Remove-Item -Recurse -Force -Path $temp -ErrorAction SilentlyContinue
    }
    Write-Host 'Parallel batch self-test passed.' -ForegroundColor Green
}

if ($SelfTest) {
    Run-SelfTest
    exit 0
}

if (-not (Test-Path $KokoroPython)) { throw "Kokoro Python was not found: $KokoroPython. Run setup_windows.bat first." }
if (-not (Test-Path $WorkerScript)) { throw "Parallel worker script was not found: $WorkerScript" }
if (-not (Test-Path $ProxyScript)) { throw "Ollama serial proxy was not found: $ProxyScript" }

Write-Step 'ThreadGens parallel self-filling batch video creator'
Write-Host "Target approved videos: $TargetVideos"
Write-Host "Slides/replies per video: $Count"
Write-Host "Parallel video workers: $Workers (configurable 1-10)" -ForegroundColor Green
Write-Host 'Ollama concurrency: exactly 1 through the local serial proxy' -ForegroundColor Green
Write-Host 'Worker generation staging: next worker launches only after prior active workers have completed P0 generation, so rendering overlaps but novelty checks stay ordered' -ForegroundColor Green
Write-Host "Idea history: $IdeaHistoryPath"
Write-Host "Global generation history: $GlobalGenerationHistoryPath"
Write-Host "Output root: $OutputRoot"
Write-Host "Defaults: platform=$Platform, formatSelection=$FormatSelection, formatSeries=[$($FormatPool -join ',')], substyle=$FormatVariant, model=$Model, tts=$TtsEngine"
Write-Host "Format cooldown history: $PublishHistoryPath (rotation offset $FormatOffset)"
Write-Host "P1 voice selection: $VoiceSelection from [$VoiceSeries], delivery=$TtsDelivery, captions=$Captions"
Write-Host 'Final MP4 names: title-based with no numeric prefix'
Write-Host 'Rejected workers are replaced until every target slot is filled' -ForegroundColor Green
if ($MaxAttempts -gt 0) { Write-Host "Attempt cap: $MaxAttempts total ideas" } else { Write-Host 'Attempt cap: none' }
if ($GenerateOpImage) { Write-Host 'OP image generation: enabled; worker count forced to 1 for GPU safety' -ForegroundColor Yellow } else { Write-Host 'OP image generation: disabled' }

Write-Step 'Building Java files'
New-Item -ItemType Directory -Force -Path (Join-Path $RepoRoot 'out') | Out-Null
$javaFiles = Get-ChildItem -Path (Join-Path $RepoRoot 'src\redditTxtToImg') -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $javaFiles -or $javaFiles.Count -eq 0) { throw 'No Java files found in src\redditTxtToImg.' }
& javac -d (Join-Path $RepoRoot 'out') $javaFiles
if ($LASTEXITCODE -ne 0) { throw "Java build failed with exit code $LASTEXITCODE." }

New-Item -ItemType Directory -Force -Path $OutputRoot, $FinalDir | Out-Null
$globalHistoryFolder = Split-Path -Parent $GlobalGenerationHistoryPath
if ($globalHistoryFolder) { New-Item -ItemType Directory -Force -Path $globalHistoryFolder | Out-Null }

$script:ideaHistory = @(Read-IdeaHistory $IdeaHistoryPath)
foreach ($oldIdea in $script:ideaHistory) {
    $oldKey = [string]$oldIdea.key
    if ([string]::IsNullOrWhiteSpace($oldKey)) { $oldKey = Get-IdeaKey $oldIdea.title $oldIdea.body $oldIdea.platform }
    $script:seenIdeaKeys[$oldKey] = $true
}
for ($slot = 1; $slot -le $TargetVideos; $slot++) { [void]$script:pendingSlots.Add($slot) }
Write-Host "Previously attempted ideas loaded: $($script:ideaHistory.Count)"

try {
    Start-OllamaSerialProxy

    while ($script:succeededVideos -lt $TargetVideos) {
        foreach ($job in @($script:activeJobs)) {
            Write-NewWorkerLogLines $job
            Update-WorkerGenerationState $job
            Try-MergeWorkerGenerationHistory $job
        }
        foreach ($job in @($script:activeJobs)) {
            if ($job.Process.HasExited) { Complete-Worker $job }
        }

        $launchedSomething = $false
        while ($script:activeJobs.Count -lt $Workers -and $script:pendingSlots.Count -gt 0 -and (Test-AllActiveGenerationSettled)) {
            if ($MaxAttempts -gt 0 -and $script:totalAttempts -ge $MaxAttempts) { break }
            $slot = [int]$script:pendingSlots[0]
            $script:pendingSlots.RemoveAt(0)
            $script:totalAttempts++
            $attemptLabel = '{0:D4}' -f $script:totalAttempts
            $slotLabel = '{0:D3}' -f $slot
            $ideaResult = Invoke-NewBatchIdeaSafe $script:totalAttempts $script:ideaHistory $script:seenIdeaKeys
            if (-not $ideaResult.succeeded) {
                $reason = [string]$ideaResult.reason
                Add-IdeaHistoryEvent ([pscustomobject]@{
                    event = 'generation_failure'; id = ''; created = (Get-Date).ToUniversalTime().ToString('o'); status = 'rejected'
                    attempt = $script:totalAttempts; approvedSlot = $slot; retries = $IdeaRetries; reason = $reason
                })
                Add-FailureRecord $attemptLabel $slotLabel '<idea generation>' $reason
                [void]$script:pendingSlots.Insert(0, $slot)
                Write-Host "Attempt $attemptLabel exhausted all $IdeaRetries seed retries for slot ${slotLabel}: $reason" -ForegroundColor Red
                if ($StopOnError) { throw "Idea generation failed after $IdeaRetries retries: $reason" }
                Write-Host 'Starting a fresh seed-generation cycle; active render workers continue.' -ForegroundColor Yellow
                continue
            }
            $idea = $ideaResult.idea
            $script:ideaHistory = @($script:ideaHistory + $idea)
            [void](Start-VideoWorker $slot $idea $attemptLabel)
            $launchedSomething = $true
            # The newly launched worker must finish P0 generation and establish its
            # pending novelty reservation before another worker is allowed to start.
            break
        }

        if ($script:succeededVideos -ge $TargetVideos) { break }
        if ($script:activeJobs.Count -eq 0) {
            if ($script:pendingSlots.Count -eq 0) { break }
            if ($MaxAttempts -gt 0 -and $script:totalAttempts -ge $MaxAttempts) { break }
        }
        if (-not $launchedSomething) { Start-Sleep -Milliseconds 300 }
    }

    while ($script:activeJobs.Count -gt 0) {
        foreach ($job in @($script:activeJobs)) {
            Write-NewWorkerLogLines $job
            Update-WorkerGenerationState $job
            Try-MergeWorkerGenerationHistory $job
        }
        foreach ($job in @($script:activeJobs)) {
            if ($job.Process.HasExited) { Complete-Worker $job }
        }
        if ($script:activeJobs.Count -gt 0) { Start-Sleep -Milliseconds 300 }
    }
} finally {
    foreach ($job in @($script:activeJobs)) { Stop-ProcessTree $job.Process }
    Stop-ProcessTree $script:proxyProcess
    Remove-Item Env:THREADGENS_PALETTE -ErrorAction SilentlyContinue
}

Write-Step 'Batch complete'
Write-Host "Approved final videos: $script:succeededVideos/$TargetVideos" -ForegroundColor Green
Write-Host "Total ideas attempted:  $script:totalAttempts"
Write-Host "Rejected attempts:      $($script:failedAttempts.Count)"
Write-Host "Maximum video workers:  $Workers"
Write-Host 'Ollama request workers: 1 (serialized)'
Write-Host "Persistent idea history: $IdeaHistoryPath"
Write-Host "Persistent generation history: $GlobalGenerationHistoryPath"
Write-Host "All attempt folders:     $OutputRoot"
Write-Host "Final MP4 copies:        $FinalDir" -ForegroundColor Green

if ($script:failedAttempts.Count -gt 0) {
    $failureReport = Join-Path $OutputRoot 'rejected_attempts.txt'
    $reportLines = @('ThreadGens rejected/failed replacement attempts', '')
    foreach ($failure in $script:failedAttempts) {
        $reportLines += "[attempt $($failure.Attempt) -> slot $($failure.Slot)] $($failure.Title) :: $($failure.Reason)"
    }
    $reportLines | Set-Content -Path $failureReport -Encoding UTF8
    Write-Host "Rejected-attempt report: $failureReport" -ForegroundColor Yellow
}

if ($script:succeededVideos -lt $TargetVideos) {
    Write-Host "Batch stopped before reaching the approved target ($script:succeededVideos/$TargetVideos)." -ForegroundColor Red
    exit 2
}
Write-Host 'Approved target reached. Whole-video workers overlapped rendering while all Ollama requests remained serialized.' -ForegroundColor Green
exit 0
