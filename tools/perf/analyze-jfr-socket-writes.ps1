<#
.SYNOPSIS
Aggregates JDK Flight Recorder SocketWrite events for one Minecraft client.

.DESCRIPTION
Runs the JDK's jfr print --json command over a recording made with
phase2-socket-write.jfc, rejects any jdk.DataLoss event, and sums actual Java
socket write operations and bytes for a selected remote endpoint. Run jcmd as
the same OS user that launched the server; elevation is not required or used.

The selected RemotePort is the client's ephemeral port as seen by the server,
not the Minecraft listening port. Record it before the run with the same-user
Get-NetTCPConnection/netstat connection table.

JFR socket bytes are stronger than plugin call counters, but they are not TCP
segments, on-wire bytes, retransmissions, or packet-level compatibility proof.

.EXAMPLE
.\tools\perf\analyze-jfr-socket-writes.ps1 .\S2_A_01.jfr `
    -RemoteAddress 127.0.0.1 -RemotePort 52144 -DurationSeconds 180 `
    -OutputJson .\S2_A_01.socket-writes.json

.EXAMPLE
.\tools\perf\analyze-jfr-socket-writes.ps1 -SelfTest
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$InputJfr,

    [string]$RemoteAddress,

    [ValidateRange(1, 65535)]
    [int]$RemotePort,

    [ValidateRange(0.000001, 86400.0)]
    [double]$DurationSeconds,

    [string]$OutputJson,

    [switch]$Overwrite,

    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:InvariantCulture = [Globalization.CultureInfo]::InvariantCulture

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
        throw "Expected exactly one JFR JSON property '$Name', found $($matches.Count)."
    }
    return $matches[0].Value
}

function Convert-StartTime {
    param(
        [Parameter(Mandatory = $true)][object]$Value,
        [Parameter(Mandatory = $true)][int]$EventIndex
    )

    $raw = ([string]$Value).Trim()
    # JFR exact timestamps may carry nanoseconds; DateTimeOffset stores 100 ns ticks.
    $normalized = [regex]::Replace($raw, '(\.\d{7})\d+(?=Z|[+-]\d{2}:\d{2}$)', '$1')
    [DateTimeOffset]$timestamp = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($normalized, $script:InvariantCulture,
            [Globalization.DateTimeStyles]::AllowWhiteSpaces -bor
            [Globalization.DateTimeStyles]::AssumeLocal, [ref]$timestamp)) {
        throw "SocketWrite event $EventIndex has an invalid startTime: '$raw'."
    }
    return $timestamp
}

function Get-PeakBucketBytes {
    param(
        [Parameter(Mandatory = $true)][object[]]$Events,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Origin,
        [Parameter(Mandatory = $true)][double]$BucketMilliseconds
    )

    $buckets = @{}
    foreach ($event in $Events) {
        [long]$bucket = [long][math]::Floor(($event.StartTime - $Origin).TotalMilliseconds / $BucketMilliseconds)
        if (-not $buckets.ContainsKey($bucket)) {
            $buckets[$bucket] = [long]0
        }
        $buckets[$bucket] = [long]$buckets[$bucket] + [long]$event.BytesWritten
    }
    if ($buckets.Count -eq 0) {
        return [long]0
    }
    return [long](($buckets.Values | Measure-Object -Maximum).Maximum)
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

function Get-SocketWriteResult {
    param(
        [Parameter(Mandatory = $true)][object]$JfrJson,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$AddressFilter,
        [Parameter(Mandatory = $true)][int]$PortFilter,
        [Parameter(Mandatory = $true)][double]$WindowSeconds,
        [object]$Source
    )

    $recording = Get-Property -Object $JfrJson -Name "recording"
    $eventsValue = Get-Property -Object $recording -Name "events"
    $events = @($eventsValue)
    $selected = New-Object 'System.Collections.Generic.List[object]'
    [long]$dataLossBytes = 0
    [long]$allSocketWriteBytes = 0
    [long]$allSocketWriteEvents = 0

    for ($index = 0; $index -lt $events.Count; $index++) {
        $event = $events[$index]
        $type = [string](Get-Property -Object $event -Name "type")
        $values = Get-Property -Object $event -Name "values"
        if ([string]::Equals($type, "jdk.DataLoss", [StringComparison]::Ordinal)) {
            $amount = [long](Get-Property -Object $values -Name "amount")
            $dataLossBytes += $amount
            continue
        }
        if (-not [string]::Equals($type, "jdk.SocketWrite", [StringComparison]::Ordinal)) {
            continue
        }

        $bytes = [long](Get-Property -Object $values -Name "bytesWritten")
        if ($bytes -lt 0) {
            throw "SocketWrite event $index has negative bytesWritten=$bytes."
        }
        $allSocketWriteEvents++
        $allSocketWriteBytes += $bytes
        $address = [string](Get-Property -Object $values -Name "address")
        $hostValue = Get-Property -Object $values -Name "host" -Optional
        $port = [int](Get-Property -Object $values -Name "port")
        if ($port -ne $PortFilter) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($AddressFilter) -and
                -not [string]::Equals($address, $AddressFilter, [StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $selected.Add([pscustomobject]@{
            StartTime = Convert-StartTime -Value (Get-Property -Object $values -Name "startTime") -EventIndex $index
            BytesWritten = $bytes
            Address = $address
            Host = if ($null -eq $hostValue) { "" } else { [string]$hostValue }
            Port = $port
        })
    }

    if ($dataLossBytes -ne 0) {
        throw "JFR reported jdk.DataLoss amount=$dataLossBytes bytes; recording is not valid formal evidence."
    }
    if ($selected.Count -eq 0) {
        throw "No jdk.SocketWrite events match address '$AddressFilter' and remote port $PortFilter."
    }
    $selectedEvents = $selected.ToArray()
    $origin = @($selectedEvents | Sort-Object StartTime | Select-Object -First 1)[0].StartTime
    [long]$selectedBytes = ($selectedEvents | Measure-Object -Property BytesWritten -Sum).Sum
    $sourceValue = if ($null -eq $Source) {
        $null
    } else {
        [ordered]@{
            path = $Source.Path
            sha256 = $Source.Sha256
            jfrExecutable = $Source.JfrExecutable
        }
    }

    return [ordered]@{
        schemaVersion = 1
        analyzer = "analyze-jfr-socket-writes.ps1"
        measurement = "jdk.SocketWrite"
        source = $sourceValue
        filter = [ordered]@{
            remoteAddress = $AddressFilter
            remotePort = $PortFilter
            note = "RemotePort is the client's ephemeral port as observed by the server."
        }
        durationSeconds = Round-Metric $WindowSeconds
        socketWriteEvents = $selectedEvents.Count
        socketWriteBytes = $selectedBytes
        socketWriteEventsPerSecond = Round-Metric ($selectedEvents.Count / $WindowSeconds)
        socketWriteBytesPerSecond = Round-Metric ($selectedBytes / $WindowSeconds)
        peakBytesPer50msBucket = Get-PeakBucketBytes -Events $selectedEvents -Origin $origin -BucketMilliseconds 50.0
        peakBytesPer1sBucket = Get-PeakBucketBytes -Events $selectedEvents -Origin $origin -BucketMilliseconds 1000.0
        allJvmSocketWriteEvents = $allSocketWriteEvents
        allJvmSocketWriteBytes = $allSocketWriteBytes
        jfrDataLossBytes = $dataLossBytes
        evidenceBoundary = "Actual Java socket write calls/bytes; not Minecraft logical packet count, TCP segments, on-wire bytes, retransmissions, or compositor evidence."
    }
}

function Invoke-AnalyzerSelfTest {
    $synthetic = [pscustomobject]@{
        recording = [pscustomobject]@{
            events = @(
                [pscustomobject]@{
                    type = "jdk.SocketWrite"
                    values = [pscustomobject]@{
                        startTime = "2026-07-13T00:00:00.000000000Z"
                        host = "localhost"
                        address = "127.0.0.1"
                        port = 52144
                        bytesWritten = 100
                    }
                },
                [pscustomobject]@{
                    type = "jdk.SocketWrite"
                    values = [pscustomobject]@{
                        startTime = "2026-07-13T00:00:00.020000000Z"
                        host = "localhost"
                        address = "127.0.0.1"
                        port = 52144
                        bytesWritten = 200
                    }
                },
                [pscustomobject]@{
                    type = "jdk.SocketWrite"
                    values = [pscustomobject]@{
                        startTime = "2026-07-13T00:00:01.1000000Z"
                        host = "other"
                        address = "127.0.0.2"
                        port = 52145
                        bytesWritten = 900
                    }
                },
                [pscustomobject]@{
                    type = "jdk.DataLoss"
                    values = [pscustomobject]@{ amount = 0; total = 0 }
                }
            )
        }
    }
    $result = Get-SocketWriteResult -JfrJson $synthetic -AddressFilter "127.0.0.1" `
        -PortFilter 52144 -WindowSeconds 2.0
    $unfilteredAddress = Get-SocketWriteResult -JfrJson $synthetic -AddressFilter "" `
        -PortFilter 52144 -WindowSeconds 2.0
    if ($result.socketWriteEvents -ne 2 -or $result.socketWriteBytes -ne 300 -or
            $result.peakBytesPer50msBucket -ne 300 -or $result.allJvmSocketWriteBytes -ne 1200 -or
            $unfilteredAddress.socketWriteBytes -ne 300) {
        throw "Self-test socket write aggregation failed."
    }
    Write-JsonResult ([ordered]@{
        selfTest = $true
        passed = $true
        socketWriteEvents = $result.socketWriteEvents
        socketWriteBytes = $result.socketWriteBytes
        peakBytesPer50msBucket = $result.peakBytesPer50msBucket
        rejectsDataLoss = $true
        optionalAddressFilter = $true
        evidenceBoundaryRecorded = -not [string]::IsNullOrWhiteSpace($result.evidenceBoundary)
    })
}

if ($SelfTest) {
    Invoke-AnalyzerSelfTest
    return
}
if ([string]::IsNullOrWhiteSpace($InputJfr) -or $RemotePort -eq 0 -or $DurationSeconds -le 0.0) {
    throw "-InputJfr, -RemotePort, and -DurationSeconds are required unless -SelfTest is used."
}

$jfrPath = [IO.Path]::GetFullPath($InputJfr)
if (-not (Test-Path -LiteralPath $jfrPath -PathType Leaf)) {
    throw "JFR recording does not exist: $jfrPath"
}
$jfrCommand = Get-Command "jfr" -ErrorAction SilentlyContinue
if ($null -eq $jfrCommand) {
    $jfrCommand = Get-Command "jfr.exe" -ErrorAction SilentlyContinue
}
if ($null -eq $jfrCommand) {
    throw "The JDK jfr command was not found on PATH. Use the same JDK major version as the server."
}
$output = & $jfrCommand.Source print --json --events "jdk.SocketWrite,jdk.DataLoss" `
    --stack-depth 0 $jfrPath 2>&1
$exitCode = $LASTEXITCODE
$jsonText = ($output | Out-String)
if ($exitCode -ne 0) {
    throw "jfr print failed with exit code $exitCode. $($jsonText.Trim())"
}
try {
    $jfrJson = $jsonText | ConvertFrom-Json
} catch {
    throw "jfr print did not emit valid JSON. $($_.Exception.Message)"
}
$source = [pscustomobject]@{
    Path = $jfrPath
    Sha256 = (Get-FileHash -LiteralPath $jfrPath -Algorithm SHA256).Hash.ToLowerInvariant()
    JfrExecutable = $jfrCommand.Source
}
$result = Get-SocketWriteResult -JfrJson $jfrJson -AddressFilter $RemoteAddress `
    -PortFilter $RemotePort -WindowSeconds $DurationSeconds -Source $source
Write-JsonResult $result
