<#
.SYNOPSIS
Validates and analyzes a same-stack, restart-per-run Phase 2 ABBA campaign.

.DESCRIPTION
Consumes a CSV manifest with one row per independent run. Every formal campaign
contains three four-run blocks in ABBA, BAAB, ABBA order. Adjacent runs are
paired, B/A log-ratios are summarized by their median, and a deterministic
paired bootstrap produces a 95% confidence interval.

Required manifest columns:
Scenario,Block,Position,Variant,RunId,StackSha256,ArtifactSha256,CaptureMethod,SourcePath

SourcePath may point to a metrics JSON file (IV_PERF, PresentMon, or the vanilla
debug-profile analyzer) or a server log containing exactly one IV_PERF JSON with
the matching RunId label. Relative paths are resolved from the manifest folder.

.EXAMPLE
.\tools\perf\analyze-phase2-abba.ps1 .\S2-manifest.csv -Metric msptP95 `
    -Direction LowerIsBetter -MinimumSeconds 170 -OutputJson .\S2-msptP95.json

.EXAMPLE
.\tools\perf\analyze-phase2-abba.ps1 -SelfTest
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$InputManifest,

    [string]$Scenario,

    [string]$Metric,

    [ValidateSet("LowerIsBetter", "HigherIsBetter")]
    [string]$Direction = "LowerIsBetter",

    [ValidateRange(0.0, 86400.0)]
    [double]$MinimumSeconds = 0.0,

    [ValidateRange(1000, 1000000)]
    [int]$BootstrapIterations = 20000,

    [int]$Seed = 20260713,

    [switch]$AllowIncomplete,

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
        throw "Manifest must contain exactly one '$Expected' column; found $($matches.Count)."
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

function Get-Property {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [switch]$Optional
    )

    $matches = @($Object.PSObject.Properties | Where-Object {
        [string]::Equals($_.Name, $Name, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($matches.Count -eq 0 -and $Optional) {
        return $null
    }
    if ($matches.Count -ne 1) {
        throw "Expected exactly one JSON property '$Name', found $($matches.Count)."
    }
    return $matches[0].Value
}

function Get-MetricValue {
    param(
        [Parameter(Mandatory = $true)][object]$Metrics,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RunId
    )

    $current = $Metrics
    foreach ($segment in $Path.Split('.')) {
        if ([string]::IsNullOrWhiteSpace($segment)) {
            throw "Metric path '$Path' contains an empty segment."
        }
        $current = Get-Property -Object $current -Name $segment
    }
    [double]$value = 0.0
    if (-not [double]::TryParse(([string]$current).Trim(), $script:NumberStyles,
            $script:InvariantCulture, [ref]$value) -or [double]::IsNaN($value) -or
            [double]::IsInfinity($value) -or $value -le 0.0) {
        throw "Run '$RunId' metric '$Path' must be a positive finite number, but was '$current'."
    }
    return $value
}

function Assert-MetricsIntegrity {
    param(
        [Parameter(Mandatory = $true)][object]$Metrics,
        [Parameter(Mandatory = $true)][string]$RunId
    )

    $guards = @(
        @{ Name = "droppedTickSamples"; Description = "dropped tick samples" },
        @{ Name = "missingSampleCount"; Description = "missing client-loop samples" },
        @{ Name = "jfrDataLossBytes"; Description = "JFR data-loss bytes" }
    )
    foreach ($guard in $guards) {
        $value = Get-Property -Object $Metrics -Name $guard.Name -Optional
        if ($null -ne $value -and [long]$value -ne 0) {
            throw "Run '$RunId' has $($guard.Name)=$value ($($guard.Description)); it is not valid formal evidence."
        }
    }
}

function Read-RunMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RunId
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Metrics source for run '$RunId' does not exist: $Path"
    }
    $extension = [IO.Path]::GetExtension($Path)
    if ([string]::Equals($extension, ".json", [StringComparison]::OrdinalIgnoreCase)) {
        try {
            $metrics = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
        } catch {
            throw "Metrics JSON for run '$RunId' is invalid: $Path. $($_.Exception.Message)"
        }
    } else {
        $matches = New-Object 'System.Collections.Generic.List[object]'
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
            $lineNumber++
            $match = [regex]::Match($line, 'IV_PERF\s+(?<json>\{.*\})\s*$')
            if (-not $match.Success) {
                continue
            }
            try {
                $candidate = $match.Groups['json'].Value | ConvertFrom-Json
            } catch {
                throw "Invalid IV_PERF JSON in '$Path' line $lineNumber. $($_.Exception.Message)"
            }
            $candidateLabel = Get-Property -Object $candidate -Name "label" -Optional
            if ($null -ne $candidateLabel -and
                    [string]::Equals([string]$candidateLabel, $RunId, [StringComparison]::Ordinal)) {
                $matches.Add($candidate)
            }
        }
        if ($matches.Count -ne 1) {
            throw "Expected exactly one IV_PERF record labelled '$RunId' in '$Path', found $($matches.Count)."
        }
        $metrics = $matches[0]
    }

    $label = Get-Property -Object $metrics -Name "label" -Optional
    if ($null -ne $label -and -not [string]::Equals([string]$label, $RunId, [StringComparison]::Ordinal)) {
        throw "Metrics label '$label' does not match manifest RunId '$RunId'."
    }

    Assert-MetricsIntegrity -Metrics $metrics -RunId $RunId
    $jfrDataLoss = Get-Property -Object $metrics -Name "jfrDataLossBytes" -Optional
    if ($null -ne $jfrDataLoss -and [long]$jfrDataLoss -ne 0) {
        throw "Run '$RunId' has jfrDataLossBytes=$jfrDataLoss; it is not valid formal evidence."
    }
    if ($MinimumSeconds -gt 0.0) {
        $seconds = Get-Property -Object $metrics -Name "durationSeconds" -Optional
        if ($null -eq $seconds) {
            $seconds = Get-Property -Object $metrics -Name "seconds" -Optional
        }
        if ($null -eq $seconds -or [double]$seconds -lt $MinimumSeconds) {
            throw "Run '$RunId' duration '$seconds' is below MinimumSeconds=$MinimumSeconds."
        }
    }
    return $metrics
}

function Get-Median {
    param([Parameter(Mandatory = $true)][double[]]$Values)

    if ($Values.Count -eq 0) {
        throw "Cannot calculate a median from an empty sample."
    }
    [double[]]$sorted = @($Values | Sort-Object)
    $middle = [int][math]::Floor($sorted.Count / 2.0)
    if (($sorted.Count % 2) -eq 1) {
        return $sorted[$middle]
    }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )

    [double[]]$sorted = @($Values | Sort-Object)
    $index = [int][math]::Ceiling($Percentile * $sorted.Count) - 1
    $index = [math]::Max(0, [math]::Min($sorted.Count - 1, $index))
    return $sorted[$index]
}

function Get-BootstrapSummary {
    param(
        [Parameter(Mandatory = $true)][double[]]$LogRatios,
        [Parameter(Mandatory = $true)][int]$Iterations,
        [Parameter(Mandatory = $true)][int]$RandomSeed
    )

    $random = [Random]::new($RandomSeed)
    $bootstrap = New-Object 'double[]' $Iterations
    for ($iteration = 0; $iteration -lt $Iterations; $iteration++) {
        $sample = New-Object 'double[]' $LogRatios.Count
        for ($index = 0; $index -lt $LogRatios.Count; $index++) {
            $sample[$index] = $LogRatios[$random.Next($LogRatios.Count)]
        }
        $bootstrap[$iteration] = Get-Median -Values $sample
    }
    return [pscustomobject]@{
        Median = Get-Median -Values $LogRatios
        Lower = Get-NearestRankPercentile -Values $bootstrap -Percentile 0.025
        Upper = Get-NearestRankPercentile -Values $bootstrap -Percentile 0.975
    }
}

function Round-Metric {
    param([Parameter(Mandatory = $true)][double]$Value)
    return [math]::Round($Value, 6, [MidpointRounding]::AwayFromZero)
}

function Write-JsonResult {
    param([Parameter(Mandatory = $true)][object]$Value)

    $json = $Value | ConvertTo-Json -Depth 12
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

function Convert-ManifestRows {
    param(
        [Parameter(Mandatory = $true)][object[]]$Rows,
        [Parameter(Mandatory = $true)][string]$ManifestDirectory
    )

    if ($Rows.Count -eq 0) {
        throw "ABBA manifest has no data rows."
    }
    [string[]]$headers = @($Rows[0].PSObject.Properties | ForEach-Object { $_.Name })
    $required = @("Scenario", "Block", "Position", "Variant", "RunId", "StackSha256",
        "ArtifactSha256", "CaptureMethod", "SourcePath")
    $columns = @{}
    foreach ($name in $required) {
        $columns[$name] = Resolve-Column -Headers $headers -Expected $name
    }

    $runs = New-Object 'System.Collections.Generic.List[object]'
    $runIds = @{}
    for ($index = 0; $index -lt $Rows.Count; $index++) {
        $rowNumber = $index + 2
        $row = $Rows[$index]
        $scenarioValue = (Get-CellValue -Row $row -Column $columns["Scenario"]).Trim()
        $runId = (Get-CellValue -Row $row -Column $columns["RunId"]).Trim()
        $variant = (Get-CellValue -Row $row -Column $columns["Variant"]).Trim().ToUpperInvariant()
        $stackHash = (Get-CellValue -Row $row -Column $columns["StackSha256"]).Trim().ToLowerInvariant()
        $artifactHash = (Get-CellValue -Row $row -Column $columns["ArtifactSha256"]).Trim().ToLowerInvariant()
        $captureMethod = (Get-CellValue -Row $row -Column $columns["CaptureMethod"]).Trim()
        $sourceValue = (Get-CellValue -Row $row -Column $columns["SourcePath"]).Trim()
        if ([string]::IsNullOrWhiteSpace($scenarioValue) -or [string]::IsNullOrWhiteSpace($runId) -or
                [string]::IsNullOrWhiteSpace($captureMethod) -or [string]::IsNullOrWhiteSpace($sourceValue)) {
            throw "Manifest row $rowNumber has an empty required text field."
        }
        if ($variant -ne "A" -and $variant -ne "B") {
            throw "Manifest row $rowNumber Variant must be A or B, but was '$variant'."
        }
        if ($stackHash -notmatch '^[0-9a-f]{64}$' -or $artifactHash -notmatch '^[0-9a-f]{64}$') {
            throw "Manifest row $rowNumber stack/artifact hashes must be 64 hexadecimal SHA-256 values."
        }
        if ($runIds.ContainsKey($runId)) {
            throw "Manifest RunId '$runId' is duplicated at rows $($runIds[$runId]) and $rowNumber."
        }
        $runIds[$runId] = $rowNumber

        [int]$block = 0
        [int]$position = 0
        if (-not [int]::TryParse((Get-CellValue -Row $row -Column $columns["Block"]).Trim(),
                [Globalization.NumberStyles]::Integer, $script:InvariantCulture, [ref]$block) -or $block -lt 1) {
            throw "Manifest row $rowNumber Block must be a positive integer."
        }
        if (-not [int]::TryParse((Get-CellValue -Row $row -Column $columns["Position"]).Trim(),
                [Globalization.NumberStyles]::Integer, $script:InvariantCulture, [ref]$position) -or
                $position -lt 1 -or $position -gt 4) {
            throw "Manifest row $rowNumber Position must be 1..4."
        }
        $sourcePath = if ([IO.Path]::IsPathRooted($sourceValue)) {
            [IO.Path]::GetFullPath($sourceValue)
        } else {
            [IO.Path]::GetFullPath((Join-Path $ManifestDirectory $sourceValue))
        }
        $runs.Add([pscustomobject]@{
            Scenario = $scenarioValue
            Block = $block
            Position = $position
            Variant = $variant
            RunId = $runId
            StackSha256 = $stackHash
            ArtifactSha256 = $artifactHash
            CaptureMethod = $captureMethod
            SourcePath = $sourcePath
        })
    }
    return $runs
}

function Get-ScenarioResult {
    param(
        [Parameter(Mandatory = $true)][object[]]$Runs,
        [Parameter(Mandatory = $true)][string]$ScenarioName,
        [Parameter(Mandatory = $true)][string]$MetricPath,
        [Parameter(Mandatory = $true)][string]$MetricDirection,
        [Parameter(Mandatory = $true)][bool]$PermitIncomplete,
        [Parameter(Mandatory = $true)][int]$Iterations,
        [Parameter(Mandatory = $true)][int]$RandomSeed,
        [switch]$Prepared
    )

    $stackHashes = @($Runs.StackSha256 | Sort-Object -Unique)
    $captureMethods = @($Runs.CaptureMethod | Sort-Object -Unique)
    if ($stackHashes.Count -ne 1) {
        throw "Scenario '$ScenarioName' mixes $($stackHashes.Count) StackSha256 values."
    }
    if ($captureMethods.Count -ne 1) {
        throw "Scenario '$ScenarioName' mixes capture methods: $($captureMethods -join ', ')."
    }
    foreach ($variant in @("A", "B")) {
        $hashes = @($Runs | Where-Object Variant -eq $variant | ForEach-Object ArtifactSha256 | Sort-Object -Unique)
        if ($hashes.Count -ne 1) {
            throw "Scenario '$ScenarioName' variant $variant must use one artifact SHA-256, found $($hashes.Count)."
        }
    }

    $blocks = @($Runs | Group-Object Block | Sort-Object { [int]$_.Name })
    if (-not $PermitIncomplete -and $blocks.Count -ne 3) {
        throw "Formal scenario '$ScenarioName' requires exactly three four-run blocks; found $($blocks.Count)."
    }
    if (-not $PermitIncomplete -and (@($blocks | ForEach-Object { [int]$_.Name }) -join ',') -ne '1,2,3') {
        throw "Formal scenario '$ScenarioName' block numbers must be exactly 1,2,3."
    }
    if ($blocks.Count -eq 0) {
        throw "Scenario '$ScenarioName' has no blocks."
    }

    $pairs = New-Object 'System.Collections.Generic.List[object]'
    $evidenceRuns = New-Object 'System.Collections.Generic.List[object]'
    for ($blockIndex = 0; $blockIndex -lt $blocks.Count; $blockIndex++) {
        $group = $blocks[$blockIndex]
        $ordered = @($group.Group | Sort-Object Position)
        if ($ordered.Count -ne 4 -or (@($ordered.Position | Sort-Object -Unique) -join '') -ne '1234') {
            throw "Scenario '$ScenarioName' block $($group.Name) must contain positions 1,2,3,4 exactly once."
        }
        $pattern = ($ordered.Variant -join '')
        $expectedPattern = if (($blockIndex % 2) -eq 0) { "ABBA" } else { "BAAB" }
        if (-not $PermitIncomplete -and $pattern -ne $expectedPattern) {
            throw "Scenario '$ScenarioName' block $($group.Name) pattern is $pattern; expected $expectedPattern."
        }
        if ($PermitIncomplete -and $pattern -ne "ABBA" -and $pattern -ne "BAAB") {
            throw "Exploratory block $($group.Name) must still be ABBA or BAAB, but was $pattern."
        }

        foreach ($run in $ordered) {
            if ($Prepared) {
                $metricsValue = $run.MetricValue
                $sourceHash = $run.SourceSha256
            } else {
                $metrics = Read-RunMetrics -Path $run.SourcePath -RunId $run.RunId
                $metricsValue = Get-MetricValue -Metrics $metrics -Path $MetricPath -RunId $run.RunId
                $sourceHash = (Get-FileHash -LiteralPath $run.SourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
            $run | Add-Member -NotePropertyName ResolvedMetricValue -NotePropertyValue $metricsValue -Force
            $evidenceRuns.Add([ordered]@{
                block = $run.Block
                position = $run.Position
                variant = $run.Variant
                runId = $run.RunId
                value = Round-Metric $metricsValue
                sourcePath = $run.SourcePath
                sourceSha256 = $sourceHash
            })
        }

        foreach ($indices in @(@(0, 1), @(2, 3))) {
            $left = $ordered[$indices[0]]
            $right = $ordered[$indices[1]]
            $aRun = if ($left.Variant -eq "A") { $left } else { $right }
            $bRun = if ($left.Variant -eq "B") { $left } else { $right }
            $ratio = $bRun.ResolvedMetricValue / $aRun.ResolvedMetricValue
            $pairs.Add([pscustomobject]@{
                Block = [int]$group.Name
                Positions = "$($indices[0] + 1)-$($indices[1] + 1)"
                ARunId = $aRun.RunId
                BRunId = $bRun.RunId
                AValue = [double]$aRun.ResolvedMetricValue
                BValue = [double]$bRun.ResolvedMetricValue
                Ratio = $ratio
                LogRatio = [math]::Log($ratio)
            })
        }
    }

    [double[]]$logRatios = @($pairs | ForEach-Object { [double]$_.LogRatio })
    $bootstrap = Get-BootstrapSummary -LogRatios $logRatios -Iterations $Iterations -RandomSeed $RandomSeed
    $medianRatio = [math]::Exp($bootstrap.Median)
    $lowerRatio = [math]::Exp($bootstrap.Lower)
    $upperRatio = [math]::Exp($bootstrap.Upper)
    if ($MetricDirection -eq "LowerIsBetter") {
        $improvement = (1.0 - $medianRatio) * 100.0
        $improvementLower = (1.0 - $upperRatio) * 100.0
        $improvementUpper = (1.0 - $lowerRatio) * 100.0
    } else {
        $improvement = ($medianRatio - 1.0) * 100.0
        $improvementLower = ($lowerRatio - 1.0) * 100.0
        $improvementUpper = ($upperRatio - 1.0) * 100.0
    }

    return [ordered]@{
        scenario = $ScenarioName
        metric = $MetricPath
        direction = $MetricDirection
        formalComplete = (-not $PermitIncomplete -and $blocks.Count -eq 3)
        stackSha256 = $stackHashes[0]
        artifactSha256 = [ordered]@{
            A = @($Runs | Where-Object Variant -eq "A" | Select-Object -First 1)[0].ArtifactSha256
            B = @($Runs | Where-Object Variant -eq "B" | Select-Object -First 1)[0].ArtifactSha256
        }
        captureMethod = $captureMethods[0]
        runCount = $Runs.Count
        pairCount = $pairs.Count
        medianBRatioToA = Round-Metric $medianRatio
        ratioBootstrap95Ci = @((Round-Metric $lowerRatio), (Round-Metric $upperRatio))
        improvementPercent = Round-Metric $improvement
        improvementBootstrap95CiPercent = @((Round-Metric $improvementLower), (Round-Metric $improvementUpper))
        pairs = @($pairs.ToArray() | ForEach-Object {
            [ordered]@{
                block = $_.Block
                positions = $_.Positions
                aRunId = $_.ARunId
                bRunId = $_.BRunId
                aValue = Round-Metric $_.AValue
                bValue = Round-Metric $_.BValue
                bRatioToA = Round-Metric $_.Ratio
            }
        })
        runs = @($evidenceRuns.ToArray())
    }
}

function Invoke-AnalyzerSelfTest {
    $runs = New-Object 'System.Collections.Generic.List[object]'
    $patterns = @("ABBA", "BAAB", "ABBA")
    for ($block = 1; $block -le 3; $block++) {
        for ($position = 1; $position -le 4; $position++) {
            $variant = [string]$patterns[$block - 1][$position - 1]
            $value = if ($variant -eq "A") { 10.0 } else { 8.0 }
            $runs.Add([pscustomobject]@{
                Scenario = "SELFTEST"
                Block = $block
                Position = $position
                Variant = $variant
                RunId = "SELFTEST_${block}_${position}_$variant"
                StackSha256 = ('1' * 64)
                ArtifactSha256 = if ($variant -eq "A") { ('a' * 64) } else { ('b' * 64) }
                CaptureMethod = "selftest"
                SourcePath = $null
                SourceSha256 = ('c' * 64)
                MetricValue = $value
            })
        }
    }
    $result = Get-ScenarioResult -Runs $runs.ToArray() -ScenarioName "SELFTEST" `
        -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
        -Iterations 1000 -RandomSeed 7 -Prepared
    if (-not $result.formalComplete -or $result.runCount -ne 12 -or $result.pairCount -ne 6) {
        throw "Self-test formal campaign accounting failed."
    }
    if ([math]::Abs($result.medianBRatioToA - 0.8) -gt 0.000001 -or
            [math]::Abs($result.improvementPercent - 20.0) -gt 0.000001) {
        throw "Self-test paired log-ratio failed."
    }
    $rejectedJfrDataLoss = $false
    try {
        Assert-MetricsIntegrity -Metrics ([pscustomobject]@{ jfrDataLossBytes = 1 }) -RunId "SELFTEST_BAD_JFR"
    } catch {
        $rejectedJfrDataLoss = $true
    }
    if (-not $rejectedJfrDataLoss) {
        throw "Self-test failed to reject non-zero jfrDataLossBytes."
    }
    Write-JsonResult ([ordered]@{
        selfTest = $true
        passed = $true
        runCount = $result.runCount
        pairCount = $result.pairCount
        medianBRatioToA = $result.medianBRatioToA
        ratioBootstrap95Ci = $result.ratioBootstrap95Ci
        improvementPercent = $result.improvementPercent
        enforcesFormalOrder = $true
        enforcesSameStackAndArtifacts = $true
        rejectsJfrDataLoss = $rejectedJfrDataLoss
    })
}

if ($SelfTest) {
    Invoke-AnalyzerSelfTest
    return
}
if ([string]::IsNullOrWhiteSpace($InputManifest) -or [string]::IsNullOrWhiteSpace($Metric)) {
    throw "-InputManifest and -Metric are required unless -SelfTest is used."
}

$manifestPath = [IO.Path]::GetFullPath($InputManifest)
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "ABBA manifest does not exist: $manifestPath"
}
$manifestRows = @(Import-Csv -LiteralPath $manifestPath)
$runs = @(Convert-ManifestRows -Rows $manifestRows -ManifestDirectory (Split-Path -Parent $manifestPath))
if (-not [string]::IsNullOrWhiteSpace($Scenario)) {
    $runs = @($runs | Where-Object {
        [string]::Equals($_.Scenario, $Scenario, [StringComparison]::Ordinal)
    })
    if ($runs.Count -eq 0) {
        throw "Manifest contains no runs for scenario '$Scenario'."
    }
}

$results = New-Object 'System.Collections.Generic.List[object]'
$scenarioGroups = @($runs | Group-Object Scenario | Sort-Object Name)
for ($index = 0; $index -lt $scenarioGroups.Count; $index++) {
    $group = $scenarioGroups[$index]
    $results.Add((Get-ScenarioResult -Runs @($group.Group) -ScenarioName $group.Name `
        -MetricPath $Metric -MetricDirection $Direction -PermitIncomplete $AllowIncomplete.IsPresent `
        -Iterations $BootstrapIterations -RandomSeed ($Seed + $index)))
}

Write-JsonResult ([ordered]@{
    schemaVersion = 1
    analyzer = "analyze-phase2-abba.ps1"
    manifest = [ordered]@{
        path = $manifestPath
        sha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    metric = $Metric
    direction = $Direction
    minimumSeconds = $MinimumSeconds
    bootstrapIterations = $BootstrapIterations
    seed = $Seed
    pairing = "adjacent positions 1-2 and 3-4 within each restart-per-run block"
    estimator = "exp(median(paired log(B/A)))"
    confidenceInterval = "deterministic paired bootstrap percentile 95% CI"
    results = @($results)
})
