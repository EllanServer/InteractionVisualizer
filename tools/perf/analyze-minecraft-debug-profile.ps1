<#
.SYNOPSIS
Analyzes vanilla Minecraft F3+L client profiling tick-time samples.

.DESCRIPTION
Reads either a Minecraft debug profiling ZIP or its client/metrics/ticking.csv
file. The vanilla ticktime sampler is measured in nanoseconds around the client
main loop, including rendering and the configured FPS limiter. It is a useful
non-elevated client-loop frame-time proxy, but it is not compositor/displayed
frame time and does not replace PresentMon for dropped-frame evidence.

.EXAMPLE
.\tools\perf\analyze-minecraft-debug-profile.ps1 `
    .\debug\profiling\2026-07-13_12.00.00-server-1.21.11.zip `
    -TrimStartSeconds 10 -TrimEndSeconds 10 -OutputJson run.json

.EXAMPLE
.\tools\perf\analyze-minecraft-debug-profile.ps1 -SelfTest
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$InputPath,

    [ValidateRange(0.0, 86400.0)]
    [double]$TrimStartSeconds = 0.0,

    [ValidateRange(0.0, 86400.0)]
    [double]$TrimEndSeconds = 0.0,

    [string]$OutputJson,

    [switch]$Overwrite,

    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:InvariantCulture = [Globalization.CultureInfo]::InvariantCulture
$script:NumberStyles = [Globalization.NumberStyles]::Float

function Resolve-Column {
    param(
        [Parameter(Mandatory = $true)][string[]]$Headers,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    $matches = @($Headers | Where-Object {
        [string]::Equals($_.TrimStart([char]0xFEFF), $Expected, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one '$Expected' column, found $($matches.Count)."
    }
    return $matches[0]
}

function Get-CellValue {
    param(
        [Parameter(Mandatory = $true)][object]$Row,
        [Parameter(Mandatory = $true)][string]$Column
    )

    $property = $Row.PSObject.Properties[$Column]
    if ($null -eq $property -or $null -eq $property.Value) {
        return ""
    }
    return [string]$property.Value
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )

    if ($Values.Count -eq 0) {
        throw "Cannot calculate a percentile from an empty sample."
    }
    [double[]]$sorted = @($Values | Sort-Object)
    $index = [int][math]::Ceiling($Percentile * $sorted.Count) - 1
    $index = [math]::Max(0, [math]::Min($sorted.Count - 1, $index))
    return $sorted[$index]
}

function Get-LowFps {
    param(
        [Parameter(Mandatory = $true)][double[]]$FrameTimesMs,
        [Parameter(Mandatory = $true)][ValidateRange(0.000001, 1.0)][double]$WorstFraction
    )

    [double[]]$descending = @($FrameTimesMs | Sort-Object -Descending)
    $count = [math]::Max(1, [int][math]::Ceiling($descending.Count * $WorstFraction))
    $sum = 0.0
    for ($index = 0; $index -lt $count; $index++) {
        $sum += $descending[$index]
    }
    return 1000.0 / ($sum / $count)
}

function Round-Metric {
    param([Parameter(Mandatory = $true)][double]$Value)
    return [math]::Round($Value, 6, [MidpointRounding]::AwayFromZero)
}

function Write-JsonResult {
    param([Parameter(Mandatory = $true)][object]$Value)

    $json = $Value | ConvertTo-Json -Depth 10
    if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
        $fullOutputPath = [IO.Path]::GetFullPath($OutputJson)
        if ((Test-Path -LiteralPath $fullOutputPath) -and -not $Overwrite) {
            throw "JSON output already exists: $fullOutputPath. Pass -Overwrite only after verifying the target."
        }
        $parent = Split-Path -Parent $fullOutputPath
        if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
            throw "JSON output directory does not exist: $parent"
        }
        Set-Content -LiteralPath $fullOutputPath -Value $json -Encoding UTF8
    }
    Write-Output $json
}

function Get-ProfilingCsvText {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Minecraft debug profile input does not exist: $fullPath"
    }

    $extension = [IO.Path]::GetExtension($fullPath)
    if ([string]::Equals($extension, ".csv", [StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{
            SourcePath = $fullPath
            EntryPath = $null
            Sha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
            CsvText = Get-Content -LiteralPath $fullPath -Raw -Encoding UTF8
        }
    }
    if (-not [string]::Equals($extension, ".zip", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Input must be a Minecraft profiling .zip or ticking.csv file: $fullPath"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($fullPath)
    try {
        $matches = @($archive.Entries | Where-Object {
            $normalized = $_.FullName.Replace([char]92, [char]47)
            $normalized -match '(?i)(^|/)client/metrics/ticking\.csv$'
        })
        if ($matches.Count -ne 1) {
            throw "Expected exactly one client/metrics/ticking.csv in '$fullPath', found $($matches.Count)."
        }
        $entry = $matches[0]
        $stream = $entry.Open()
        try {
            $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false, $true), $true)
            try {
                $csvText = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
        return [pscustomobject]@{
            SourcePath = $fullPath
            EntryPath = $entry.FullName
            Sha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
            CsvText = $csvText
        }
    } finally {
        $archive.Dispose()
    }
}

function Convert-CsvTextToSamples {
    param([Parameter(Mandatory = $true)][string]$CsvText)

    $rows = @($CsvText | ConvertFrom-Csv)
    if ($rows.Count -eq 0) {
        throw "Minecraft ticking CSV has no data rows."
    }
    [string[]]$headers = @($rows[0].PSObject.Properties | ForEach-Object { $_.Name })
    $tickColumn = Resolve-Column -Headers $headers -Expected "@tick"
    $timeColumn = Resolve-Column -Headers $headers -Expected "ticktime"

    $samples = New-Object 'System.Collections.Generic.List[object]'
    [long]$previousTick = -1
    for ($index = 0; $index -lt $rows.Count; $index++) {
        $lineNumber = $index + 2
        [long]$tick = 0
        $rawTick = (Get-CellValue -Row $rows[$index] -Column $tickColumn).Trim()
        if (-not [long]::TryParse($rawTick, [Globalization.NumberStyles]::Integer,
                $script:InvariantCulture, [ref]$tick)) {
            throw "Line $lineNumber column '$tickColumn' is not an integer tick index: '$rawTick'."
        }
        if ($previousTick -ge 0 -and $tick -le $previousTick) {
            throw "Tick indices must be strictly increasing; line $lineNumber has $tick after $previousTick."
        }

        [double]$nanoseconds = 0.0
        $rawTime = (Get-CellValue -Row $rows[$index] -Column $timeColumn).Trim()
        if (-not [double]::TryParse($rawTime, $script:NumberStyles, $script:InvariantCulture,
                [ref]$nanoseconds) -or [double]::IsNaN($nanoseconds) -or
                [double]::IsInfinity($nanoseconds) -or $nanoseconds -le 0.0) {
            throw "Line $lineNumber column '$timeColumn' must be a positive finite nanosecond value: '$rawTime'."
        }
        $samples.Add([pscustomobject]@{
            Tick = $tick
            FrameTimeMs = $nanoseconds / 1000000.0
        })
        $previousTick = $tick
    }
    return $samples
}

function Get-DebugProfileResult {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][double]$TrimStart,
        [Parameter(Mandatory = $true)][double]$TrimEnd,
        [object]$Source
    )

    if ($Samples.Count -eq 0) {
        throw "No client-loop samples are available."
    }
    $totalDurationSeconds = (($Samples | ForEach-Object { $_.FrameTimeMs } | Measure-Object -Sum).Sum) / 1000.0
    $windowEnd = $totalDurationSeconds - $TrimEnd
    if ($windowEnd -lt $TrimStart) {
        throw "Head/tail trimming removes the entire profile: total=$totalDurationSeconds seconds."
    }

    $selected = New-Object 'System.Collections.Generic.List[object]'
    $elapsedSeconds = 0.0
    foreach ($sample in $Samples) {
        $sampleStart = $elapsedSeconds
        $sampleEnd = $elapsedSeconds + ($sample.FrameTimeMs / 1000.0)
        if ($sampleStart -ge $TrimStart -and $sampleEnd -le $windowEnd) {
            $selected.Add($sample)
        }
        $elapsedSeconds = $sampleEnd
    }
    if ($selected.Count -eq 0) {
        throw "No complete client-loop samples remain after trimming."
    }

    [double[]]$frameTimes = @($selected | ForEach-Object { [double]$_.FrameTimeMs })
    $analyzedDurationSeconds = (($frameTimes | Measure-Object -Sum).Sum) / 1000.0
    $firstTick = [long]$Samples[0].Tick
    $lastTick = [long]$Samples[$Samples.Count - 1].Tick
    $expectedSamples = $lastTick - $firstTick + 1
    $missingSamples = [math]::Max(0, $expectedSamples - $Samples.Count)

    $sourceValue = if ($null -eq $Source) {
        $null
    } else {
        [ordered]@{
            path = $Source.SourcePath
            zipEntry = $Source.EntryPath
            sha256 = $Source.Sha256
        }
    }

    return [ordered]@{
        schemaVersion = 1
        analyzer = "analyze-minecraft-debug-profile.ps1"
        measurement = "vanilla-client-main-loop-ticktime"
        source = $sourceValue
        unitConversion = "ticktime nanoseconds / 1,000,000 = milliseconds"
        evidenceBoundary = "Client main-loop duration including rendering/FPS limiting; not OS compositor displayed time, TCP bytes, or dropped-frame evidence."
        filters = [ordered]@{
            trimStartSeconds = $TrimStart
            trimEndSeconds = $TrimEnd
        }
        rawSampleCount = $Samples.Count
        sampleCount = $frameTimes.Count
        firstTick = $firstTick
        lastTick = $lastTick
        missingSampleCount = $missingSamples
        rawDurationSeconds = Round-Metric $totalDurationSeconds
        durationSeconds = Round-Metric $analyzedDurationSeconds
        clientMainLoopFps = Round-Metric ($frameTimes.Count / $analyzedDurationSeconds)
        p50FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameTimes -Percentile 0.50)
        p95FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameTimes -Percentile 0.95)
        p99FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameTimes -Percentile 0.99)
        onePercentLowFps = Round-Metric (Get-LowFps -FrameTimesMs $frameTimes -WorstFraction 0.01)
        pointOnePercentLowFps = Round-Metric (Get-LowFps -FrameTimesMs $frameTimes -WorstFraction 0.001)
        percentileFormula = "nearest-rank over ascending vanilla client main-loop ticktime"
        lowFormula = "1000 / mean(worst ceil(N * fraction) client main-loop times in ms)"
    }
}

function Assert-Close {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][double]$Actual,
        [Parameter(Mandatory = $true)][double]$Expected,
        [double]$Tolerance = 0.000001
    )
    if ([math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "Self-test '$Name' failed: actual=$Actual expected=$Expected tolerance=$Tolerance"
    }
}

function Invoke-AnalyzerSelfTest {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add('@tick,ticktime')
    for ($index = 1; $index -le 200; $index++) {
        $lines.Add("$index,$($index * 1000000)")
    }
    $samples = @(Convert-CsvTextToSamples -CsvText ($lines -join "`n"))
    $result = Get-DebugProfileResult -Samples $samples -TrimStart 0.0 -TrimEnd 0.0

    if ($result.rawSampleCount -ne 200 -or $result.missingSampleCount -ne 0) {
        throw "Self-test sample accounting failed."
    }
    Assert-Close -Name "p50-nearest-rank" -Actual $result.p50FrameTimeMs -Expected 100.0
    Assert-Close -Name "p95-nearest-rank" -Actual $result.p95FrameTimeMs -Expected 190.0
    Assert-Close -Name "p99-nearest-rank" -Actual $result.p99FrameTimeMs -Expected 198.0
    Assert-Close -Name "main-loop-fps" -Actual $result.clientMainLoopFps -Expected (200.0 / 20.1)

    Write-JsonResult ([ordered]@{
        selfTest = $true
        passed = $true
        sampleCount = $result.sampleCount
        p50FrameTimeMs = $result.p50FrameTimeMs
        p95FrameTimeMs = $result.p95FrameTimeMs
        p99FrameTimeMs = $result.p99FrameTimeMs
        clientMainLoopFps = $result.clientMainLoopFps
        evidenceBoundaryRecorded = -not [string]::IsNullOrWhiteSpace($result.evidenceBoundary)
    })
}

if ($SelfTest) {
    Invoke-AnalyzerSelfTest
    return
}
if ([string]::IsNullOrWhiteSpace($InputPath)) {
    throw "-InputPath is required unless -SelfTest is used."
}

$source = Get-ProfilingCsvText -Path $InputPath
$samples = @(Convert-CsvTextToSamples -CsvText $source.CsvText)
$result = Get-DebugProfileResult -Samples $samples -TrimStart $TrimStartSeconds `
        -TrimEnd $TrimEndSeconds -Source $source
Write-JsonResult $result
