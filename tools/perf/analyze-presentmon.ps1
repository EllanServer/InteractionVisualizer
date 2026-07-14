<#
.SYNOPSIS
Analyzes a PresentMon per-frame CSV and emits JSON using one explicit stream.

.DESCRIPTION
Uses displayed frame duration, not CPU present cadence, to calculate displayed
FPS and frame-time statistics. PresentMon 2.x writes DisplayedTime in
milliseconds and writes NA when a frame was not displayed. Documented legacy
MsBetweenDisplayChange is accepted as a fallback with the same NA requirement.

The input capture must retain dropped rows. Do not record with
--exclude_dropped when droppedRatio is required.

.EXAMPLE
.\tools\perf\analyze-presentmon.ps1 capture.csv -Process javaw.exe `
    -SwapChain 0x00000123456789AB -TrimStartSeconds 10 -TrimEndSeconds 10

.EXAMPLE
.\tools\perf\analyze-presentmon.ps1 -SelfTest
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$InputCsv,

    [string]$Process,

    [string]$SwapChain,

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

function Get-CellValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Row,

        [Parameter(Mandatory = $true)]
        [string]$Column
    )

    $property = $Row.PSObject.Properties[$Column]
    if ($null -eq $property) {
        throw "Internal error: CSV column '$Column' is unavailable on a parsed row."
    }
    if ($null -eq $property.Value) {
        return ""
    }
    return [string]$property.Value
}

function Resolve-Column {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Headers,

        [Parameter(Mandatory = $true)]
        [string]$Role,

        [Parameter(Mandatory = $true)]
        [string[]]$Candidates
    )

    foreach ($candidate in $Candidates) {
        $matches = @($Headers | Where-Object {
            [string]::Equals($_, $candidate, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($matches.Count -gt 1) {
            throw "CSV has multiple case-insensitive matches for $Role column '$candidate'."
        }
        if ($matches.Count -eq 1) {
            return $matches[0]
        }
    }
    throw "Missing required $Role column. Accepted official candidates: $($Candidates -join ', ')."
}

function Resolve-TimestampSpec {
    param([Parameter(Mandatory = $true)][string[]]$Headers)

    # PresentMon 2.5.1 CsvOutput.cpp writes CPUStartTime and CPUStartQPCTime in
    # milliseconds. The other entries are documented 1.x/2.x time variants.
    $specs = @(
        [pscustomobject]@{ Name = "CPUStartTime"; Kind = "numeric"; Scale = 0.001; Unit = "milliseconds"; Variant = "PresentMon 2.x default" },
        [pscustomobject]@{ Name = "CPUStartQPCTime"; Kind = "numeric"; Scale = 0.001; Unit = "milliseconds"; Variant = "PresentMon 2.x --qpc_time_ms" },
        [pscustomobject]@{ Name = "CPUStartTimeInMs"; Kind = "numeric"; Scale = 0.001; Unit = "milliseconds"; Variant = "documented legacy" },
        [pscustomobject]@{ Name = "CPUStartQPCTimeInMs"; Kind = "numeric"; Scale = 0.001; Unit = "milliseconds"; Variant = "documented legacy QPC milliseconds" },
        [pscustomobject]@{ Name = "TimeInMs"; Kind = "numeric"; Scale = 0.001; Unit = "milliseconds"; Variant = "documented legacy" },
        [pscustomobject]@{ Name = "TimeInSeconds"; Kind = "numeric"; Scale = 1.0; Unit = "seconds"; Variant = "documented legacy" },
        [pscustomobject]@{ Name = "CPUStartTimeInSeconds"; Kind = "numeric"; Scale = 1.0; Unit = "seconds"; Variant = "documented legacy" },
        [pscustomobject]@{ Name = "CPUStartDateTime"; Kind = "datetime"; Scale = 1.0; Unit = "local datetime"; Variant = "PresentMon --date_time" },
        [pscustomobject]@{ Name = "TimeInDateTime"; Kind = "datetime"; Scale = 1.0; Unit = "local datetime"; Variant = "documented legacy --date_time" }
    )

    foreach ($spec in $specs) {
        $matches = @($Headers | Where-Object {
            [string]::Equals($_, $spec.Name, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($matches.Count -gt 1) {
            throw "CSV has multiple case-insensitive matches for timestamp column '$($spec.Name)'."
        }
        if ($matches.Count -eq 1) {
            return [pscustomobject]@{
                Name = $matches[0]
                Kind = $spec.Kind
                Scale = $spec.Scale
                Unit = $spec.Unit
                Variant = $spec.Variant
            }
        }
    }

    $rawQpcColumns = @("CPUStartQPC", "TimeInQPC", "Present Start QPC")
    foreach ($column in $rawQpcColumns) {
        if (@($Headers | Where-Object {
            [string]::Equals($_, $column, [StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0) {
            throw "CSV only exposes raw QPC timestamp '$column'. Its frequency is not in the per-frame CSV, so seconds cannot be inferred. Recapture without --qpc_time or use --qpc_time_ms."
        }
    }

    throw "Missing a supported PresentMon timestamp. Expected CPUStartTime, CPUStartQPCTime, TimeInMs, TimeInSeconds, or a documented DateTime variant."
}

function Resolve-DisplayedFrameTimeSpec {
    param([Parameter(Mandatory = $true)][string[]]$Headers)

    $specs = @(
        [pscustomobject]@{ Name = "DisplayedTime"; Semantic = "displayed-duration"; Variant = "PresentMon 2.x" },
        [pscustomobject]@{ Name = "MsBetweenDisplayChange"; Semantic = "display-change-interval"; Variant = "documented legacy" }
    )
    foreach ($spec in $specs) {
        $matches = @($Headers | Where-Object {
            [string]::Equals($_, $spec.Name, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($matches.Count -gt 1) {
            throw "CSV has multiple case-insensitive matches for displayed frame-time column '$($spec.Name)'."
        }
        if ($matches.Count -eq 1) {
            return [pscustomobject]@{
                Name = $matches[0]
                Semantic = $spec.Semantic
                Variant = $spec.Variant
            }
        }
    }

    $presentOnly = @("FrameTime", "MsBetweenPresents") | Where-Object {
        $candidate = $_
        @($Headers | Where-Object {
            [string]::Equals($_, $candidate, [StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0
    }
    if ($presentOnly.Count -gt 0) {
        throw "Missing displayed frame time. Found $($presentOnly -join ', '), but those measure CPU/present cadence and are intentionally not substituted for DisplayedTime. Recapture with display tracking enabled."
    }
    throw "Missing displayed frame time. Expected PresentMon 2.x DisplayedTime or documented legacy MsBetweenDisplayChange. Recapture with display tracking enabled."
}

function Resolve-SourceFormat {
    param(
        [Parameter(Mandatory = $true)]
        [object]$TimestampSpec,

        [Parameter(Mandatory = $true)]
        [object]$FrameTimeSpec
    )

    if ([string]::Equals($FrameTimeSpec.Name, "DisplayedTime", [StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{
            Family = "presentmon-v2"
            Description = "PresentMon 2.x v2-metrics per-frame CSV"
        }
    }
    if ([string]::Equals($FrameTimeSpec.Name, "MsBetweenDisplayChange", [StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{
            Family = "presentmon-legacy"
            Description = "PresentMon legacy/v1-compatible per-frame CSV"
        }
    }
    throw "Unable to classify PresentMon source format from timestamp '$($TimestampSpec.Name)' and frame-time '$($FrameTimeSpec.Name)'."
}

function Convert-OfficialDateTimeToSeconds {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [int]$LineNumber,

        [Parameter(Mandatory = $true)]
        [string]$Column
    )

    # PresentMon 2.5.1 writes: yyyy-M-d H:mm:ss.fffffffff (local time).
    $match = [regex]::Match(
        $Value.Trim(),
        '^(?<base>\d{4}-\d{1,2}-\d{1,2} \d{1,2}:\d{2}:\d{2})(?:\.(?<fraction>\d{1,9}))?$'
    )
    if (-not $match.Success) {
        throw "Line $LineNumber column '$Column' is not PresentMon's documented local DateTime format: '$Value'."
    }

    [datetime]$baseTime = [datetime]::MinValue
    if (-not [datetime]::TryParseExact(
        $match.Groups["base"].Value,
        "yyyy-M-d H:mm:ss",
        $script:InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeLocal,
        [ref]$baseTime
    )) {
        throw "Line $LineNumber column '$Column' contains an invalid DateTime: '$Value'."
    }

    [long]$fractionTicks = 0
    $fraction = $match.Groups["fraction"].Value
    if ($fraction.Length -gt 0) {
        $nanoseconds = [long]$fraction.PadRight(9, '0')
        $fractionTicks = [long][math]::Floor($nanoseconds / 100.0)
    }
    $offset = [DateTimeOffset]::new($baseTime)
    return ($offset.UtcTicks + $fractionTicks) / [double][TimeSpan]::TicksPerSecond
}

function Convert-TimestampToSeconds {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [object]$Spec,

        [Parameter(Mandatory = $true)]
        [int]$LineNumber
    )

    if ($Spec.Kind -eq "datetime") {
        return Convert-OfficialDateTimeToSeconds -Value $Value -LineNumber $LineNumber -Column $Spec.Name
    }

    [double]$parsed = 0.0
    if (-not [double]::TryParse(
        $Value.Trim(),
        $script:NumberStyles,
        $script:InvariantCulture,
        [ref]$parsed
    ) -or [double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "Line $LineNumber column '$($Spec.Name)' is not a finite invariant-culture number: '$Value'."
    }
    return $parsed * [double]$Spec.Scale
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$Values,

        [Parameter(Mandatory = $true)]
        [ValidateRange(0.0, 1.0)]
        [double]$Percentile
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
        [Parameter(Mandatory = $true)]
        [double[]]$FrameTimesMs,

        [Parameter(Mandatory = $true)]
        [ValidateRange(0.000001, 1.0)]
        [double]$WorstFraction
    )

    if ($FrameTimesMs.Count -eq 0) {
        throw "Cannot calculate low FPS from an empty sample."
    }
    [double[]]$descending = @($FrameTimesMs | Sort-Object -Descending)
    $count = [math]::Max(1, [int][math]::Ceiling($descending.Count * $WorstFraction))
    $sum = 0.0
    for ($index = 0; $index -lt $count; $index++) {
        $sum += $descending[$index]
    }
    $averageWorstFrameTime = $sum / $count
    return 1000.0 / $averageWorstFrameTime
}

function Round-Metric {
    param([Parameter(Mandatory = $true)][double]$Value)
    return [math]::Round($Value, 6, [MidpointRounding]::AwayFromZero)
}

function Write-JsonResult {
    param([Parameter(Mandatory = $true)][object]$Value)

    $json = $Value | ConvertTo-Json -Depth 8
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

function Assert-Close {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][double]$Actual,
        [Parameter(Mandatory = $true)][double]$Expected,
        [double]$Tolerance = 0.000000001
    )
    if ([math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "Self-test '$Name' failed: actual=$Actual expected=$Expected tolerance=$Tolerance"
    }
}

function Invoke-AnalyzerSelfTest {
    [double[]]$values = 1..200
    $p50 = Get-NearestRankPercentile -Values $values -Percentile 0.50
    $p95 = Get-NearestRankPercentile -Values $values -Percentile 0.95
    $p99 = Get-NearestRankPercentile -Values $values -Percentile 0.99
    $onePercentLow = Get-LowFps -FrameTimesMs $values -WorstFraction 0.01
    $pointOnePercentLow = Get-LowFps -FrameTimesMs $values -WorstFraction 0.001

    Assert-Close -Name "p50-nearest-rank" -Actual $p50 -Expected 100.0
    Assert-Close -Name "p95-nearest-rank" -Actual $p95 -Expected 190.0
    Assert-Close -Name "p99-nearest-rank" -Actual $p99 -Expected 198.0
    Assert-Close -Name "one-percent-low" -Actual $onePercentLow -Expected (1000.0 / 199.5)
    Assert-Close -Name "point-one-percent-low" -Actual $pointOnePercentLow -Expected 5.0

    $durationSeconds = (($values | Measure-Object -Sum).Sum) / 1000.0
    $meanFps = $values.Count / $durationSeconds
    Assert-Close -Name "mean-fps-from-displayed-duration" -Actual $meanFps -Expected (200.0 / 20.1)

    $timestampSpec = Resolve-TimestampSpec -Headers @("Application", "CPUStartTime", "DisplayedTime")
    $timestampSeconds = Convert-TimestampToSeconds -Value "1234.5" -Spec $timestampSpec -LineNumber 2
    Assert-Close -Name "v2-cpu-start-milliseconds" -Actual $timestampSeconds -Expected 1.2345
    $v2Format = Resolve-SourceFormat -TimestampSpec $timestampSpec `
            -FrameTimeSpec (Resolve-DisplayedFrameTimeSpec -Headers @("DisplayedTime"))
    if ($v2Format.Family -ne "presentmon-v2") {
        throw "Self-test failed: DisplayedTime was not classified as PresentMon v2."
    }
    $legacyFormat = Resolve-SourceFormat `
            -TimestampSpec (Resolve-TimestampSpec -Headers @("TimeInMs", "MsBetweenDisplayChange")) `
            -FrameTimeSpec (Resolve-DisplayedFrameTimeSpec -Headers @("MsBetweenDisplayChange"))
    if ($legacyFormat.Family -ne "presentmon-legacy") {
        throw "Self-test failed: MsBetweenDisplayChange was not classified as legacy."
    }

    $presentCadenceRejected = $false
    try {
        $null = Resolve-DisplayedFrameTimeSpec -Headers @("Application", "FrameTime", "MsBetweenPresents")
    } catch {
        $presentCadenceRejected = $_.Exception.Message -match "intentionally not substituted"
    }
    if (-not $presentCadenceRejected) {
        throw "Self-test 'reject-present-cadence-as-display-time' failed."
    }

    Write-JsonResult ([ordered]@{
        selfTest = $true
        passed = $true
        sampleCount = $values.Count
        p50FrameTimeMs = $p50
        p95FrameTimeMs = $p95
        p99FrameTimeMs = $p99
        onePercentLowFps = Round-Metric $onePercentLow
        pointOnePercentLowFps = Round-Metric $pointOnePercentLow
        v2CpuStartMilliseconds = $true
        v2FormatClassification = $v2Format.Description
        legacyFormatClassification = $legacyFormat.Description
        rejectsPresentCadenceAsDisplayTime = $presentCadenceRejected
        lowFormula = "1000 / mean(worst ceil(N * fraction) displayed frame times in ms)"
    })
}

function Invoke-PresentMonAnalysis {
    if ([string]::IsNullOrWhiteSpace($InputCsv)) {
        throw "-InputCsv is required unless -SelfTest is used."
    }
    $inputPath = [IO.Path]::GetFullPath($InputCsv)
    if (-not (Test-Path -LiteralPath $inputPath)) {
        throw "PresentMon CSV does not exist: $inputPath"
    }
    $inputItem = Get-Item -LiteralPath $inputPath
    if ($inputItem.PSIsContainer) {
        throw "PresentMon input is a directory: $inputPath"
    }

    $rows = @(Import-Csv -LiteralPath $inputPath)
    if ($rows.Count -eq 0) {
        throw "PresentMon CSV has no data rows: $inputPath"
    }
    [string[]]$headers = @($rows[0].PSObject.Properties | ForEach-Object { $_.Name })

    $applicationColumn = Resolve-Column -Headers $headers -Role "application" -Candidates @("Application")
    $processIdColumn = Resolve-Column -Headers $headers -Role "process ID" -Candidates @("ProcessID")
    $swapChainColumn = Resolve-Column -Headers $headers -Role "swap chain" -Candidates @("SwapChainAddress")
    $timestampSpec = Resolve-TimestampSpec -Headers $headers
    $frameTimeSpec = Resolve-DisplayedFrameTimeSpec -Headers $headers
    $sourceFormat = Resolve-SourceFormat -TimestampSpec $timestampSpec -FrameTimeSpec $frameTimeSpec

    [long]$wantedProcessId = 0
    $processIsId = -not [string]::IsNullOrWhiteSpace($Process) -and [long]::TryParse(
        $Process.Trim(),
        [Globalization.NumberStyles]::Integer,
        $script:InvariantCulture,
        [ref]$wantedProcessId
    )

    $filtered = New-Object 'System.Collections.Generic.List[object]'
    for ($index = 0; $index -lt $rows.Count; $index++) {
        $row = $rows[$index]
        $lineNumber = $index + 2
        if (-not [string]::IsNullOrWhiteSpace($Process)) {
            if ($processIsId) {
                [long]$rowProcessId = 0
                $rawProcessId = (Get-CellValue -Row $row -Column $processIdColumn).Trim()
                if (-not [long]::TryParse(
                    $rawProcessId,
                    [Globalization.NumberStyles]::Integer,
                    $script:InvariantCulture,
                    [ref]$rowProcessId
                )) {
                    throw "Line $lineNumber column '$processIdColumn' is not an integer process ID: '$rawProcessId'."
                }
                if ($rowProcessId -ne $wantedProcessId) {
                    continue
                }
            } else {
                $application = (Get-CellValue -Row $row -Column $applicationColumn).Trim()
                if (-not [string]::Equals($application, $Process.Trim(), [StringComparison]::OrdinalIgnoreCase)) {
                    continue
                }
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($SwapChain)) {
            $rowSwapChain = (Get-CellValue -Row $row -Column $swapChainColumn).Trim()
            if (-not [string]::Equals($rowSwapChain, $SwapChain.Trim(), [StringComparison]::OrdinalIgnoreCase)) {
                continue
            }
        }
        $filtered.Add([pscustomobject]@{
            Row = $row
            LineNumber = $lineNumber
        })
    }
    if ($filtered.Count -eq 0) {
        throw "No PresentMon rows remain after process/swap-chain filtering."
    }

    $streams = @{}
    foreach ($entry in $filtered) {
        $application = (Get-CellValue -Row $entry.Row -Column $applicationColumn).Trim()
        $processId = (Get-CellValue -Row $entry.Row -Column $processIdColumn).Trim()
        $swapAddress = (Get-CellValue -Row $entry.Row -Column $swapChainColumn).Trim()
        if ([string]::IsNullOrWhiteSpace($application) -or
            [string]::IsNullOrWhiteSpace($processId) -or
            [string]::IsNullOrWhiteSpace($swapAddress)) {
            throw "Line $($entry.LineNumber) has an empty Application, ProcessID, or SwapChainAddress."
        }
        $key = "$application$([char]31)$processId$([char]31)$swapAddress"
        if (-not $streams.ContainsKey($key)) {
            $streams[$key] = [pscustomobject]@{
                Application = $application
                ProcessID = $processId
                SwapChainAddress = $swapAddress
            }
        }
    }
    if ($streams.Count -ne 1) {
        $choices = @($streams.Values | Sort-Object Application, ProcessID, SwapChainAddress | ForEach-Object {
            "Application='$($_.Application)' ProcessID=$($_.ProcessID) SwapChainAddress=$($_.SwapChainAddress)"
        })
        throw "Multiple PresentMon streams remain. Select one with -Process (name or PID) and -SwapChain. Available streams: $($choices -join '; ')"
    }
    $stream = @($streams.Values)[0]

    $records = New-Object 'System.Collections.Generic.List[object]'
    $minimumTimestamp = [double]::PositiveInfinity
    $maximumTimestamp = [double]::NegativeInfinity
    foreach ($entry in $filtered) {
        $timestampRaw = Get-CellValue -Row $entry.Row -Column $timestampSpec.Name
        $timestamp = Convert-TimestampToSeconds -Value $timestampRaw -Spec $timestampSpec -LineNumber $entry.LineNumber
        $minimumTimestamp = [math]::Min($minimumTimestamp, $timestamp)
        $maximumTimestamp = [math]::Max($maximumTimestamp, $timestamp)
        $records.Add([pscustomobject]@{
            Row = $entry.Row
            LineNumber = $entry.LineNumber
            TimestampSeconds = $timestamp
        })
    }

    $windowStart = $minimumTimestamp + $TrimStartSeconds
    $windowEnd = $maximumTimestamp - $TrimEndSeconds
    if ($windowEnd -lt $windowStart) {
        throw "Head/tail trimming removes the entire capture: start=$windowStart end=$windowEnd."
    }

    $selected = @($records | Where-Object {
        $_.TimestampSeconds -ge $windowStart -and $_.TimestampSeconds -le $windowEnd
    })
    if ($selected.Count -eq 0) {
        throw "No rows remain after trimming $TrimStartSeconds seconds from the head and $TrimEndSeconds seconds from the tail."
    }

    $frameTimes = New-Object 'System.Collections.Generic.List[double]'
    $droppedFrames = 0
    foreach ($record in $selected) {
        $raw = (Get-CellValue -Row $record.Row -Column $frameTimeSpec.Name).Trim()
        if ([string]::Equals($raw, "NA", [StringComparison]::OrdinalIgnoreCase)) {
            $droppedFrames++
            continue
        }

        [double]$frameTime = 0.0
        if (-not [double]::TryParse(
            $raw,
            $script:NumberStyles,
            $script:InvariantCulture,
            [ref]$frameTime
        ) -or [double]::IsNaN($frameTime) -or [double]::IsInfinity($frameTime) -or $frameTime -le 0.0) {
            throw "Line $($record.LineNumber) column '$($frameTimeSpec.Name)' must be a positive millisecond value or official NA marker, but was '$raw'."
        }
        $frameTimes.Add($frameTime)
    }
    if ($frameTimes.Count -eq 0) {
        throw "No displayed frames remain after filtering and trimming."
    }

    [double[]]$frameArray = $frameTimes.ToArray()
    $displayedDurationMs = ($frameArray | Measure-Object -Sum).Sum
    if ($displayedDurationMs -le 0.0) {
        throw "Displayed frame duration is not positive."
    }
    $durationSeconds = $displayedDurationMs / 1000.0
    $meanFps = $frameArray.Count / $durationSeconds
    $droppedRatio = $droppedFrames / [double]$selected.Count

    $result = [ordered]@{
        schemaVersion = 1
        analyzer = "analyze-presentmon.ps1"
        source = [ordered]@{
            path = $inputPath
            format = $sourceFormat.Description
            formatFamily = $sourceFormat.Family
            columns = [ordered]@{
                application = $applicationColumn
                processId = $processIdColumn
                swapChain = $swapChainColumn
                timestamp = $timestampSpec.Name
                timestampUnit = $timestampSpec.Unit
                timestampVariant = $timestampSpec.Variant
                displayedFrameTime = $frameTimeSpec.Name
                displayedFrameTimeVariant = $frameTimeSpec.Variant
                displayedFrameTimeSemantic = $frameTimeSpec.Semantic
            }
        }
        filters = [ordered]@{
            process = $Process
            swapChain = $SwapChain
            trimStartSeconds = $TrimStartSeconds
            trimEndSeconds = $TrimEndSeconds
        }
        stream = [ordered]@{
            application = $stream.Application
            processId = $stream.ProcessID
            swapChainAddress = $stream.SwapChainAddress
        }
        rowsAfterProcessSwapChainFilter = $filtered.Count
        rowsAfterTrim = $selected.Count
        displayedFrameCount = $frameArray.Count
        droppedFrameCount = $droppedFrames
        droppedRatio = Round-Metric $droppedRatio
        durationSeconds = Round-Metric $durationSeconds
        timestampSpanSeconds = Round-Metric ($maximumTimestamp - $minimumTimestamp)
        analyzedTimestampSpanSeconds = Round-Metric ($windowEnd - $windowStart)
        meanFps = Round-Metric $meanFps
        p50FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameArray -Percentile 0.50)
        p95FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameArray -Percentile 0.95)
        p99FrameTimeMs = Round-Metric (Get-NearestRankPercentile -Values $frameArray -Percentile 0.99)
        onePercentLowFps = Round-Metric (Get-LowFps -FrameTimesMs $frameArray -WorstFraction 0.01)
        pointOnePercentLowFps = Round-Metric (Get-LowFps -FrameTimesMs $frameArray -WorstFraction 0.001)
        percentileFormula = "nearest-rank over ascending displayed frame times"
        lowFormula = "1000 / mean(worst ceil(N * fraction) displayed frame times in ms)"
    }
    Write-JsonResult $result
}

if ($SelfTest) {
    Invoke-AnalyzerSelfTest
    return
}

Invoke-PresentMonAnalysis
