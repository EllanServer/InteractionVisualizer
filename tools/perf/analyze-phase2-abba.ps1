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

Optional provenance columns (all-or-none):
AbFactor,ConfigSha256,JvmArgumentsSha256,JvmArgumentsNormalizedSha256,
LegacyTextComponentCacheDisableProperty,LegacyTextComponentCacheEnabled

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

function Resolve-OptionalColumn {
    param(
        [Parameter(Mandatory = $true)][string[]]$Headers,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    $matches = @($Headers | Where-Object {
        [string]::Equals($_.TrimStart([char]0xFEFF), $Expected, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($matches.Count -gt 1) {
        throw "Manifest must contain at most one '$Expected' column; found $($matches.Count)."
    }
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[0]
}

function ConvertTo-ManifestBoolean {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][int]$RowNumber
    )

    switch ($Value.Trim().ToLowerInvariant()) {
        "true" { return $true }
        "false" { return $false }
        default { throw "Manifest row $RowNumber $Field must be true or false, but was '$Value'." }
    }
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
    $provenanceNames = @(
        "AbFactor",
        "ConfigSha256",
        "JvmArgumentsSha256",
        "JvmArgumentsNormalizedSha256",
        "LegacyTextComponentCacheDisableProperty",
        "LegacyTextComponentCacheEnabled"
    )
    $provenanceColumns = @{}
    foreach ($name in $provenanceNames) {
        $provenanceColumns[$name] = Resolve-OptionalColumn -Headers $headers -Expected $name
    }
    $presentProvenanceColumns = @($provenanceNames | Where-Object {
        $null -ne $provenanceColumns[$_]
    })
    if ($presentProvenanceColumns.Count -ne 0 -and
            $presentProvenanceColumns.Count -ne $provenanceNames.Count) {
        $missing = @($provenanceNames | Where-Object { $null -eq $provenanceColumns[$_] })
        throw "Manifest provenance columns are all-or-none; missing: $($missing -join ', ')."
    }
    $hasExtendedProvenance = $presentProvenanceColumns.Count -eq $provenanceNames.Count

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

        $abFactor = $null
        $configSha256 = $null
        $jvmArgumentsSha256 = $null
        $jvmArgumentsNormalizedSha256 = $null
        $legacyTextComponentCacheDisableProperty = $null
        $legacyTextComponentCacheEnabled = $null
        if ($hasExtendedProvenance) {
            $abFactor = (Get-CellValue -Row $row -Column $provenanceColumns["AbFactor"]).Trim().ToLowerInvariant()
            if ($abFactor -ne "scenario-config" -and $abFactor -ne "legacy-text-component-cache") {
                throw "Manifest row $rowNumber AbFactor must be scenario-config or legacy-text-component-cache, but was '$abFactor'."
            }
            $configSha256 = (Get-CellValue -Row $row -Column $provenanceColumns["ConfigSha256"]).Trim().ToLowerInvariant()
            $jvmArgumentsSha256 = (Get-CellValue -Row $row -Column $provenanceColumns["JvmArgumentsSha256"]).Trim().ToLowerInvariant()
            $jvmArgumentsNormalizedSha256 = (Get-CellValue -Row $row -Column $provenanceColumns["JvmArgumentsNormalizedSha256"]).Trim().ToLowerInvariant()
            foreach ($hash in @(
                    @{ Name = "ConfigSha256"; Value = $configSha256 },
                    @{ Name = "JvmArgumentsSha256"; Value = $jvmArgumentsSha256 },
                    @{ Name = "JvmArgumentsNormalizedSha256"; Value = $jvmArgumentsNormalizedSha256 }
                )) {
                if ($hash.Value -notmatch '^[0-9a-f]{64}$') {
                    throw "Manifest row $rowNumber $($hash.Name) must be a 64 hexadecimal SHA-256 value."
                }
            }
            $legacyTextComponentCacheDisableProperty = ConvertTo-ManifestBoolean `
                -Value (Get-CellValue -Row $row -Column $provenanceColumns["LegacyTextComponentCacheDisableProperty"]) `
                -Field "LegacyTextComponentCacheDisableProperty" -RowNumber $rowNumber
            $legacyTextComponentCacheEnabled = ConvertTo-ManifestBoolean `
                -Value (Get-CellValue -Row $row -Column $provenanceColumns["LegacyTextComponentCacheEnabled"]) `
                -Field "LegacyTextComponentCacheEnabled" -RowNumber $rowNumber
            if ($legacyTextComponentCacheDisableProperty -eq $legacyTextComponentCacheEnabled) {
                throw "Manifest row $rowNumber cache enabled state must be the inverse of its disable property."
            }
        }

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
            HasExtendedProvenance = $hasExtendedProvenance
            AbFactor = $abFactor
            ConfigSha256 = $configSha256
            JvmArgumentsSha256 = $jvmArgumentsSha256
            JvmArgumentsNormalizedSha256 = $jvmArgumentsNormalizedSha256
            LegacyTextComponentCacheDisableProperty = $legacyTextComponentCacheDisableProperty
            LegacyTextComponentCacheEnabled = $legacyTextComponentCacheEnabled
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

    $provenanceStates = @($Runs | ForEach-Object {
        $property = $_.PSObject.Properties["HasExtendedProvenance"]
        if ($null -eq $property) { $false } else { [bool]$property.Value }
    } | Sort-Object -Unique)
    if ($provenanceStates.Count -ne 1) {
        throw "Scenario '$ScenarioName' mixes legacy and extended provenance rows."
    }
    $hasExtendedProvenance = [bool]$provenanceStates[0]
    $abFactor = $null
    $configHashes = @()
    $configHashesByVariant = [ordered]@{ A = @(); B = @() }
    $jvmArgumentsNormalizedSha256 = $null
    $jvmArgumentsHashesByVariant = [ordered]@{ A = @(); B = @() }
    $legacyTextComponentCacheTreatment = [ordered]@{
        provenancePresent = $hasExtendedProvenance
        propertyName = "interactionvisualizer.disableLegacyTextComponentCache"
        treatmentScope = @("sharedComponentCache", "perEntitySameRawFastPath")
        byVariant = [ordered]@{
            A = [ordered]@{ disableProperty = @(); enabled = @() }
            B = [ordered]@{ disableProperty = @(); enabled = @() }
        }
    }
    if ($hasExtendedProvenance) {
        foreach ($run in $Runs) {
            if ($run.AbFactor -ne "scenario-config" -and
                    $run.AbFactor -ne "legacy-text-component-cache") {
                throw "Scenario '$ScenarioName' run '$($run.RunId)' has invalid AbFactor '$($run.AbFactor)'."
            }
            foreach ($hash in @(
                    @{ Name = "ConfigSha256"; Value = $run.ConfigSha256 },
                    @{ Name = "JvmArgumentsSha256"; Value = $run.JvmArgumentsSha256 },
                    @{ Name = "JvmArgumentsNormalizedSha256"; Value = $run.JvmArgumentsNormalizedSha256 }
                )) {
                if ([string]$hash.Value -notmatch '^[0-9a-f]{64}$') {
                    throw "Scenario '$ScenarioName' run '$($run.RunId)' has invalid $($hash.Name)."
                }
            }
            if ($run.LegacyTextComponentCacheDisableProperty -isnot [bool] -or
                    $run.LegacyTextComponentCacheEnabled -isnot [bool]) {
                throw "Scenario '$ScenarioName' run '$($run.RunId)' cache provenance must use booleans."
            }
            if ($run.LegacyTextComponentCacheDisableProperty -eq
                    $run.LegacyTextComponentCacheEnabled) {
                throw "Scenario '$ScenarioName' run '$($run.RunId)' cache enabled state is not the inverse of its disable property."
            }
        }

        $abFactors = @($Runs.AbFactor | Sort-Object -Unique)
        if ($abFactors.Count -ne 1) {
            throw "Scenario '$ScenarioName' mixes AbFactor values: $($abFactors -join ', ')."
        }
        $abFactor = $abFactors[0]
        $configHashes = @($Runs.ConfigSha256 | Sort-Object -Unique)
        $normalizedJvmHashes = @($Runs.JvmArgumentsNormalizedSha256 | Sort-Object -Unique)
        if ($normalizedJvmHashes.Count -ne 1) {
            throw "Scenario '$ScenarioName' must use exactly one JvmArgumentsNormalizedSha256, found $($normalizedJvmHashes.Count)."
        }
        $jvmArgumentsNormalizedSha256 = $normalizedJvmHashes[0]
        foreach ($variant in @("A", "B")) {
            $configHashesByVariant[$variant] = @($Runs | Where-Object Variant -eq $variant |
                ForEach-Object ConfigSha256 | Sort-Object -Unique)
            $jvmArgumentsHashesByVariant[$variant] = @($Runs | Where-Object Variant -eq $variant |
                ForEach-Object JvmArgumentsSha256 | Sort-Object -Unique)
            $legacyTextComponentCacheTreatment.byVariant[$variant].disableProperty = @($Runs |
                Where-Object Variant -eq $variant |
                ForEach-Object LegacyTextComponentCacheDisableProperty | Sort-Object -Unique)
            $legacyTextComponentCacheTreatment.byVariant[$variant].enabled = @($Runs |
                Where-Object Variant -eq $variant |
                ForEach-Object LegacyTextComponentCacheEnabled | Sort-Object -Unique)
        }

        if ($abFactor -eq "scenario-config") {
            $allJvmArgumentHashes = @($Runs.JvmArgumentsSha256 | Sort-Object -Unique)
            if ($allJvmArgumentHashes.Count -ne 1) {
                throw "Scenario '$ScenarioName' scenario-config factor must keep JvmArgumentsSha256 identical across all runs."
            }
            foreach ($variant in @("A", "B")) {
                if ($configHashesByVariant[$variant].Count -ne 1) {
                    throw "Scenario '$ScenarioName' scenario-config variant $variant must use exactly one ConfigSha256."
                }
                $disableValues = $legacyTextComponentCacheTreatment.byVariant[$variant].disableProperty
                $enabledValues = $legacyTextComponentCacheTreatment.byVariant[$variant].enabled
                if ($disableValues.Count -ne 1 -or $enabledValues.Count -ne 1 -or
                        [bool]$disableValues[0] -ne $false -or [bool]$enabledValues[0] -ne $true) {
                    throw "Scenario '$ScenarioName' scenario-config variant $variant must keep the legacy text cache enabled."
                }
            }
            if ($configHashesByVariant.A[0] -eq $configHashesByVariant.B[0]) {
                throw "Scenario '$ScenarioName' scenario-config variants A and B must use different ConfigSha256 values."
            }
        } else {
            if ($configHashes.Count -ne 1) {
                throw "Scenario '$ScenarioName' legacy-text-component-cache factor must keep ConfigSha256 identical across all runs."
            }
            foreach ($variant in @("A", "B")) {
                if ($jvmArgumentsHashesByVariant[$variant].Count -ne 1) {
                    throw "Scenario '$ScenarioName' legacy-text-component-cache variant $variant must use exactly one JvmArgumentsSha256."
                }
            }
            if ($jvmArgumentsHashesByVariant.A[0] -eq $jvmArgumentsHashesByVariant.B[0]) {
                throw "Scenario '$ScenarioName' legacy-text-component-cache variants A and B must use different JvmArgumentsSha256 values."
            }
            foreach ($run in $Runs) {
                $expectedDisable = $run.Variant -eq "A"
                $expectedEnabled = $run.Variant -eq "B"
                if ($run.LegacyTextComponentCacheDisableProperty -ne $expectedDisable -or
                        $run.LegacyTextComponentCacheEnabled -ne $expectedEnabled) {
                    throw "Scenario '$ScenarioName' cache treatment mismatch in run '$($run.RunId)': A must be disabled and B enabled."
                }
            }
        }
    }

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
            $evidenceRun = [ordered]@{
                block = $run.Block
                position = $run.Position
                variant = $run.Variant
                runId = $run.RunId
                value = Round-Metric $metricsValue
                sourcePath = $run.SourcePath
                sourceSha256 = $sourceHash
            }
            if ($hasExtendedProvenance) {
                $evidenceRun["abFactor"] = $run.AbFactor
                $evidenceRun["configSha256"] = $run.ConfigSha256
                $evidenceRun["jvmArgumentsSha256"] = $run.JvmArgumentsSha256
                $evidenceRun["jvmArgumentsNormalizedSha256"] = $run.JvmArgumentsNormalizedSha256
                $evidenceRun["legacyTextComponentCacheDisableProperty"] =
                    $run.LegacyTextComponentCacheDisableProperty
                $evidenceRun["legacyTextComponentCacheEnabled"] =
                    $run.LegacyTextComponentCacheEnabled
            }
            $evidenceRuns.Add($evidenceRun)
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
        abFactor = $abFactor
        configSha256 = @($configHashes)
        configSha256ByVariant = $configHashesByVariant
        jvmArgumentsSha256ByVariant = $jvmArgumentsHashesByVariant
        jvmArgumentsNormalizedSha256 = $jvmArgumentsNormalizedSha256
        legacyTextComponentCache = $legacyTextComponentCacheTreatment
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

function Assert-CampaignProvenance {
    param([Parameter(Mandatory = $true)][object[]]$Runs)

    $extendedRuns = @($Runs | Where-Object HasExtendedProvenance)
    if ($extendedRuns.Count -eq 0) {
        return
    }
    $normalizedJvmHashes = @($extendedRuns.JvmArgumentsNormalizedSha256 | Sort-Object -Unique)
    if ($normalizedJvmHashes.Count -ne 1) {
        throw "Extended provenance campaign must use exactly one JvmArgumentsNormalizedSha256, found $($normalizedJvmHashes.Count)."
    }
    $abFactors = @($extendedRuns.AbFactor | Sort-Object -Unique)
    if ($abFactors.Count -ne 1) {
        throw "Extended provenance campaign must use exactly one AbFactor, found: $($abFactors -join ', ')."
    }
}

function New-SelfTestPreparedRuns {
    param(
        [Parameter(Mandatory = $true)][string]$ScenarioName,
        [switch]$Extended,
        [ValidateSet("scenario-config", "legacy-text-component-cache")]
        [string]$AbFactor = "legacy-text-component-cache"
    )

    $runs = New-Object 'System.Collections.Generic.List[object]'
    $patterns = @("ABBA", "BAAB", "ABBA")
    for ($block = 1; $block -le 3; $block++) {
        for ($position = 1; $position -le 4; $position++) {
            $variant = [string]$patterns[$block - 1][$position - 1]
            $value = if ($variant -eq "A") { 10.0 } else { 8.0 }
            $run = [ordered]@{
                Scenario = $ScenarioName
                Block = $block
                Position = $position
                Variant = $variant
                RunId = "${ScenarioName}_${block}_${position}_$variant"
                StackSha256 = ('1' * 64)
                ArtifactSha256 = if ($variant -eq "A") { ('a' * 64) } else { ('b' * 64) }
                CaptureMethod = "selftest"
                SourcePath = $null
                SourceSha256 = ('c' * 64)
                MetricValue = $value
            }
            if ($Extended) {
                $run["HasExtendedProvenance"] = $true
                $run["AbFactor"] = $AbFactor
                $run["ConfigSha256"] = if ($AbFactor -eq "scenario-config" -and $variant -eq "B") {
                    ('4' * 64)
                } else {
                    ('3' * 64)
                }
                $run["JvmArgumentsSha256"] = if ($AbFactor -eq "legacy-text-component-cache" -and
                        $variant -eq "B") { ('6' * 64) } else { ('5' * 64) }
                $run["JvmArgumentsNormalizedSha256"] = ('7' * 64)
                $run["LegacyTextComponentCacheDisableProperty"] =
                    ($AbFactor -eq "legacy-text-component-cache" -and $variant -eq "A")
                $run["LegacyTextComponentCacheEnabled"] =
                    -not $run["LegacyTextComponentCacheDisableProperty"]
            }
            $runs.Add([pscustomobject]$run)
        }
    }
    return $runs.ToArray()
}

function Assert-SelfTestRejected {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $rejected = $false
    try {
        & $Action
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Self-test failed to reject $Description."
    }
}

function Invoke-AnalyzerSelfTest {
    $runs = @(New-SelfTestPreparedRuns -ScenarioName "SELFTEST")
    $result = Get-ScenarioResult -Runs $runs -ScenarioName "SELFTEST" `
        -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
        -Iterations 1000 -RandomSeed 7 -Prepared
    if (-not $result.formalComplete -or $result.runCount -ne 12 -or $result.pairCount -ne 6) {
        throw "Self-test formal campaign accounting failed."
    }
    if ([math]::Abs($result.medianBRatioToA - 0.8) -gt 0.000001 -or
            [math]::Abs($result.improvementPercent - 20.0) -gt 0.000001) {
        throw "Self-test paired log-ratio failed."
    }
    if ($null -ne $result.abFactor -or $result.configSha256.Count -ne 0 -or
            $result.legacyTextComponentCache.provenancePresent) {
        throw "Self-test legacy manifest compatibility failed."
    }

    $legacyManifestRow = [pscustomobject][ordered]@{
        Scenario = "LEGACY_MANIFEST"
        Block = "1"
        Position = "1"
        Variant = "A"
        RunId = "LEGACY_MANIFEST_1_1_A"
        StackSha256 = ('1' * 64)
        ArtifactSha256 = ('a' * 64)
        CaptureMethod = "selftest"
        SourcePath = "selftest.json"
    }
    $convertedLegacyRows = @(Convert-ManifestRows -Rows @($legacyManifestRow) `
        -ManifestDirectory ([IO.Path]::GetTempPath()))
    if ($convertedLegacyRows.Count -ne 1 -or $convertedLegacyRows[0].HasExtendedProvenance) {
        throw "Self-test failed to accept a legacy nine-column manifest."
    }

    $partialProvenanceRow = [pscustomobject][ordered]@{
        Scenario = "PARTIAL"
        Block = "1"
        Position = "1"
        Variant = "A"
        RunId = "PARTIAL_1_1_A"
        StackSha256 = ('1' * 64)
        ArtifactSha256 = ('a' * 64)
        CaptureMethod = "selftest"
        SourcePath = "selftest.json"
        AbFactor = "legacy-text-component-cache"
    }
    Assert-SelfTestRejected -Description "a partial provenance column group" -Action {
        Convert-ManifestRows -Rows @($partialProvenanceRow) `
            -ManifestDirectory ([IO.Path]::GetTempPath()) | Out-Null
    }

    $cacheRuns = @(New-SelfTestPreparedRuns -ScenarioName "CACHE" -Extended `
        -AbFactor "legacy-text-component-cache")
    $cacheResult = Get-ScenarioResult -Runs $cacheRuns -ScenarioName "CACHE" `
        -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
        -Iterations 1000 -RandomSeed 11 -Prepared
    if ($cacheResult.abFactor -ne "legacy-text-component-cache" -or
            $cacheResult.configSha256.Count -ne 1 -or
            $cacheResult.jvmArgumentsSha256ByVariant.A.Count -ne 1 -or
            $cacheResult.jvmArgumentsSha256ByVariant.B.Count -ne 1 -or
            $cacheResult.jvmArgumentsSha256ByVariant.A[0] -eq
                $cacheResult.jvmArgumentsSha256ByVariant.B[0] -or
            $cacheResult.jvmArgumentsNormalizedSha256 -ne ('7' * 64) -or
            -not $cacheResult.legacyTextComponentCache.provenancePresent -or
            $cacheResult.runs[0].abFactor -ne "legacy-text-component-cache") {
        throw "Self-test failed to accept and preserve cache-factor provenance."
    }

    $badCacheConfig = @(New-SelfTestPreparedRuns -ScenarioName "BAD_CACHE_CONFIG" -Extended)
    $badCacheConfig[0].ConfigSha256 = ('8' * 64)
    Assert-SelfTestRejected -Description "cache-factor config drift" -Action {
        Get-ScenarioResult -Runs $badCacheConfig -ScenarioName "BAD_CACHE_CONFIG" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 13 -Prepared | Out-Null
    }

    $badCacheJvm = @(New-SelfTestPreparedRuns -ScenarioName "BAD_CACHE_JVM" -Extended)
    foreach ($run in $badCacheJvm | Where-Object Variant -eq "B") {
        $run.JvmArgumentsSha256 = ('5' * 64)
    }
    Assert-SelfTestRejected -Description "identical cache-factor A/B JVM hashes" -Action {
        Get-ScenarioResult -Runs $badCacheJvm -ScenarioName "BAD_CACHE_JVM" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 17 -Prepared | Out-Null
    }

    $badCacheTreatment = @(New-SelfTestPreparedRuns -ScenarioName "BAD_CACHE_TREATMENT" -Extended)
    $badCacheTreatment[0].LegacyTextComponentCacheDisableProperty = $false
    $badCacheTreatment[0].LegacyTextComponentCacheEnabled = $true
    Assert-SelfTestRejected -Description "a reversed cache-factor treatment" -Action {
        Get-ScenarioResult -Runs $badCacheTreatment -ScenarioName "BAD_CACHE_TREATMENT" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 19 -Prepared | Out-Null
    }

    $badNormalizedJvm = @(New-SelfTestPreparedRuns -ScenarioName "BAD_NORMALIZED_JVM" -Extended)
    $badNormalizedJvm[0].JvmArgumentsNormalizedSha256 = ('8' * 64)
    Assert-SelfTestRejected -Description "normalized JVM argument drift" -Action {
        Get-ScenarioResult -Runs $badNormalizedJvm -ScenarioName "BAD_NORMALIZED_JVM" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 23 -Prepared | Out-Null
    }

    $badScenarioConfigJvm = @(New-SelfTestPreparedRuns -ScenarioName "BAD_SCENARIO_CONFIG" `
        -Extended -AbFactor "scenario-config")
    $badScenarioConfigJvm[0].JvmArgumentsSha256 = ('8' * 64)
    Assert-SelfTestRejected -Description "scenario-config JVM argument drift" -Action {
        Get-ScenarioResult -Runs $badScenarioConfigJvm -ScenarioName "BAD_SCENARIO_CONFIG" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 29 -Prepared | Out-Null
    }

    $scenarioConfigRuns = @(New-SelfTestPreparedRuns -ScenarioName "SCENARIO_CONFIG" `
        -Extended -AbFactor "scenario-config")
    $scenarioConfigResult = Get-ScenarioResult -Runs $scenarioConfigRuns `
        -ScenarioName "SCENARIO_CONFIG" -MetricPath "metric" -MetricDirection "LowerIsBetter" `
        -PermitIncomplete $false -Iterations 1000 -RandomSeed 31 -Prepared
    if ($scenarioConfigResult.configSha256ByVariant.A.Count -ne 1 -or
            $scenarioConfigResult.configSha256ByVariant.B.Count -ne 1 -or
            $scenarioConfigResult.configSha256ByVariant.A[0] -eq
                $scenarioConfigResult.configSha256ByVariant.B[0]) {
        throw "Self-test failed to accept and preserve scenario-config provenance."
    }

    $badScenarioConfigDrift = @(New-SelfTestPreparedRuns -ScenarioName "BAD_SCENARIO_DRIFT" `
        -Extended -AbFactor "scenario-config")
    $badScenarioConfigDrift[0].ConfigSha256 = ('8' * 64)
    Assert-SelfTestRejected -Description "scenario-config config drift" -Action {
        Get-ScenarioResult -Runs $badScenarioConfigDrift -ScenarioName "BAD_SCENARIO_DRIFT" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 37 -Prepared | Out-Null
    }

    $badScenarioConfigSame = @(New-SelfTestPreparedRuns -ScenarioName "BAD_SCENARIO_SAME" `
        -Extended -AbFactor "scenario-config")
    foreach ($run in $badScenarioConfigSame | Where-Object Variant -eq "B") {
        $run.ConfigSha256 = ('3' * 64)
    }
    Assert-SelfTestRejected -Description "identical scenario-config A/B config hashes" -Action {
        Get-ScenarioResult -Runs $badScenarioConfigSame -ScenarioName "BAD_SCENARIO_SAME" `
            -MetricPath "metric" -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 41 -Prepared | Out-Null
    }

    $badScenarioConfigTreatment = @(New-SelfTestPreparedRuns `
        -ScenarioName "BAD_SCENARIO_TREATMENT" -Extended -AbFactor "scenario-config")
    $badScenarioConfigTreatment[0].LegacyTextComponentCacheDisableProperty = $true
    $badScenarioConfigTreatment[0].LegacyTextComponentCacheEnabled = $false
    Assert-SelfTestRejected -Description "scenario-config cache treatment drift" -Action {
        Get-ScenarioResult -Runs $badScenarioConfigTreatment `
            -ScenarioName "BAD_SCENARIO_TREATMENT" -MetricPath "metric" `
            -MetricDirection "LowerIsBetter" -PermitIncomplete $false `
            -Iterations 1000 -RandomSeed 43 -Prepared | Out-Null
    }

    $mixedFactorCampaign = @(
        $cacheRuns
        (New-SelfTestPreparedRuns -ScenarioName "MIXED_SCENARIO" -Extended `
            -AbFactor "scenario-config")
    )
    Assert-SelfTestRejected -Description "mixed campaign A/B factors" -Action {
        Assert-CampaignProvenance -Runs $mixedFactorCampaign
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
        acceptsLegacyNineColumnManifest = $true
        acceptsCacheFactorProvenance = $true
        acceptsScenarioConfigProvenance = $true
        rejectsPartialProvenanceColumns = $true
        enforcesConfigAndJvmProvenance = $true
        enforcesNormalizedJvmArguments = $true
        enforcesCacheTreatment = $true
        enforcesSingleCampaignFactor = $true
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
Assert-CampaignProvenance -Runs $runs
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

$extendedRuns = @($runs | Where-Object HasExtendedProvenance)
$abFactors = @($extendedRuns | ForEach-Object AbFactor | Sort-Object -Unique)
$rootAbFactor = if ($abFactors.Count -eq 0) {
    $null
} else {
    $abFactors[0]
}
$rootConfigHashes = @($extendedRuns | ForEach-Object ConfigSha256 | Sort-Object -Unique)
$rootConfigHashesByVariant = [ordered]@{
    A = @($extendedRuns | Where-Object Variant -eq "A" |
        ForEach-Object ConfigSha256 | Sort-Object -Unique)
    B = @($extendedRuns | Where-Object Variant -eq "B" |
        ForEach-Object ConfigSha256 | Sort-Object -Unique)
}
$rootJvmArgumentsHashesByVariant = [ordered]@{
    A = @($extendedRuns | Where-Object Variant -eq "A" |
        ForEach-Object JvmArgumentsSha256 | Sort-Object -Unique)
    B = @($extendedRuns | Where-Object Variant -eq "B" |
        ForEach-Object JvmArgumentsSha256 | Sort-Object -Unique)
}
$rootNormalizedJvmHashes = @($extendedRuns | ForEach-Object JvmArgumentsNormalizedSha256 |
    Sort-Object -Unique)
$rootNormalizedJvmHash = if ($rootNormalizedJvmHashes.Count -eq 1) {
    $rootNormalizedJvmHashes[0]
} else {
    $null
}
$rootCacheTreatment = [ordered]@{
    provenancePresent = $extendedRuns.Count -gt 0
    propertyName = "interactionvisualizer.disableLegacyTextComponentCache"
    treatmentScope = @("sharedComponentCache", "perEntitySameRawFastPath")
    byVariant = [ordered]@{
        A = [ordered]@{
            disableProperty = @($extendedRuns | Where-Object Variant -eq "A" |
                ForEach-Object LegacyTextComponentCacheDisableProperty | Sort-Object -Unique)
            enabled = @($extendedRuns | Where-Object Variant -eq "A" |
                ForEach-Object LegacyTextComponentCacheEnabled | Sort-Object -Unique)
        }
        B = [ordered]@{
            disableProperty = @($extendedRuns | Where-Object Variant -eq "B" |
                ForEach-Object LegacyTextComponentCacheDisableProperty | Sort-Object -Unique)
            enabled = @($extendedRuns | Where-Object Variant -eq "B" |
                ForEach-Object LegacyTextComponentCacheEnabled | Sort-Object -Unique)
        }
    }
}

Write-JsonResult ([ordered]@{
    schemaVersion = 2
    abFactor = $rootAbFactor
    configSha256 = $rootConfigHashes
    configSha256ByVariant = $rootConfigHashesByVariant
    jvmArgumentsSha256ByVariant = $rootJvmArgumentsHashesByVariant
    jvmArgumentsNormalizedSha256 = $rootNormalizedJvmHash
    legacyTextComponentCache = $rootCacheTreatment
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
    results = @($results.ToArray())
})
