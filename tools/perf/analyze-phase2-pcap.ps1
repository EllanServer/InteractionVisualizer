<#
.SYNOPSIS
Analyzes one PktMon/Wireshark TCP capture for a Minecraft server port.

.DESCRIPTION
Accepts either pcapng/pcap/cap input (exported through tshark) or a previously
exported fields CSV. It separates server-to-client and client-to-server frames
by the configured listening port, then reports packet/data-segment counts,
TCP payload bytes, on-wire frame bytes, epoch-aligned 50 ms/1 s peaks, and the
TCP analysis flags available in the CSV.

This is an offline reader. It never starts/stops PktMon, changes filters, or
opens a live capture. The pcap path requires tshark; CSV and -SelfTest do not.

Formal runs should pass both -WindowStartEpochSeconds and
-WindowEndEpochSeconds. The selected interval is half-open: [start, end).

.EXAMPLE
.\tools\perf\analyze-phase2-pcap.ps1 .\S2_A_01.pcapng `
    -ServerPort 25566 -WindowStartEpochSeconds 1783920000.125 `
    -WindowEndEpochSeconds 1783920180.125 `
    -OutputJson .\S2_A_01.pcap-analysis.json

.EXAMPLE
.\tools\perf\analyze-phase2-pcap.ps1 .\S2_A_01.fields.csv `
    -ServerPort 25566 -WindowStartEpochSeconds 1783920000.125 `
    -WindowEndEpochSeconds 1783920180.125

.EXAMPLE
.\tools\perf\analyze-phase2-pcap.ps1 -SelfTest
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$InputPath,

    [ValidateRange(1, 65535)]
    [int]$ServerPort = 25566,

    [double]$WindowStartEpochSeconds,

    [double]$WindowEndEpochSeconds,

    [string]$TsharkPath,

    [string]$OutputJson,

    [switch]$Overwrite,

    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:InvariantCulture = [Globalization.CultureInfo]::InvariantCulture
$script:NumberStyles = [Globalization.NumberStyles]::Float
$script:IntegerStyles = [Globalization.NumberStyles]::Integer
$script:RequiredFields = @(
    "frame.number",
    "frame.time_epoch",
    "frame.len",
    "tcp.srcport",
    "tcp.dstport",
    "tcp.len"
)
$script:AnalysisFields = [ordered]@{
    retransmission = "tcp.analysis.retransmission"
    fastRetransmission = "tcp.analysis.fast_retransmission"
    spuriousRetransmission = "tcp.analysis.spurious_retransmission"
    lostSegment = "tcp.analysis.lost_segment"
    duplicateAck = "tcp.analysis.duplicate_ack"
    outOfOrder = "tcp.analysis.out_of_order"
    zeroWindow = "tcp.analysis.zero_window"
    reset = "tcp.flags.reset"
}

function Test-FiniteNumber {
    param([Parameter(Mandatory = $true)][double]$Value)
    return -not [double]::IsNaN($Value) -and -not [double]::IsInfinity($Value)
}

function Round-Metric {
    param([Parameter(Mandatory = $true)][double]$Value)
    return [math]::Round($Value, 6, [MidpointRounding]::AwayFromZero)
}

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-JsonResult {
    param([Parameter(Mandatory = $true)][object]$Value)

    $json = $Value | ConvertTo-Json -Depth 16
    if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
        $fullOutputPath = [IO.Path]::GetFullPath($OutputJson)
        if ((Test-Path -LiteralPath $fullOutputPath) -and -not $Overwrite) {
            throw "JSON output already exists: $fullOutputPath. Pass -Overwrite only after verifying the target."
        }
        $parent = Split-Path -Parent $fullOutputPath
        if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
            throw "JSON output directory does not exist: $parent"
        }
        [IO.File]::WriteAllText($fullOutputPath, $json + [Environment]::NewLine,
                [Text.UTF8Encoding]::new($false))
    }
    Write-Output $json
}

function Remove-OwnedTemporaryDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedLeafPrefix
    )

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd(
            [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
            [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $parent = [IO.Directory]::GetParent($fullPath)
    $leaf = [IO.Path]::GetFileName($fullPath)
    if ($null -eq $parent -or
            -not [string]::Equals($parent.FullName.TrimEnd(
                    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
                    $temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not $leaf.StartsWith($ExpectedLeafPrefix, [StringComparison]::Ordinal)) {
        throw "Refusing recursive cleanup outside the owned temp-directory pattern: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function Resolve-ColumnIndex {
    param(
        [Parameter(Mandatory = $true)][string[]]$Headers,
        [Parameter(Mandatory = $true)][string]$Expected,
        [switch]$Optional
    )

    $matches = New-Object 'System.Collections.Generic.List[int]'
    for ($index = 0; $index -lt $Headers.Count; $index++) {
        if ([string]::Equals($Headers[$index].TrimStart([char]0xFEFF), $Expected,
                [StringComparison]::OrdinalIgnoreCase)) {
            $matches.Add($index)
        }
    }
    if ($matches.Count -eq 0 -and $Optional) {
        return -1
    }
    if ($matches.Count -ne 1) {
        throw "Expected exactly one CSV column '$Expected', found $($matches.Count)."
    }
    return $matches[0]
}

function Get-FieldValue {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Fields,
        [Parameter(Mandatory = $true)][int]$Index
    )
    if ($Index -lt 0) {
        return ""
    }
    if ($Index -ge $Fields.Count) {
        throw "CSV row has $($Fields.Count) fields but index $Index was requested."
    }
    if ($null -eq $Fields[$Index]) {
        return ""
    }
    return [string]$Fields[$Index]
}

function Convert-RequiredLong {
    param(
        [Parameter(Mandatory = $true)][string]$Raw,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][long]$LineNumber,
        [long]$Minimum = 0L
    )

    [long]$value = 0L
    if (-not [long]::TryParse($Raw.Trim(), $script:IntegerStyles,
            $script:InvariantCulture, [ref]$value) -or $value -lt $Minimum) {
        throw "CSV line $LineNumber column '$Name' must be an integer >= $Minimum, but was '$Raw'."
    }
    return $value
}

function Convert-Port {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Raw,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][long]$LineNumber
    )

    $trimmed = $Raw.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        return 0
    }
    [int]$value = 0
    if (-not [int]::TryParse($trimmed, $script:IntegerStyles,
            $script:InvariantCulture, [ref]$value) -or $value -lt 1 -or $value -gt 65535) {
        throw "CSV line $LineNumber column '$Name' must be an empty value or TCP port 1..65535, but was '$Raw'."
    }
    return $value
}

function Convert-EpochSeconds {
    param(
        [Parameter(Mandatory = $true)][string]$Raw,
        [Parameter(Mandatory = $true)][long]$LineNumber
    )

    [double]$value = 0.0
    if (-not [double]::TryParse($Raw.Trim(), $script:NumberStyles,
            $script:InvariantCulture, [ref]$value) -or -not (Test-FiniteNumber $value) -or $value -lt 0.0) {
        throw "CSV line $LineNumber column 'frame.time_epoch' must be finite non-negative epoch seconds, but was '$Raw'."
    }
    return $value
}

function Test-FlagValue {
    param([AllowEmptyString()][string]$Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $false
    }
    $value = $Raw.Trim()
    if ($value -in @("0", "false", "False", "FALSE", "no", "No", "NO")) {
        return $false
    }
    [double]$number = 0.0
    if ([double]::TryParse($value, $script:NumberStyles,
            $script:InvariantCulture, [ref]$number)) {
        return $number -ne 0.0
    }
    return $true
}

function New-BucketAccumulator {
    return [pscustomobject]@{
        PacketCount = [long]0
        DataPacketCount = [long]0
        TcpPayloadBytes = [long]0
        OnWireBytes = [long]0
    }
}

function New-DirectionAccumulator {
    return [pscustomobject]@{
        PacketCount = [long]0
        DataPacketCount = [long]0
        TcpPayloadBytes = [long]0
        OnWireBytes = [long]0
        RetransmissionPackets = [long]0
        Retransmission = [long]0
        FastRetransmission = [long]0
        SpuriousRetransmission = [long]0
        LostSegment = [long]0
        DuplicateAck = [long]0
        OutOfOrder = [long]0
        ZeroWindow = [long]0
        Reset = [long]0
        Buckets50Ms = @{}
        Buckets1S = @{}
    }
}

function Add-BucketSample {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Buckets,
        [Parameter(Mandatory = $true)][double]$Timestamp,
        [Parameter(Mandatory = $true)][double]$BucketSeconds,
        [Parameter(Mandatory = $true)][long]$TcpLength,
        [Parameter(Mandatory = $true)][long]$FrameLength
    )

    [long]$bucketIndex = [long][math]::Floor($Timestamp / $BucketSeconds)
    if (-not $Buckets.ContainsKey($bucketIndex)) {
        $Buckets[$bucketIndex] = New-BucketAccumulator
    }
    $bucket = $Buckets[$bucketIndex]
    $bucket.PacketCount = [long]$bucket.PacketCount + 1L
    if ($TcpLength -gt 0L) {
        $bucket.DataPacketCount = [long]$bucket.DataPacketCount + 1L
    }
    $bucket.TcpPayloadBytes = [long]$bucket.TcpPayloadBytes + $TcpLength
    $bucket.OnWireBytes = [long]$bucket.OnWireBytes + $FrameLength
}

function Add-DirectionSample {
    param(
        [Parameter(Mandatory = $true)][object]$Accumulator,
        [Parameter(Mandatory = $true)][double]$Timestamp,
        [Parameter(Mandatory = $true)][long]$TcpLength,
        [Parameter(Mandatory = $true)][long]$FrameLength,
        [Parameter(Mandatory = $true)][hashtable]$Flags
    )

    $Accumulator.PacketCount = [long]$Accumulator.PacketCount + 1L
    if ($TcpLength -gt 0L) {
        $Accumulator.DataPacketCount = [long]$Accumulator.DataPacketCount + 1L
    }
    $Accumulator.TcpPayloadBytes = [long]$Accumulator.TcpPayloadBytes + $TcpLength
    $Accumulator.OnWireBytes = [long]$Accumulator.OnWireBytes + $FrameLength

    if ($Flags.retransmission -or $Flags.fastRetransmission -or $Flags.spuriousRetransmission) {
        $Accumulator.RetransmissionPackets = [long]$Accumulator.RetransmissionPackets + 1L
    }
    foreach ($mapping in @(
            @("retransmission", "Retransmission"),
            @("fastRetransmission", "FastRetransmission"),
            @("spuriousRetransmission", "SpuriousRetransmission"),
            @("lostSegment", "LostSegment"),
            @("duplicateAck", "DuplicateAck"),
            @("outOfOrder", "OutOfOrder"),
            @("zeroWindow", "ZeroWindow"),
            @("reset", "Reset"))) {
        if ($Flags[$mapping[0]]) {
            $property = $mapping[1]
            $Accumulator.$property = [long]$Accumulator.$property + 1L
        }
    }

    Add-BucketSample -Buckets $Accumulator.Buckets50Ms -Timestamp $Timestamp `
            -BucketSeconds 0.05D -TcpLength $TcpLength -FrameLength $FrameLength
    Add-BucketSample -Buckets $Accumulator.Buckets1S -Timestamp $Timestamp `
            -BucketSeconds 1.0D -TcpLength $TcpLength -FrameLength $FrameLength
}

function Get-PeakSummary {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Buckets,
        [Parameter(Mandatory = $true)][double]$BucketSeconds
    )

    $result = [ordered]@{
        bucketSeconds = $BucketSeconds
        packetCount = [long]0
        packetCountBucketStartEpochSeconds = $null
        dataPacketCount = [long]0
        dataPacketCountBucketStartEpochSeconds = $null
        tcpPayloadBytes = [long]0
        tcpPayloadBytesBucketStartEpochSeconds = $null
        onWireBytes = [long]0
        onWireBytesBucketStartEpochSeconds = $null
    }
    $metrics = @("PacketCount", "DataPacketCount", "TcpPayloadBytes", "OnWireBytes")
    [long[]]$keys = @($Buckets.Keys | ForEach-Object { [long]$_ } | Sort-Object)
    foreach ($key in $keys) {
        $bucket = $Buckets[$key]
        foreach ($metric in $metrics) {
            $jsonName = $metric.Substring(0, 1).ToLowerInvariant() + $metric.Substring(1)
            [long]$value = [long]$bucket.$metric
            if ($value -gt [long]$result[$jsonName]) {
                $result[$jsonName] = $value
                $result["${jsonName}BucketStartEpochSeconds"] = Round-Metric ($key * $BucketSeconds)
            }
        }
    }
    return [pscustomobject]$result
}

function Get-OptionalCounter {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Availability,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][long]$Value
    )
    if (-not $Availability[$Name]) {
        return $null
    }
    return $Value
}

function Get-DirectionResult {
    param(
        [Parameter(Mandatory = $true)][object]$Accumulator,
        [Parameter(Mandatory = $true)][double]$DurationSeconds,
        [Parameter(Mandatory = $true)][hashtable]$Availability,
        [Parameter(Mandatory = $true)][string]$Direction
    )

    $rateDivisor = if ($DurationSeconds -gt 0.0D) { $DurationSeconds } else { 1.0D }
    $retransmissionAvailable = $Availability.retransmission -or
            $Availability.fastRetransmission -or $Availability.spuriousRetransmission
    return [ordered]@{
        direction = $Direction
        packetCount = [long]$Accumulator.PacketCount
        dataPacketCount = [long]$Accumulator.DataPacketCount
        tcpPayloadBytes = [long]$Accumulator.TcpPayloadBytes
        onWireBytes = [long]$Accumulator.OnWireBytes
        packetsPerSecond = if ($DurationSeconds -gt 0.0D) {
            Round-Metric ($Accumulator.PacketCount / $rateDivisor)
        } else { 0.0D }
        dataPacketsPerSecond = if ($DurationSeconds -gt 0.0D) {
            Round-Metric ($Accumulator.DataPacketCount / $rateDivisor)
        } else { 0.0D }
        tcpPayloadBytesPerSecond = if ($DurationSeconds -gt 0.0D) {
            Round-Metric ($Accumulator.TcpPayloadBytes / $rateDivisor)
        } else { 0.0D }
        onWireBytesPerSecond = if ($DurationSeconds -gt 0.0D) {
            Round-Metric ($Accumulator.OnWireBytes / $rateDivisor)
        } else { 0.0D }
        retransmissionPackets = if ($retransmissionAvailable) {
            [long]$Accumulator.RetransmissionPackets
        } else { $null }
        tcpAnalysis = [ordered]@{
            retransmission = Get-OptionalCounter $Availability "retransmission" $Accumulator.Retransmission
            fastRetransmission = Get-OptionalCounter $Availability "fastRetransmission" $Accumulator.FastRetransmission
            spuriousRetransmission = Get-OptionalCounter $Availability "spuriousRetransmission" $Accumulator.SpuriousRetransmission
            lostSegment = Get-OptionalCounter $Availability "lostSegment" $Accumulator.LostSegment
            duplicateAck = Get-OptionalCounter $Availability "duplicateAck" $Accumulator.DuplicateAck
            outOfOrder = Get-OptionalCounter $Availability "outOfOrder" $Accumulator.OutOfOrder
            zeroWindow = Get-OptionalCounter $Availability "zeroWindow" $Accumulator.ZeroWindow
            reset = Get-OptionalCounter $Availability "reset" $Accumulator.Reset
        }
        peak50ms = Get-PeakSummary -Buckets $Accumulator.Buckets50Ms -BucketSeconds 0.05D
        peak1s = Get-PeakSummary -Buckets $Accumulator.Buckets1S -BucketSeconds 1.0D
    }
}

function Get-FieldsCsvAnalysis {
    param(
        [Parameter(Mandatory = $true)][string]$CsvPath,
        [Parameter(Mandatory = $true)][string]$EvidencePath,
        [Parameter(Mandatory = $true)][string]$EvidenceFormat,
        [Parameter(Mandatory = $true)][int]$Port,
        [object]$WindowStart,
        [object]$WindowEnd,
        [object]$TsharkMetadata
    )

    Add-Type -AssemblyName Microsoft.VisualBasic
    $parser = [Microsoft.VisualBasic.FileIO.TextFieldParser]::new(
            $CsvPath, [Text.UTF8Encoding]::new($false, $true), $true)
    $parser.TextFieldType = [Microsoft.VisualBasic.FileIO.FieldType]::Delimited
    $parser.SetDelimiters(",")
    $parser.HasFieldsEnclosedInQuotes = $true
    $parser.TrimWhiteSpace = $false

    try {
        if ($parser.EndOfData) {
            throw "Fields CSV is empty: $CsvPath"
        }
        [string[]]$headers = $parser.ReadFields()
        if ($null -eq $headers -or $headers.Count -eq 0) {
            throw "Fields CSV has no header: $CsvPath"
        }
        $indexes = @{}
        foreach ($name in $script:RequiredFields) {
            $indexes[$name] = Resolve-ColumnIndex -Headers $headers -Expected $name
        }
        $availability = @{}
        foreach ($entry in $script:AnalysisFields.GetEnumerator()) {
            [int]$index = Resolve-ColumnIndex -Headers $headers -Expected $entry.Value -Optional
            $indexes[$entry.Value] = $index
            $availability[$entry.Key] = $index -ge 0
        }

        $downstream = New-DirectionAccumulator
        $upstream = New-DirectionAccumulator
        [long]$sourceRows = 0L
        [long]$portMatchedRows = 0L
        [long]$selectedPortRows = 0L
        [long]$ignoredNonServerPortRows = 0L
        [long]$ambiguousDirectionRows = 0L
        $rawFirst = $null
        $rawLast = $null
        $selectedFirst = $null
        $selectedLast = $null

        while (-not $parser.EndOfData) {
            [string[]]$fields = $parser.ReadFields()
            $sourceRows++
            [long]$lineNumber = $sourceRows + 1L
            if ($null -eq $fields -or $fields.Count -ne $headers.Count) {
                $actual = if ($null -eq $fields) { 0 } else { $fields.Count }
                throw "CSV line $lineNumber has $actual fields; expected $($headers.Count)."
            }

            $null = Convert-RequiredLong -Raw (Get-FieldValue $fields $indexes["frame.number"]) `
                    -Name "frame.number" -LineNumber $lineNumber -Minimum 1L
            [double]$timestamp = Convert-EpochSeconds `
                    -Raw (Get-FieldValue $fields $indexes["frame.time_epoch"]) -LineNumber $lineNumber
            [long]$frameLength = Convert-RequiredLong `
                    -Raw (Get-FieldValue $fields $indexes["frame.len"]) `
                    -Name "frame.len" -LineNumber $lineNumber -Minimum 1L
            [int]$sourcePort = Convert-Port `
                    -Raw (Get-FieldValue $fields $indexes["tcp.srcport"]) `
                    -Name "tcp.srcport" -LineNumber $lineNumber
            [int]$destinationPort = Convert-Port `
                    -Raw (Get-FieldValue $fields $indexes["tcp.dstport"]) `
                    -Name "tcp.dstport" -LineNumber $lineNumber
            $tcpLengthRaw = (Get-FieldValue $fields $indexes["tcp.len"]).Trim()
            [long]$tcpLength = if ([string]::IsNullOrWhiteSpace($tcpLengthRaw)) {
                0L
            } else {
                Convert-RequiredLong -Raw $tcpLengthRaw -Name "tcp.len" `
                        -LineNumber $lineNumber -Minimum 0L
            }

            $sourceIsServer = $sourcePort -eq $Port
            $destinationIsServer = $destinationPort -eq $Port
            if (-not $sourceIsServer -and -not $destinationIsServer) {
                $ignoredNonServerPortRows++
                continue
            }
            $portMatchedRows++
            if ($null -eq $rawFirst -or $timestamp -lt [double]$rawFirst) { $rawFirst = $timestamp }
            if ($null -eq $rawLast -or $timestamp -gt [double]$rawLast) { $rawLast = $timestamp }

            if ($null -ne $WindowStart -and ($timestamp -lt [double]$WindowStart -or
                    $timestamp -ge [double]$WindowEnd)) {
                continue
            }
            $selectedPortRows++
            if ($null -eq $selectedFirst -or $timestamp -lt [double]$selectedFirst) { $selectedFirst = $timestamp }
            if ($null -eq $selectedLast -or $timestamp -gt [double]$selectedLast) { $selectedLast = $timestamp }

            if ($sourceIsServer -eq $destinationIsServer) {
                $ambiguousDirectionRows++
                continue
            }

            $flags = @{}
            foreach ($entry in $script:AnalysisFields.GetEnumerator()) {
                $flags[$entry.Key] = Test-FlagValue `
                        (Get-FieldValue $fields $indexes[$entry.Value])
            }
            if ($sourceIsServer) {
                Add-DirectionSample -Accumulator $downstream -Timestamp $timestamp `
                        -TcpLength $tcpLength -FrameLength $frameLength -Flags $flags
            } else {
                Add-DirectionSample -Accumulator $upstream -Timestamp $timestamp `
                        -TcpLength $tcpLength -FrameLength $frameLength -Flags $flags
            }
        }
    } catch [Microsoft.VisualBasic.FileIO.MalformedLineException] {
        throw "Malformed CSV near line $($parser.ErrorLineNumber): $($_.Exception.Message)"
    } finally {
        $parser.Dispose()
    }

    $explicitWindow = $null -ne $WindowStart
    [double]$duration = if ($explicitWindow) {
        [double]$WindowEnd - [double]$WindowStart
    } elseif ($null -ne $rawFirst -and $null -ne $rawLast) {
        [math]::Max(0.0D, [double]$rawLast - [double]$rawFirst)
    } else {
        0.0D
    }
    $effectiveStart = if ($explicitWindow) { [double]$WindowStart } else { $rawFirst }
    $effectiveEnd = if ($explicitWindow) { [double]$WindowEnd } else { $rawLast }
    [long]$classifiedRows = [long]$downstream.PacketCount + [long]$upstream.PacketCount

    $availabilityResult = [ordered]@{}
    foreach ($entry in $script:AnalysisFields.GetEnumerator()) {
        $availabilityResult[$entry.Value] = [bool]$availability[$entry.Key]
    }
    $allAnalysisFieldsAvailable = @($script:AnalysisFields.Keys | Where-Object {
            -not $availability[$_]
        }).Count -eq 0
    return [ordered]@{
        schemaVersion = 1
        analyzer = "analyze-phase2-pcap.ps1"
        source = [ordered]@{
            path = [IO.Path]::GetFullPath($EvidencePath)
            sha256 = Get-FileSha256 $EvidencePath
            format = $EvidenceFormat
            tshark = $TsharkMetadata
        }
        serverPort = $Port
        durationSeconds = Round-Metric $duration
        window = [ordered]@{
            explicit = $explicitWindow
            selection = if ($explicitWindow) { "[startEpochSeconds, endEpochSeconds)" } else { "all server-port rows" }
            startEpochSeconds = if ($null -ne $effectiveStart) { Round-Metric ([double]$effectiveStart) } else { $null }
            endEpochSeconds = if ($null -ne $effectiveEnd) { Round-Metric ([double]$effectiveEnd) } else { $null }
            rawFirstServerPortPacketEpochSeconds = if ($null -ne $rawFirst) { Round-Metric ([double]$rawFirst) } else { $null }
            rawLastServerPortPacketEpochSeconds = if ($null -ne $rawLast) { Round-Metric ([double]$rawLast) } else { $null }
            selectedFirstPacketEpochSeconds = if ($null -ne $selectedFirst) { Round-Metric ([double]$selectedFirst) } else { $null }
            selectedLastPacketEpochSeconds = if ($null -ne $selectedLast) { Round-Metric ([double]$selectedLast) } else { $null }
            bucketAlignment = "floor(frame.time_epoch / bucketSeconds), Unix epoch aligned"
        }
        rowAccounting = [ordered]@{
            sourceRows = $sourceRows
            serverPortRowsBeforeWindow = $portMatchedRows
            selectedServerPortRows = $selectedPortRows
            classifiedDirectionRows = $classifiedRows
            ignoredNonServerPortRows = $ignoredNonServerPortRows
            ambiguousDirectionRows = $ambiguousDirectionRows
        }
        analysisFieldAvailability = $availabilityResult
        downstream = Get-DirectionResult -Accumulator $downstream -DurationSeconds $duration `
                -Availability $availability -Direction "server-to-client (tcp.srcport == serverPort)"
        upstream = Get-DirectionResult -Accumulator $upstream -DurationSeconds $duration `
                -Availability $availability -Direction "client-to-server (tcp.dstport == serverPort)"
        formalEvidenceReady = $explicitWindow -and $duration -gt 0.0D -and
                $classifiedRows -gt 0L -and $ambiguousDirectionRows -eq 0L -and
                $allAnalysisFieldsAvailable
        evidenceBoundary = @(
            "Counts TCP frames and tcp.len/frame.len for one exported capture appearance; it does not count Minecraft logical packets or Bundle contents.",
            "Encrypted or compressed Minecraft payload is not decoded. PacketSize=0 preserves bytes but does not provide decryption keys.",
            "Retransmission, duplicate-ACK, lost-segment and out-of-order values are tshark TCP-analysis heuristics; missing CSV fields are null, never assumed zero.",
            "Capture drops/events-lost and PktMon component duplication are not derivable from these fields; retain counters JSON, stats TXT and the completed component sidecar.",
            "On-wire bytes are sum(frame.len) for classified TCP frames. Offload/capture-component semantics remain those of the source pcapng."
        )
    }
}

function Resolve-TsharkCommand {
    if (-not [string]::IsNullOrWhiteSpace($TsharkPath)) {
        $resolved = [IO.Path]::GetFullPath($TsharkPath)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "tshark executable does not exist: $resolved"
        }
        return $resolved
    }
    $command = Get-Command "tshark.exe" -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        $command = Get-Command "tshark" -ErrorAction SilentlyContinue
    }
    if ($null -eq $command) {
        throw "tshark was not found. Install Wireshark/TShark, pass -TsharkPath, or analyze a pre-exported fields CSV."
    }
    return $command.Source
}

function Export-PcapFieldsCsv {
    param(
        [Parameter(Mandatory = $true)][string]$CapturePath,
        [Parameter(Mandatory = $true)][string]$CsvPath,
        [Parameter(Mandatory = $true)][string]$ErrorPath,
        [Parameter(Mandatory = $true)][int]$Port
    )

    $tshark = Resolve-TsharkCommand
    $versionOutput = @(& $tshark --version 2>&1)
    $versionExitCode = $LASTEXITCODE
    if ($versionExitCode -ne 0) {
        throw "tshark --version failed (exit $versionExitCode): $($versionOutput -join [Environment]::NewLine)"
    }
    $fields = @($script:RequiredFields) + @($script:AnalysisFields.Values)
    $arguments = @(
        "-n", "-r", $CapturePath,
        "-Y", "tcp.port == $Port",
        "-T", "fields",
        "-E", "header=y",
        "-E", "separator=,",
        "-E", "quote=d",
        "-E", "occurrence=f"
    )
    foreach ($field in $fields) {
        $arguments += @("-e", $field)
    }

    & $tshark @arguments 2> $ErrorPath | Set-Content -LiteralPath $CsvPath -Encoding UTF8
    $exitCode = $LASTEXITCODE
    [string]$standardError = if (Test-Path -LiteralPath $ErrorPath) {
        [string](Get-Content -LiteralPath $ErrorPath -Raw -Encoding UTF8)
    } else { "" }
    if ($exitCode -ne 0) {
        throw "tshark field export failed (exit $exitCode): $standardError"
    }
    return [ordered]@{
        path = $tshark
        sha256 = Get-FileSha256 $tshark
        version = if ($versionOutput.Count -gt 0) { [string]$versionOutput[0] } else { "unknown" }
        displayFilter = "tcp.port == $Port"
        fields = $fields
        stderr = $standardError.Trim()
    }
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][object]$Actual,
        [Parameter(Mandatory = $true)][object]$Expected
    )
    if ($Actual -ne $Expected) {
        throw "Self-test '$Name' failed: actual='$Actual' expected='$Expected'."
    }
}

function Assert-Close {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][double]$Actual,
        [Parameter(Mandatory = $true)][double]$Expected,
        [double]$Tolerance = 0.000001D
    )
    if ([math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "Self-test '$Name' failed: actual=$Actual expected=$Expected tolerance=$Tolerance."
    }
}

function Invoke-AnalyzerSelfTest {
    $directory = Join-Path ([IO.Path]::GetTempPath()) "iv-phase2-pcap-$([Guid]::NewGuid().ToString('N'))"
    $null = New-Item -ItemType Directory -Path $directory
    $csvPath = Join-Path $directory "selftest.fields.csv"
    try {
        $rows = @(
            [ordered]@{ "frame.number"=1; "frame.time_epoch"="1000.010"; "frame.len"=154; "tcp.srcport"=25566; "tcp.dstport"=50000; "tcp.len"=100; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=2; "frame.time_epoch"="1000.020"; "frame.len"=104; "tcp.srcport"=25566; "tcp.dstport"=50000; "tcp.len"=50; "tcp.analysis.retransmission"=1; "tcp.analysis.fast_retransmission"=1; "tcp.analysis.spurious_retransmission"=1; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=3; "frame.time_epoch"="1000.049"; "frame.len"=54; "tcp.srcport"=50000; "tcp.dstport"=25566; "tcp.len"=0; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=1; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=4; "frame.time_epoch"="1000.051"; "frame.len"=254; "tcp.srcport"=25566; "tcp.dstport"=50000; "tcp.len"=200; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=5; "frame.time_epoch"="1000.900"; "frame.len"=84; "tcp.srcport"=50000; "tcp.dstport"=25566; "tcp.len"=30; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=1; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=1; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=6; "frame.time_epoch"="1001.010"; "frame.len"=94; "tcp.srcport"=50000; "tcp.dstport"=25566; "tcp.len"=40; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=1; "tcp.flags.reset"=1 },
            [ordered]@{ "frame.number"=7; "frame.time_epoch"="999.900"; "frame.len"=1053; "tcp.srcport"=25566; "tcp.dstport"=50000; "tcp.len"=999; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=8; "frame.time_epoch"="1002.000"; "frame.len"=1053; "tcp.srcport"=25566; "tcp.dstport"=50000; "tcp.len"=999; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" },
            [ordered]@{ "frame.number"=9; "frame.time_epoch"="1000.200"; "frame.len"=554; "tcp.srcport"=1234; "tcp.dstport"=4321; "tcp.len"=500; "tcp.analysis.retransmission"=""; "tcp.analysis.fast_retransmission"=""; "tcp.analysis.spurious_retransmission"=""; "tcp.analysis.lost_segment"=""; "tcp.analysis.duplicate_ack"=""; "tcp.analysis.out_of_order"=""; "tcp.analysis.zero_window"=""; "tcp.flags.reset"="" }
        )
        $rows | ForEach-Object { [pscustomobject]$_ } |
                Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8
        $result = Get-FieldsCsvAnalysis -CsvPath $csvPath -EvidencePath $csvPath `
                -EvidenceFormat "fields-csv" -Port 25566 -WindowStart 1000.0D `
                -WindowEnd 1002.0D -TsharkMetadata $null

        Assert-Equal "source rows" $result.rowAccounting.sourceRows 9L
        Assert-Equal "selected rows" $result.rowAccounting.selectedServerPortRows 6L
        Assert-Equal "ignored rows" $result.rowAccounting.ignoredNonServerPortRows 1L
        Assert-Equal "downstream packets" $result.downstream.packetCount 3L
        Assert-Equal "downstream data packets" $result.downstream.dataPacketCount 3L
        Assert-Equal "downstream payload" $result.downstream.tcpPayloadBytes 350L
        Assert-Equal "downstream wire" $result.downstream.onWireBytes 512L
        Assert-Equal "upstream packets" $result.upstream.packetCount 3L
        Assert-Equal "upstream data packets" $result.upstream.dataPacketCount 2L
        Assert-Equal "upstream payload" $result.upstream.tcpPayloadBytes 70L
        Assert-Equal "upstream wire" $result.upstream.onWireBytes 232L
        Assert-Equal "unique retransmission packets" $result.downstream.retransmissionPackets 1L
        Assert-Equal "retransmission flag" $result.downstream.tcpAnalysis.retransmission 1L
        Assert-Equal "fast retransmission flag" $result.downstream.tcpAnalysis.fastRetransmission 1L
        Assert-Equal "spurious retransmission flag" $result.downstream.tcpAnalysis.spuriousRetransmission 1L
        Assert-Equal "duplicate ACK flag" $result.upstream.tcpAnalysis.duplicateAck 1L
        Assert-Equal "lost segment flag" $result.upstream.tcpAnalysis.lostSegment 1L
        Assert-Equal "out of order flag" $result.upstream.tcpAnalysis.outOfOrder 1L
        Assert-Equal "zero window flag" $result.upstream.tcpAnalysis.zeroWindow 1L
        Assert-Equal "reset flag" $result.upstream.tcpAnalysis.reset 1L
        Assert-Equal "downstream 50ms packet peak" $result.downstream.peak50ms.packetCount 2L
        Assert-Equal "downstream 50ms payload peak" $result.downstream.peak50ms.tcpPayloadBytes 200L
        Assert-Equal "downstream 50ms wire peak" $result.downstream.peak50ms.onWireBytes 258L
        Assert-Equal "downstream 1s payload peak" $result.downstream.peak1s.tcpPayloadBytes 350L
        Assert-Equal "upstream 1s packet peak" $result.upstream.peak1s.packetCount 2L
        Assert-Equal "upstream 1s payload peak" $result.upstream.peak1s.tcpPayloadBytes 40L
        Assert-Close "duration" $result.durationSeconds 2.0D
        Assert-Close "downstream packet rate" $result.downstream.packetsPerSecond 1.5D
        Assert-Close "upstream payload rate" $result.upstream.tcpPayloadBytesPerSecond 35.0D
        Assert-Equal "formal evidence ready" $result.formalEvidenceReady $true

        $requiredOnlyCsvPath = Join-Path $directory "selftest.required-only.fields.csv"
        $rows | ForEach-Object {
            [pscustomobject][ordered]@{
                "frame.number" = $_["frame.number"]
                "frame.time_epoch" = $_["frame.time_epoch"]
                "frame.len" = $_["frame.len"]
                "tcp.srcport" = $_["tcp.srcport"]
                "tcp.dstport" = $_["tcp.dstport"]
                "tcp.len" = $_["tcp.len"]
            }
        } | Export-Csv -LiteralPath $requiredOnlyCsvPath -NoTypeInformation -Encoding UTF8
        $requiredOnlyResult = Get-FieldsCsvAnalysis -CsvPath $requiredOnlyCsvPath `
                -EvidencePath $requiredOnlyCsvPath -EvidenceFormat "fields-csv" `
                -Port 25566 -WindowStart 1000.0D -WindowEnd 1002.0D `
                -TsharkMetadata $null
        Assert-Equal "missing analysis fields reject formal evidence" `
                $requiredOnlyResult.formalEvidenceReady $false
        if ($null -ne $requiredOnlyResult.downstream.tcpAnalysis.retransmission -or
                $null -ne $requiredOnlyResult.upstream.tcpAnalysis.duplicateAck) {
            throw "Self-test 'missing analysis fields are null' failed."
        }

        $selfTestResult = [ordered]@{
            selfTest = $true
            passed = $true
            csvPathExercised = $true
            tsharkRequired = $false
            selectedPacketCount = $result.rowAccounting.classifiedDirectionRows
            downstreamPayloadBytes = $result.downstream.tcpPayloadBytes
            upstreamPayloadBytes = $result.upstream.tcpPayloadBytes
            epochAligned50msBuckets = $true
            halfOpenWindowVerified = $true
            completeAnalysisFieldsRequiredForFormalEvidence = $true
        }
        Write-Output ($selfTestResult | ConvertTo-Json -Depth 4)
    } finally {
        Remove-OwnedTemporaryDirectory -Path $directory -ExpectedLeafPrefix "iv-phase2-pcap-"
    }
}

if ($SelfTest) {
    Invoke-AnalyzerSelfTest
    return
}
if ([string]::IsNullOrWhiteSpace($InputPath)) {
    throw "-InputPath is required unless -SelfTest is used."
}
$hasWindowStart = $PSBoundParameters.ContainsKey("WindowStartEpochSeconds")
$hasWindowEnd = $PSBoundParameters.ContainsKey("WindowEndEpochSeconds")
if ($hasWindowStart -ne $hasWindowEnd) {
    throw "Pass both -WindowStartEpochSeconds and -WindowEndEpochSeconds, or neither."
}
if ($hasWindowStart) {
    if (-not (Test-FiniteNumber $WindowStartEpochSeconds) -or
            -not (Test-FiniteNumber $WindowEndEpochSeconds) -or
            $WindowStartEpochSeconds -lt 0.0D -or
            $WindowEndEpochSeconds -le $WindowStartEpochSeconds) {
        throw "The explicit epoch window must be finite, non-negative, and end must be greater than start."
    }
}

$fullInputPath = [IO.Path]::GetFullPath($InputPath)
if (-not (Test-Path -LiteralPath $fullInputPath -PathType Leaf)) {
    throw "Capture/CSV input does not exist: $fullInputPath"
}
$windowStart = if ($hasWindowStart) { [object]$WindowStartEpochSeconds } else { $null }
$windowEnd = if ($hasWindowEnd) { [object]$WindowEndEpochSeconds } else { $null }
$extension = [IO.Path]::GetExtension($fullInputPath).ToLowerInvariant()

if ($extension -eq ".csv") {
    $analysis = Get-FieldsCsvAnalysis -CsvPath $fullInputPath -EvidencePath $fullInputPath `
            -EvidenceFormat "fields-csv" -Port $ServerPort -WindowStart $windowStart `
            -WindowEnd $windowEnd -TsharkMetadata $null
    Write-JsonResult $analysis
    return
}
if ($extension -notin @(".pcapng", ".pcap", ".cap")) {
    throw "Input must be .pcapng, .pcap, .cap, or a fields .csv file: $fullInputPath"
}

$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "iv-phase2-tshark-$([Guid]::NewGuid().ToString('N'))"
$null = New-Item -ItemType Directory -Path $temporaryDirectory
$temporaryCsv = Join-Path $temporaryDirectory "fields.csv"
$temporaryError = Join-Path $temporaryDirectory "tshark.stderr.txt"
try {
    $tsharkMetadata = Export-PcapFieldsCsv -CapturePath $fullInputPath `
            -CsvPath $temporaryCsv -ErrorPath $temporaryError -Port $ServerPort
    $analysis = Get-FieldsCsvAnalysis -CsvPath $temporaryCsv -EvidencePath $fullInputPath `
            -EvidenceFormat $extension.TrimStart('.') -Port $ServerPort `
            -WindowStart $windowStart -WindowEnd $windowEnd -TsharkMetadata $tsharkMetadata
    Write-JsonResult $analysis
} finally {
    Remove-OwnedTemporaryDirectory -Path $temporaryDirectory `
            -ExpectedLeafPrefix "iv-phase2-tshark-"
}
