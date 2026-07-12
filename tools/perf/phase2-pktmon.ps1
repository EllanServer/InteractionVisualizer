[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("status", "start", "stop", "counters", "convert", "selftest")]
    [string]$Action,

    [string]$OutputDirectory,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')]
    [string]$RunId,

    [ValidateRange(1, 65535)]
    [int]$Port = 25566,

    [ValidateSet("nics", "all")]
    [string]$CaptureComponent = "nics",

    [ValidateRange(0, 65535)]
    [int]$CaptureComponentId = 0,

    [ValidateRange(0, 65535)]
    [int]$PacketSize = 128,

    [ValidateRange(16, 4096)]
    [int]$FileSizeMB = 1024,

    [string]$InputEtl,

    [ValidateRange(0, 65535)]
    [int]$ComponentId = 0,

    [switch]$CountersOnly,

    [switch]$Overwrite
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:PktMon = $null
$script:FilterNamePrefix = "IV-Phase2-TCP"
$script:ActiveManifestName = ".phase2-pktmon-active.json"
$script:CaptureLockName = ".phase2-pktmon-capture.lock.json"
$script:FilterOwnerName = ".phase2-pktmon-filter-owner.json"
$script:FilterStateName = ".phase2-pktmon-filter-state.txt"

function Get-PktMonCommand {
    if ($null -eq $script:PktMon) {
        $command = Get-Command "pktmon.exe" -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            $command = Get-Command "pktmon" -ErrorAction SilentlyContinue
        }
        if ($null -eq $command) {
            throw "pktmon was not found. This tool requires the Windows in-box pktmon executable."
        }
        $script:PktMon = $command.Source
    }
    return $script:PktMon
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$ArgumentList
    )

    $lines = & $FilePath @ArgumentList 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($lines | Out-String).TrimEnd()
    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = $text
    }
}

function Invoke-PktMonCapture {
    param([Parameter(Mandatory = $true)][string[]]$ArgumentList)
    return Invoke-NativeCapture -FilePath (Get-PktMonCommand) -ArgumentList $ArgumentList
}

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "This pktmon action requires an elevated (Run as administrator) PowerShell window."
    }
}

function Resolve-OutputDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [switch]$Create
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "-OutputDirectory is required for this action."
    }
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath)) {
        if (-not $Create) {
            throw "Output directory does not exist: $fullPath"
        }
        $null = New-Item -ItemType Directory -Path $fullPath -Force
    }
    $item = Get-Item -LiteralPath $fullPath
    if (-not $item.PSIsContainer) {
        throw "Output path is not a directory: $fullPath"
    }
    return $item.FullName
}

function Normalize-StateText {
    param([AllowEmptyString()][string]$Text)
    if ($null -eq $Text) {
        return ""
    }
    return (($Text -replace "`r`n", "`n") -replace "`r", "`n").Trim()
}

function Test-FilterListEmpty {
    param([AllowEmptyString()][string]$Text)
    $normalized = Normalize-StateText $Text
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return $true
    }

    $emptyPatterns = @(
        '(?im)^\s*packet\s+filters?\s*:\s*(none|0)\s*$',
        '(?im)^\s*(none|no\s+(active\s+)?packet\s+filters?)\s*$',
        '(?im)^\s*\u6570\u636E\u5305\u7B5B\u9009\u5668\s*[:\uFF1A]\s*(\u65E0|0)\s*$',
        '(?im)^\s*(\u65E0|\u6CA1\u6709).*(\u7B5B\u9009\u5668|\u8FC7\u6EE4\u5668)\s*$'
    )
    foreach ($pattern in $emptyPatterns) {
        if ($normalized -match $pattern) {
            return $true
        }
    }
    return $false
}

function Get-FilterList {
    $result = Invoke-PktMonCapture -ArgumentList @("filter", "list")
    if ($result.ExitCode -ne 0) {
        throw "Unable to read pktmon filters (exit $($result.ExitCode)): $($result.Text)"
    }
    return $result.Text
}

function Test-DefiniteEtwSession {
    $logman = Get-Command "logman.exe" -ErrorAction SilentlyContinue
    if ($null -eq $logman) {
        return $false
    }
    $query = Invoke-NativeCapture -FilePath $logman.Source -ArgumentList @("query", "PktMon", "-ets")
    return $query.ExitCode -eq 0
}

function Get-CaptureState {
    if (Test-DefiniteEtwSession) {
        return "Active"
    }

    $status = Invoke-PktMonCapture -ArgumentList @("status")
    if ($status.ExitCode -ne 0) {
        return "Unknown"
    }
    $text = Normalize-StateText $status.Text

    $inactivePatterns = @(
        '(?i)not\s+running',
        '(?i)no\s+(active\s+)?(packet\s+)?capture',
        '(?i)stopped',
        '\u672A\u8FD0\u884C',
        '\u672A\u542F\u52A8',
        '\u5DF2\u505C\u6B62',
        '\u6CA1\u6709.*\u6355\u83B7',
        '\u65E0\u6D3B\u52A8.*\u6355\u83B7',
        '\u5F53\u524D\u6CA1\u6709'
    )
    foreach ($pattern in $inactivePatterns) {
        if ($text -match $pattern) {
            return "Inactive"
        }
    }

    if ($text -match '(?is)collected\s+data\s*:.*(packet\s+counters|packet\s+capture)' -or
        $text -match '(\u6B63\u5728\u8FD0\u884C|\u8FD0\u884C\u4E2D|\u6B63\u5728\u6355\u83B7|\u6570\u636E\u5305\u6355\u83B7.*(\u542F\u7528|\u5DF2\u542F\u52A8)|\u6570\u636E\u5305\u8BA1\u6570\u5668.*(\u542F\u7528|\u5DF2\u542F\u52A8))') {
        return "Active"
    }

    # A counters-only PktMon session may not expose the regular ETW logger.
    # Treat valid JSON counters as active only when localized status was inconclusive.
    $counters = Invoke-PktMonCapture -ArgumentList @("counters", "--json")
    if ($counters.ExitCode -eq 0 -and $counters.Text -match '^\s*[\[{]') {
        return "Active"
    }
    return "Unknown"
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json
}

function ConvertTo-Utf8Bytes {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $encoding = [Text.UTF8Encoding]::new($false)
    return $encoding.GetBytes($Text)
}

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = ConvertTo-Utf8Bytes -Text $Text
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Write-NewTextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )
    $bytes = ConvertTo-Utf8Bytes -Text ($Text + [Environment]::NewLine)
    $stream = [IO.FileStream]::new($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
            [IO.FileShare]::Read, 4096, [IO.FileOptions]::WriteThrough)
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function Write-NewJsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Path
    )
    Write-NewTextFile -Path $Path -Text ($Value | ConvertTo-Json -Depth 12)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $directory = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path))
    $leaf = [IO.Path]::GetFileName($Path)
    $temporary = Join-Path $directory ".$leaf.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    $backup = Join-Path $directory ".$leaf.$PID.$([Guid]::NewGuid().ToString('N')).bak"
    try {
        Write-NewTextFile -Path $temporary -Text ($Value | ConvertTo-Json -Depth 12)
        if (Test-Path -LiteralPath $Path) {
            [IO.File]::Replace($temporary, $Path, $backup, $true)
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Required manifest property '$Name' is missing."
    }
    return $property.Value
}

function Assert-OrCreateOwnedFilter {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,

        [Parameter(Mandatory = $true)]
        [int]$FilterPort
    )

    $filterName = "$($script:FilterNamePrefix)-$FilterPort"
    $ownerPath = Join-Path $Directory $script:FilterOwnerName
    $statePath = Join-Path $Directory $script:FilterStateName
    $currentText = Get-FilterList
    $owner = Read-JsonFile $ownerPath

    if ($null -ne $owner) {
        if (-not (Test-Path -LiteralPath $statePath)) {
            throw "Owned filter metadata exists but its saved state is missing. Refusing to touch global filters."
        }
        if ([string]$owner.filterName -ne $filterName -or [int]$owner.port -ne $FilterPort) {
            throw "Saved filter ownership does not match TCP/$FilterPort. Use the original campaign directory or an isolated host."
        }
        $savedText = [IO.File]::ReadAllText($statePath, [Text.UTF8Encoding]::new($false))
        if ((Normalize-StateText $savedText) -ne (Normalize-StateText $currentText)) {
            throw "Global pktmon filters changed since this campaign created its filter. Refusing to start."
        }
        $ownerHost = $owner.PSObject.Properties["host"]
        if ($null -ne $ownerHost -and
            -not [string]::Equals([string]$ownerHost.Value, [Environment]::MachineName,
                    [StringComparison]::OrdinalIgnoreCase)) {
            throw "Saved filter ownership belongs to host '$($ownerHost.Value)', not this machine. Refusing to start."
        }
        $ownerFingerprint = $owner.PSObject.Properties["stateSha256"]
        $currentFingerprint = Get-TextSha256 -Text (Normalize-StateText $currentText)
        if ($null -ne $ownerFingerprint -and [string]$ownerFingerprint.Value -ne $currentFingerprint) {
            throw "Saved filter fingerprint does not match the current global filter state. Refusing to start."
        }
        if ($currentText -notmatch [regex]::Escape($filterName) -or
            $currentText -notmatch "(?<!\d)$FilterPort(?!\d)") {
            throw "The saved filter state no longer proves ownership of TCP/$FilterPort. Refusing to start."
        }
        return $filterName
    }

    if (-not (Test-FilterListEmpty $currentText)) {
        throw "Existing or unrecognized global pktmon filters were found. This tool never deletes them and will not add another filter."
    }

    $add = Invoke-PktMonCapture -ArgumentList @("filter", "add", $filterName, "-t", "TCP", "-p", [string]$FilterPort)
    if ($add.ExitCode -ne 0) {
        throw "Unable to add the TCP/$FilterPort pktmon filter (exit $($add.ExitCode)): $($add.Text)"
    }

    $createdText = Get-FilterList
    if ($createdText -notmatch [regex]::Escape($filterName) -or
        $createdText -notmatch "(?<!\d)$FilterPort(?!\d)") {
        throw "The filter was added, but its state could not be verified. It was intentionally left in place; inspect it manually."
    }

    Write-NewTextFile -Path $statePath -Text (Normalize-StateText $createdText)
    Write-NewJsonFile -Path $ownerPath -Value ([ordered]@{
        schema = 2
        filterName = $filterName
        port = $FilterPort
        host = [Environment]::MachineName
        stateSha256 = Get-TextSha256 -Text (Normalize-StateText $createdText)
        createdUtc = [DateTime]::UtcNow.ToString("o")
        note = "The wrapper never removes global pktmon filters."
    })
    return $filterName
}

function Get-ActiveManifestPath {
    param([Parameter(Mandatory = $true)][string]$Directory)
    return Join-Path $Directory $script:ActiveManifestName
}

function Get-CaptureLockPath {
    param([Parameter(Mandatory = $true)][string]$Directory)
    return Join-Path $Directory $script:CaptureLockName
}

function New-CaptureLock {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$CaptureId
    )
    try {
        $processStartUtc = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString("o")
    } catch {
        $processStartUtc = $null
    }
    $path = Get-CaptureLockPath -Directory $Directory
    try {
        Write-NewJsonFile -Path $path -Value ([ordered]@{
            schema = 1
            captureId = $CaptureId
            runId = $Id
            campaignDirectory = $Directory
            host = [Environment]::MachineName
            ownerProcessId = $PID
            ownerProcessStartUtc = $processStartUtc
            createdUtc = [DateTime]::UtcNow.ToString("o")
            note = "Never auto-steal this lock; inspect PktMon and the active manifest first."
        })
    } catch [IO.IOException] {
        throw "Another capture or stale campaign lock already owns '$Directory'. It is never stolen automatically: $path"
    }
    return $path
}

function Assert-CaptureLock {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$CaptureId
    )
    $path = Get-CaptureLockPath -Directory $Directory
    $lock = Read-JsonFile $path
    if ($null -eq $lock) {
        throw "Capture lock is missing: $path"
    }
    $actualCaptureId = [string](Get-RequiredProperty -Object $lock -Name "captureId")
    $actualRunId = [string](Get-RequiredProperty -Object $lock -Name "runId")
    $actualHost = [string](Get-RequiredProperty -Object $lock -Name "host")
    $actualDirectory = [IO.Path]::GetFullPath([string](Get-RequiredProperty -Object $lock -Name "campaignDirectory"))
    if ($actualCaptureId -ne $CaptureId) {
        throw "Capture lock ID '$actualCaptureId' does not match '$CaptureId'."
    }
    if ($actualRunId -ne $Id) {
        throw "Capture lock RunId '$actualRunId' does not match '$Id'."
    }
    if (-not [string]::Equals($actualHost, [Environment]::MachineName, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Capture lock host '$actualHost' does not match '$([Environment]::MachineName)'."
    }
    if (-not [string]::Equals($actualDirectory, $Directory, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Capture lock directory '$actualDirectory' does not match '$Directory'."
    }
    return $path
}

function Remove-CaptureLock {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$CaptureId
    )
    $path = Assert-CaptureLock -Directory $Directory -Id $Id -CaptureId $CaptureId
    Remove-Item -LiteralPath $path -Force
}

function Get-RunArtifactPaths {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Id
    )
    return @(
        (Join-Path $Directory "$Id.claim.json"),
        (Join-Path $Directory "$Id.etl"),
        (Join-Path $Directory "$Id.counters-live.json"),
        (Join-Path $Directory "$Id.counters-final.json"),
        (Join-Path $Directory "$Id.capture.json"),
        (Join-Path $Directory "$Id.pcapng"),
        (Join-Path $Directory "$Id.stats.txt")
    )
}

function New-RunClaim {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$CaptureId
    )
    $paths = Get-RunArtifactPaths -Directory $Directory -Id $Id
    foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path) {
            throw "RunId '$Id' is already claimed by existing output: $path"
        }
    }
    $claimPath = $paths[0]
    Write-NewJsonFile -Path $claimPath -Value ([ordered]@{
        schema = 1
        runId = $Id
        captureId = $CaptureId
        campaignDirectory = $Directory
        host = [Environment]::MachineName
        claimedUtc = [DateTime]::UtcNow.ToString("o")
        note = "Run claims are immutable and prevent accidental output reuse."
    })
    return $claimPath
}

function Get-LogmanStatus {
    $logman = Get-Command "logman.exe" -ErrorAction SilentlyContinue
    if ($null -eq $logman) {
        return [pscustomobject]@{ ExitCode = -1; Text = "" }
    }
    return Invoke-NativeCapture -FilePath $logman.Source -ArgumentList @("query", "PktMon", "-ets")
}

function Get-CaptureEvidence {
    $status = Invoke-PktMonCapture -ArgumentList @("status", "--buffer-info")
    $logman = Get-LogmanStatus
    $combined = (Normalize-StateText ($status.Text + "`n" + $logman.Text)).Replace('/', '\')
    return [pscustomobject]@{
        StatusExitCode = $status.ExitCode
        StatusText = $status.Text
        LogmanExitCode = $logman.ExitCode
        LogmanText = $logman.Text
        CombinedText = $combined
        Sha256 = Get-TextSha256 -Text $combined
    }
}

function Assert-ManifestSchema2 {
    param([Parameter(Mandatory = $true)][object]$Manifest)
    $schema = [int](Get-RequiredProperty -Object $Manifest -Name "schema")
    if ($schema -ne 2) {
        throw "Unsupported capture manifest schema '$schema'; schema 2 ownership metadata is required."
    }
}

function Test-ManifestCaptureOwnership {
    param([Parameter(Mandatory = $true)][object]$Manifest)

    $reasons = [Collections.Generic.List[string]]::new()
    try {
        Assert-ManifestSchema2 -Manifest $Manifest
    } catch {
        $reasons.Add($_.Exception.Message)
        return [pscustomobject]@{ Verified = $false; Reasons = @($reasons); State = "Unknown"; Evidence = $null }
    }

    $host = [string](Get-RequiredProperty -Object $Manifest -Name "host")
    if (-not [string]::Equals($host, [Environment]::MachineName, [StringComparison]::OrdinalIgnoreCase)) {
        $reasons.Add("Manifest host '$host' does not match '$([Environment]::MachineName)'.")
    }

    $state = Get-CaptureState
    if ($state -ne "Active") {
        $reasons.Add("PktMon state is '$state', not definitively active.")
    }

    $filter = Get-RequiredProperty -Object $Manifest -Name "filter"
    $expectedFilterFingerprint = [string](Get-RequiredProperty -Object $filter -Name "stateSha256")
    try {
        $currentFilterFingerprint = Get-TextSha256 -Text (Normalize-StateText (Get-FilterList))
        if ($currentFilterFingerprint -ne $expectedFilterFingerprint) {
            $reasons.Add("Current global filter fingerprint does not match the capture manifest.")
        }
    } catch {
        $reasons.Add("Unable to verify current filters: $($_.Exception.Message)")
    }

    $mode = [string](Get-RequiredProperty -Object $Manifest -Name "captureMode")
    $evidence = Get-CaptureEvidence
    if ($mode -eq "counters-only") {
        $reasons.Add("Counters-only PktMon exposes no unique ETL path or public session ID; ownership cannot be proven safely.")
    } elseif ($mode -eq "etl") {
        $etlPath = [string](Get-RequiredProperty -Object $Manifest -Name "etlPath")
        if ([string]::IsNullOrWhiteSpace($etlPath)) {
            $reasons.Add("ETL capture manifest has no canonical ETL path.")
        } else {
            $canonical = ([IO.Path]::GetFullPath($etlPath)).Replace('/', '\')
            if ($evidence.CombinedText.IndexOf($canonical, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
                $reasons.Add("Live PktMon/logman status does not contain the manifest's unique ETL path '$canonical'.")
            }
        }
    } else {
        $reasons.Add("Unknown capture mode '$mode'.")
    }

    return [pscustomobject]@{
        Verified = $reasons.Count -eq 0
        Reasons = @($reasons)
        State = $state
        Evidence = $evidence
    }
}

function Try-StopManifestCapture {
    param([Parameter(Mandatory = $true)][object]$Manifest)
    $verification = Test-ManifestCaptureOwnership -Manifest $Manifest
    if (-not $verification.Verified) {
        return [pscustomobject]@{
            Stopped = $false
            Verification = $verification
            Output = ""
            ExitCode = $null
            FinalState = $verification.State
        }
    }
    $stopped = Invoke-PktMonCapture -ArgumentList @("stop")
    $finalState = Get-CaptureState
    return [pscustomobject]@{
        Stopped = $stopped.ExitCode -eq 0 -and $finalState -eq "Inactive"
        Verification = $verification
        Output = $stopped.Text
        ExitCode = $stopped.ExitCode
        FinalState = $finalState
    }
}

function Invoke-StatusAction {
    Assert-Administrator
    $state = Get-CaptureState
    $status = Invoke-PktMonCapture -ArgumentList @("status", "--buffer-info")
    $filters = Invoke-PktMonCapture -ArgumentList @("filter", "list")

    Write-Output "SafetyState: $state"
    Write-Output "--- pktmon status ---"
    Write-Output $status.Text
    Write-Output "--- pktmon filters ---"
    Write-Output $filters.Text

    if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $directory = Resolve-OutputDirectory -Path $OutputDirectory
        $manifestPath = Get-ActiveManifestPath $directory
        if (Test-Path -LiteralPath $manifestPath) {
            Write-Output "--- campaign manifest ---"
            Write-Output ([IO.File]::ReadAllText($manifestPath, [Text.UTF8Encoding]::new($false)))
        }
        $lockPath = Get-CaptureLockPath -Directory $directory
        if (Test-Path -LiteralPath $lockPath) {
            Write-Output "--- campaign capture lock ---"
            Write-Output ([IO.File]::ReadAllText($lockPath, [Text.UTF8Encoding]::new($false)))
        }
    }
}

function Invoke-StartAction {
    Assert-Administrator
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        throw "-RunId is required for start."
    }
    if ($PacketSize -ne 0 -and $PacketSize -lt 64) {
        throw "-PacketSize must be 0 (full packet) or at least 64 bytes."
    }
    if ($CountersOnly) {
        throw "Safe CountersOnly start is unavailable: PktMon exposes neither a unique ETL path nor a public capture session ID, so a later global stop cannot prove ownership. Use ETL mode or operate pktmon manually on an isolated host."
    }

    $directory = Resolve-OutputDirectory -Path $OutputDirectory -Create
    $captureId = [Guid]::NewGuid().ToString("D")
    $captureLockPath = New-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
    try {
    $manifestPath = Get-ActiveManifestPath $directory
    $previousManifest = Read-JsonFile $manifestPath
    if ($null -ne $previousManifest) {
        $previousStatusProperty = $previousManifest.PSObject.Properties["status"]
        if ($null -eq $previousStatusProperty -or [string]$previousStatusProperty.Value -ne "completed") {
            throw "This campaign has a non-completed capture manifest. Recover it before starting another capture."
        }
    }

    $state = Get-CaptureState
    if ($state -ne "Inactive") {
        throw "PktMon capture state is '$state', not definitively inactive. Refusing to start or alter filters."
    }

    $claimPath = New-RunClaim -Directory $directory -Id $RunId -CaptureId $captureId
    $filterName = Assert-OrCreateOwnedFilter -Directory $directory -FilterPort $Port
    $filterText = Normalize-StateText (Get-FilterList)
    $filterFingerprint = Get-TextSha256 -Text $filterText
    $etlPath = [IO.Path]::GetFullPath((Join-Path $directory "$RunId.etl"))

    $arguments = @("start")
    $arguments += @("--capture")
    if ($CaptureComponentId -gt 0) {
        $arguments += @("--comp", [string]$CaptureComponentId)
        $componentDescription = [string]$CaptureComponentId
        $componentSelector = "id"
    } else {
        $arguments += @("--comp", $CaptureComponent)
        $componentDescription = $CaptureComponent
        $componentSelector = "name"
    }
    $arguments += @("--type", "all")
    $arguments += @(
        "--pkt-size", [string]$PacketSize,
        "--file-name", $etlPath,
        "--file-size", [string]$FileSizeMB,
        "--log-mode", "circular"
    )

    try {
        $starterProcessStartUtc = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString("o")
    } catch {
        $starterProcessStartUtc = $null
    }
    $manifest = [pscustomobject][ordered]@{
        schema = 2
        status = "starting"
        captureId = $captureId
        runId = $RunId
        campaignDirectory = $directory
        captureLockPath = $captureLockPath
        claimPath = $claimPath
        host = [Environment]::MachineName
        starterProcessId = $PID
        starterProcessStartUtc = $starterProcessStartUtc
        manifestCreatedUtc = [DateTime]::UtcNow.ToString("o")
        startAttemptUtc = [DateTime]::UtcNow.ToString("o")
        captureMode = "etl"
        port = $Port
        filter = [ordered]@{
            name = $filterName
            port = $Port
            stateSha256 = $filterFingerprint
            hashAlgorithm = "SHA-256/UTF-8-no-BOM/normalized-text"
        }
        component = [ordered]@{
            selector = $componentSelector
            value = $componentDescription
        }
        packetSize = $PacketSize
        fileSizeMB = $FileSizeMB
        logMode = "circular"
        etlPath = $etlPath
        startArgumentsSha256 = Get-TextSha256 -Text ($arguments -join [char]31)
    }
    Write-JsonFile -Path $manifestPath -Value $manifest
    } catch {
        $setupError = $_.Exception
        try {
            Remove-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
        } catch {
            Write-Warning "Unable to release capture lock after pre-start failure: $($_.Exception.Message)"
        }
        throw $setupError
    }

    try {
        $started = Invoke-PktMonCapture -ArgumentList $arguments
    } catch {
        $invokeError = $_.Exception.Message
        $manifest.status = "start-invocation-failed"
        $manifest | Add-Member -NotePropertyName startError -NotePropertyValue $invokeError -Force
        $recovery = Try-StopManifestCapture -Manifest $manifest
        if ($recovery.Stopped) {
            $manifest.status = "start-invocation-failed-cleaned"
            $manifest | Add-Member -NotePropertyName cleanupUtc -NotePropertyValue ([DateTime]::UtcNow.ToString("o")) -Force
        } elseif ($recovery.FinalState -eq "Active") {
            $manifest.status = "start-invocation-failed-unverified"
            $manifest | Add-Member -NotePropertyName recoveryReason -NotePropertyValue ($recovery.Verification.Reasons -join " ") -Force
        } else {
            $manifest.status = "start-invocation-failed-inactive"
        }
        Write-JsonFile -Path $manifestPath -Value $manifest
        if ($recovery.Stopped -or $recovery.FinalState -eq "Inactive") {
            Remove-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
        }
        throw "Unable to invoke pktmon start. Cleanup was attempted only with exact ownership proof; manifest status is '$($manifest.status)': $invokeError"
    }
    $manifest | Add-Member -NotePropertyName startExitCode -NotePropertyValue $started.ExitCode -Force
    $manifest | Add-Member -NotePropertyName startOutputSha256 -NotePropertyValue (Get-TextSha256 -Text $started.Text) -Force
    if ($started.ExitCode -ne 0) {
        $manifest.status = "start-failed"
        $manifest | Add-Member -NotePropertyName startError -NotePropertyValue $started.Text -Force
        $recovery = Try-StopManifestCapture -Manifest $manifest
        if ($recovery.Stopped) {
            $manifest.status = "start-failed-cleaned"
            $manifest | Add-Member -NotePropertyName cleanupUtc -NotePropertyValue ([DateTime]::UtcNow.ToString("o")) -Force
        } elseif ($recovery.FinalState -eq "Active") {
            $manifest.status = "start-failed-unverified"
            $manifest | Add-Member -NotePropertyName recoveryReason -NotePropertyValue ($recovery.Verification.Reasons -join " ") -Force
        } else {
            $manifest.status = "start-failed-inactive"
        }
        Write-JsonFile -Path $manifestPath -Value $manifest
        if ($recovery.Stopped -or $recovery.FinalState -eq "Inactive") {
            Remove-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
        }
        throw "pktmon start failed (exit $($started.ExitCode)). Automatic cleanup was attempted only when exact ownership could be proved; manifest status is '$($manifest.status)'. The owned filter was never removed. $($started.Text)"
    }

    $verification = Test-ManifestCaptureOwnership -Manifest $manifest
    $manifest | Add-Member -NotePropertyName ownershipEvidenceSha256 -NotePropertyValue $verification.Evidence.Sha256 -Force
    if (-not $verification.Verified) {
        $manifest.status = "active-unverified"
        $manifest | Add-Member -NotePropertyName recoveryReason -NotePropertyValue ($verification.Reasons -join " ") -Force
        Write-JsonFile -Path $manifestPath -Value $manifest
        throw "PktMon started, but exact ownership cannot be proved from live status. No global stop was issued. Inspect the recoverable manifest and PktMon manually: $($verification.Reasons -join ' ')"
    }

    $manifest.status = "active"
    $manifest | Add-Member -NotePropertyName startedUtc -NotePropertyValue ([DateTime]::UtcNow.ToString("o")) -Force
    try {
        Write-JsonFile -Path $manifestPath -Value $manifest
    } catch {
        $writeError = $_.Exception.Message
        $cleanup = Try-StopManifestCapture -Manifest $manifest
        if ($cleanup.Stopped -or $cleanup.FinalState -eq "Inactive") {
            Remove-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
        }
        throw "PktMon started but the active manifest could not be committed: $writeError. Verified cleanup stopped=$($cleanup.Stopped), finalState=$($cleanup.FinalState). The atomic starting manifest remains for recovery."
    }

    Write-Output $started.Text
    Write-Output "Started owned phase-2 capture '$RunId'."
    Write-Output "ETL: $etlPath"
}

function Invoke-CountersAction {
    Assert-Administrator
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        throw "-RunId is required for counters so output cannot be attached to the wrong capture."
    }
    $directory = Resolve-OutputDirectory -Path $OutputDirectory
    $manifest = Read-JsonFile (Get-ActiveManifestPath $directory)
    if ($null -eq $manifest -or [string](Get-RequiredProperty -Object $manifest -Name "status") -ne "active") {
        throw "No active phase-2 manifest exists in $directory. Refusing to label counters as an owned run."
    }
    Assert-ManifestSchema2 -Manifest $manifest
    if ([string](Get-RequiredProperty -Object $manifest -Name "runId") -ne $RunId) {
        throw "Active manifest RunId does not match '$RunId'. Refusing to write counters."
    }
    $captureId = [string](Get-RequiredProperty -Object $manifest -Name "captureId")
    $null = Assert-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId
    $verification = Test-ManifestCaptureOwnership -Manifest $manifest
    if (-not $verification.Verified) {
        throw "Exact PktMon ownership cannot be proved; counters were not read or labeled. $($verification.Reasons -join ' ')"
    }

    $result = Invoke-PktMonCapture -ArgumentList @("counters", "--type", "all", "--json")
    if ($result.ExitCode -ne 0) {
        throw "pktmon counters failed (exit $($result.ExitCode)): $($result.Text)"
    }
    $counterPath = Join-Path $directory "$RunId.counters-live.json"
    Write-NewTextFile -Path $counterPath -Text $result.Text
    Write-Output "Counters: $counterPath"
}

function Invoke-StopAction {
    Assert-Administrator
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        throw "-RunId is required for stop so a campaign directory alone cannot authorize a global PktMon stop."
    }
    $directory = Resolve-OutputDirectory -Path $OutputDirectory
    $manifestPath = Get-ActiveManifestPath $directory
    $manifest = Read-JsonFile $manifestPath
    if ($null -eq $manifest) {
        throw "No phase-2 manifest exists in $directory. Refusing to stop an unowned capture."
    }
    Assert-ManifestSchema2 -Manifest $manifest
    $manifestRunId = [string](Get-RequiredProperty -Object $manifest -Name "runId")
    if ($manifestRunId -ne $RunId) {
        throw "Manifest RunId '$manifestRunId' does not match requested RunId '$RunId'. Refusing to stop."
    }
    $manifestDirectory = [IO.Path]::GetFullPath([string](Get-RequiredProperty -Object $manifest -Name "campaignDirectory"))
    if (-not [string]::Equals($manifestDirectory, $directory, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest campaign directory '$manifestDirectory' does not match '$directory'. Refusing to stop."
    }
    $status = [string](Get-RequiredProperty -Object $manifest -Name "status")
    if ($status -notin @("starting", "active", "active-unverified", "start-failed-unverified", "start-invocation-failed-unverified", "stopping", "stop-failed")) {
        throw "Manifest status '$status' does not describe a recoverable live capture. Refusing to stop."
    }

    $captureId = [string](Get-RequiredProperty -Object $manifest -Name "captureId")
    $null = Assert-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId

    $claimPath = [string](Get-RequiredProperty -Object $manifest -Name "claimPath")
    $claim = Read-JsonFile $claimPath
    if ($null -eq $claim -or
        [string](Get-RequiredProperty -Object $claim -Name "captureId") -ne [string](Get-RequiredProperty -Object $manifest -Name "captureId") -or
        [string](Get-RequiredProperty -Object $claim -Name "runId") -ne $RunId -or
        -not [string]::Equals([string](Get-RequiredProperty -Object $claim -Name "host"),
                [Environment]::MachineName, [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals([IO.Path]::GetFullPath([string](Get-RequiredProperty -Object $claim -Name "campaignDirectory")),
                $directory, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Immutable RunId claim does not match the active manifest. Refusing to stop."
    }

    $verification = Test-ManifestCaptureOwnership -Manifest $manifest
    if (-not $verification.Verified) {
        throw "Exact PktMon ownership cannot be proved; no global stop was issued. $($verification.Reasons -join ' ')"
    }

    $counterPath = Join-Path $directory "$RunId.counters-final.json"
    $completedPath = Join-Path $directory "$RunId.capture.json"
    foreach ($path in @($counterPath, $completedPath)) {
        if (Test-Path -LiteralPath $path) {
            throw "Stop output already exists and will not be overwritten: $path"
        }
    }

    $manifest.status = "stopping"
    $manifest | Add-Member -NotePropertyName stopAttemptUtc -NotePropertyValue ([DateTime]::UtcNow.ToString("o")) -Force
    $manifest | Add-Member -NotePropertyName preStopEvidenceSha256 -NotePropertyValue $verification.Evidence.Sha256 -Force
    Write-JsonFile -Path $manifestPath -Value $manifest

    $counters = Invoke-PktMonCapture -ArgumentList @("counters", "--type", "all", "--json")
    if ($counters.ExitCode -eq 0) {
        Write-NewTextFile -Path $counterPath -Text $counters.Text
    } else {
        Write-Warning "Unable to save final counters before stop: $($counters.Text)"
        $counterPath = $null
    }

    $stopResult = Try-StopManifestCapture -Manifest $manifest
    if (-not $stopResult.Stopped) {
        $manifest.status = "stop-failed"
        $manifest | Add-Member -NotePropertyName stopFailure -NotePropertyValue (($stopResult.Verification.Reasons -join " ") + " exit=$($stopResult.ExitCode) finalState=$($stopResult.FinalState)") -Force
        Write-JsonFile -Path $manifestPath -Value $manifest
        throw "Owned capture did not reach a definitively inactive state; manifest retained for recovery. No filter was removed."
    }

    $manifest.status = "completed"
    $manifest | Add-Member -NotePropertyName stoppedUtc -NotePropertyValue ([DateTime]::UtcNow.ToString("o")) -Force
    $manifest | Add-Member -NotePropertyName countersPath -NotePropertyValue $counterPath -Force
    Write-NewJsonFile -Path $completedPath -Value $manifest
    Write-JsonFile -Path $manifestPath -Value $manifest
    Remove-CaptureLock -Directory $directory -Id $RunId -CaptureId $captureId

    Write-Output $stopResult.Output
    Write-Output "Stopped owned phase-2 capture '$RunId'."
    Write-Output "Global pktmon filters were left unchanged."
}

function Resolve-ConversionInput {
    $manifestPath = $null
    if (-not [string]::IsNullOrWhiteSpace($InputEtl)) {
        $inputPath = [IO.Path]::GetFullPath($InputEtl)
        $manifestPath = [IO.Path]::Combine(
                [IO.Path]::GetDirectoryName($inputPath),
                [IO.Path]::GetFileNameWithoutExtension($inputPath) + ".capture.json")
    } else {
        if ([string]::IsNullOrWhiteSpace($RunId)) {
            throw "convert requires either -InputEtl or both -OutputDirectory and -RunId."
        }
        $directory = Resolve-OutputDirectory -Path $OutputDirectory
        $inputPath = [IO.Path]::GetFullPath((Join-Path $directory "$RunId.etl"))
        $manifestPath = Join-Path $directory "$RunId.capture.json"
    }

    $manifest = Read-JsonFile $manifestPath
    if ($null -eq $manifest) {
        throw "A completed capture sidecar is required to prove ETL component semantics: $manifestPath"
    }
    Assert-ManifestSchema2 -Manifest $manifest
    if ([string](Get-RequiredProperty -Object $manifest -Name "status") -ne "completed") {
        throw "Capture sidecar is not completed: $manifestPath"
    }
    if ([string](Get-RequiredProperty -Object $manifest -Name "captureMode") -ne "etl") {
        throw "Counters-only captures have no ETL to convert."
    }
    $manifestEtl = [IO.Path]::GetFullPath([string](Get-RequiredProperty -Object $manifest -Name "etlPath"))
    if (-not [string]::Equals($manifestEtl, $inputPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "ETL path does not match its capture sidecar. Expected '$manifestEtl', got '$inputPath'."
    }
    $component = Get-RequiredProperty -Object $manifest -Name "component"
    return [pscustomobject]@{
        InputPath = $inputPath
        ManifestPath = $manifestPath
        Manifest = $manifest
        ComponentSelector = [string](Get-RequiredProperty -Object $component -Name "selector")
        ComponentValue = [string](Get-RequiredProperty -Object $component -Name "value")
    }
}

function Assert-ConversionComponentChoice {
    param(
        [Parameter(Mandatory = $true)][string]$Selector,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][int]$SelectedComponentId
    )
    if ($Selector -eq "name" -and
        [string]::Equals($Value, "all", [StringComparison]::OrdinalIgnoreCase) -and
        $SelectedComponentId -le 0) {
        throw "This ETL captured component=all. Pass an explicit -ComponentId greater than zero; converting all Appearances would double-count packets."
    }
    if ($Selector -eq "id" -and $SelectedComponentId -gt 0 -and
        [string]$SelectedComponentId -ne $Value) {
        throw "The ETL was captured for component ID $Value, but conversion requested component ID $SelectedComponentId."
    }
}

function Invoke-ConvertAction {
    $conversion = Resolve-ConversionInput
    $inputPath = $conversion.InputPath
    if (-not (Test-Path -LiteralPath $inputPath)) {
        throw "ETL input does not exist: $inputPath"
    }
    $inputItem = Get-Item -LiteralPath $inputPath
    if ($inputItem.PSIsContainer) {
        throw "ETL input is a directory: $inputPath"
    }
    Assert-ConversionComponentChoice -Selector $conversion.ComponentSelector `
            -Value $conversion.ComponentValue -SelectedComponentId $ComponentId

    $basePath = [IO.Path]::Combine($inputItem.DirectoryName, [IO.Path]::GetFileNameWithoutExtension($inputItem.Name))
    $pcapPath = "$basePath.pcapng"
    $statsPath = "$basePath.stats.txt"
    foreach ($path in @($pcapPath, $statsPath)) {
        if ((Test-Path -LiteralPath $path) -and -not $Overwrite) {
            throw "Conversion output already exists: $path. Pass -Overwrite only after verifying the target."
        }
    }

    $pcapArguments = @("etl2pcap", $inputPath, "--out", $pcapPath)
    if ($ComponentId -gt 0) {
        $pcapArguments += @("--component-id", [string]$ComponentId)
    }
    $pcap = Invoke-PktMonCapture -ArgumentList $pcapArguments
    if ($pcap.ExitCode -ne 0) {
        throw "pktmon etl2pcap failed (exit $($pcap.ExitCode)): $($pcap.Text)"
    }

    $stats = Invoke-PktMonCapture -ArgumentList @("etl2txt", $inputPath, "--stats")
    if ($stats.ExitCode -ne 0) {
        throw "pktmon etl2txt --stats failed (exit $($stats.ExitCode)): $($stats.Text)"
    }
    if ($Overwrite) {
        [IO.File]::WriteAllText($statsPath, $stats.Text + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    } else {
        Write-NewTextFile -Path $statsPath -Text $stats.Text
    }

    Write-Output $pcap.Text
    Write-Output "PCAPNG: $pcapPath"
    Write-Output "Stats: $statsPath"
}

function Invoke-SelfTestAction {
    $directory = Join-Path ([IO.Path]::GetTempPath()) "iv-phase2-pktmon-$([Guid]::NewGuid().ToString('N'))"
    $null = New-Item -ItemType Directory -Path $directory
    try {
        $jsonPath = Join-Path $directory "atomic.json"
        Write-JsonFile -Path $jsonPath -Value ([ordered]@{ version = 1 })
        Write-JsonFile -Path $jsonPath -Value ([ordered]@{ version = 2 })
        if ([int](Get-RequiredProperty -Object (Read-JsonFile $jsonPath) -Name "version") -ne 2) {
            throw "Atomic JSON replacement self-test failed."
        }

        $newPath = Join-Path $directory "create-new.txt"
        Write-NewTextFile -Path $newPath -Text "first"
        $overwriteRejected = $false
        try {
            Write-NewTextFile -Path $newPath -Text "second"
        } catch {
            $overwriteRejected = $true
        }
        if (-not $overwriteRejected) {
            throw "CreateNew overwrite self-test failed."
        }

        $lockCaptureId = [Guid]::NewGuid().ToString("D")
        $null = New-CaptureLock -Directory $directory -Id "lock-a" -CaptureId $lockCaptureId
        $concurrentCaptureRejected = $false
        try {
            $null = New-CaptureLock -Directory $directory -Id "lock-b" -CaptureId ([Guid]::NewGuid().ToString("D"))
        } catch {
            $concurrentCaptureRejected = $true
        }
        if (-not $concurrentCaptureRejected) {
            throw "Concurrent campaign capture lock self-test failed."
        }
        $null = Assert-CaptureLock -Directory $directory -Id "lock-a" -CaptureId $lockCaptureId
        Remove-CaptureLock -Directory $directory -Id "lock-a" -CaptureId $lockCaptureId

        $captureId = [Guid]::NewGuid().ToString("D")
        $null = New-RunClaim -Directory $directory -Id "selftest" -CaptureId $captureId
        $duplicateClaimRejected = $false
        try {
            $null = New-RunClaim -Directory $directory -Id "selftest" -CaptureId ([Guid]::NewGuid().ToString("D"))
        } catch {
            $duplicateClaimRejected = $true
        }
        if (-not $duplicateClaimRejected) {
            throw "Duplicate RunId claim self-test failed."
        }

        $allWithoutComponentRejected = $false
        try {
            Assert-ConversionComponentChoice -Selector "name" -Value "all" -SelectedComponentId 0
        } catch {
            $allWithoutComponentRejected = $true
        }
        if (-not $allWithoutComponentRejected) {
            throw "component=all conversion guard self-test failed."
        }
        Assert-ConversionComponentChoice -Selector "name" -Value "all" -SelectedComponentId 12
        $mismatchedCapturedComponentRejected = $false
        try {
            Assert-ConversionComponentChoice -Selector "id" -Value "12" -SelectedComponentId 13
        } catch {
            $mismatchedCapturedComponentRejected = $true
        }
        if (-not $mismatchedCapturedComponentRejected) {
            throw "Captured component ID mismatch self-test failed."
        }

        [pscustomobject]@{
            selfTest = $true
            passed = $true
            atomicManifestReplace = $true
            createNewRejectsOverwrite = $overwriteRejected
            concurrentCaptureRejected = $concurrentCaptureRejected
            duplicateRunIdRejected = $duplicateClaimRejected
            allRequiresComponentId = $allWithoutComponentRejected
            mismatchedComponentIdRejected = $mismatchedCapturedComponentRejected
        } | ConvertTo-Json
    } finally {
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

switch ($Action.ToLowerInvariant()) {
    "status" { Invoke-StatusAction }
    "start" { Invoke-StartAction }
    "stop" { Invoke-StopAction }
    "counters" { Invoke-CountersAction }
    "convert" { Invoke-ConvertAction }
    "selftest" { Invoke-SelfTestAction }
    default { throw "Unsupported action: $Action" }
}
