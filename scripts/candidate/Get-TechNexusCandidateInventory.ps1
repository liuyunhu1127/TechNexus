param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('LOCAL_REHEARSAL', 'CANDIDATE_GATE')]
    [string]$Classification,

    [ValidateSet('LOAD_GENERATOR', 'TARGET_HOST', 'DATABASE_HOST')]
    [string]$Role = 'LOAD_GENERATOR',

    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

function Get-CommandVersion {
    param([string]$Command, [string[]]$Arguments)
    try {
        $resolved = Get-Command $Command -ErrorAction Stop
        $text = & $resolved.Source @Arguments 2>&1 | Out-String
        [pscustomobject]@{ available = $true; path = $resolved.Source; version = $text.Trim() }
    } catch {
        [pscustomobject]@{ available = $false; path = $null; version = $null }
    }
}

$uri = [Uri]$BaseUrl
$loopbackNames = @('localhost', '127.0.0.1', '::1')
$isLoopback = $loopbackNames -contains $uri.Host
if ($Classification -eq 'CANDIDATE_GATE' -and $isLoopback) {
    throw 'CANDIDATE_GATE refuses a loopback BaseUrl. Run the load generator from a separate host against the candidate URL.'
}

$inventoryWarnings = [Collections.Generic.List[string]]::new()
$computer = $null
$os = $null
$processors = @()
try {
    $computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    $processors = @(Get-CimInstance Win32_Processor -ErrorAction Stop)
} catch {
    $inventoryWarnings.Add("CIM host inventory unavailable: $($_.Exception.GetType().Name)")
}
$disks = @(Get-PSDrive -PSProvider FileSystem | ForEach-Object {
    [pscustomobject]@{
        device = $_.Name
        root = $_.Root
        sizeBytes = if ($null -ne $_.Used -and $null -ne $_.Free) { [long]$_.Used + [long]$_.Free } else { $null }
        freeBytes = if ($null -ne $_.Free) { [long]$_.Free } else { $null }
    }
})
$adapters = @()
try {
    $adapters = @(Get-NetAdapter -Physical -ErrorAction Stop | ForEach-Object {
        [pscustomobject]@{
            name = $_.Name
            interfaceDescription = $_.InterfaceDescription
            status = [string]$_.Status
            linkSpeed = [string]$_.LinkSpeed
            macAddress = $_.MacAddress
        }
    })
} catch {
    $inventoryWarnings.Add("Physical network adapter inventory unavailable: $($_.Exception.GetType().Name)")
}

$health = [ordered]@{ reachable = $false; statusCode = $null; bodyStatus = $null; error = $null }
try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "$($uri.GetLeftPart([UriPartial]::Authority))/actuator/health"
    $health.reachable = $true
    $health.statusCode = [int]$response.StatusCode
    try { $health.bodyStatus = ($response.Content | ConvertFrom-Json).status } catch { }
} catch {
    $health.error = $_.Exception.GetType().Name
}

$inventory = [ordered]@{
    schemaVersion = 1
    classification = $Classification
    role = $Role
    gatingEvidence = $Classification -eq 'CANDIDATE_GATE'
    collectedAt = [DateTimeOffset]::UtcNow.ToString('o')
    baseUrl = $BaseUrl
    host = [ordered]@{
        computerName = $env:COMPUTERNAME
        manufacturer = if ($computer) { $computer.Manufacturer } else { $null }
        model = if ($computer) { $computer.Model } else { $null }
        logicalProcessors = if ($computer) { [int]$computer.NumberOfLogicalProcessors } else { [Environment]::ProcessorCount }
        totalMemoryBytes = if ($computer) { [long]$computer.TotalPhysicalMemory } else { $null }
        processors = @($processors | ForEach-Object {
            [ordered]@{ name = $_.Name.Trim(); cores = [int]$_.NumberOfCores; logicalProcessors = [int]$_.NumberOfLogicalProcessors }
        })
        operatingSystem = [ordered]@{
            caption = if ($os) { $os.Caption } else { [Runtime.InteropServices.RuntimeInformation]::OSDescription }
            version = if ($os) { $os.Version } else { [Environment]::OSVersion.Version.ToString() }
            buildNumber = if ($os) { $os.BuildNumber } else { $null }
            architecture = if ($os) { $os.OSArchitecture } else { [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }
        }
        disks = $disks
        physicalNetworkAdapters = $adapters
    }
    tools = [ordered]@{
        java = Get-CommandVersion java @('-version')
        node = Get-CommandVersion node @('--version')
        mysql = Get-CommandVersion mysql @('--version')
        docker = Get-CommandVersion docker @('--version')
    }
    targetHealth = $health
    warnings = $inventoryWarnings
    interpretation = if ($Classification -eq 'CANDIDATE_GATE') {
        "This file inventories role $Role. Capture separate files for LOAD_GENERATOR, TARGET_HOST, and DATABASE_HOST when they are different machines."
    } else {
        'LOCAL_REHEARSAL only. This inventory is not candidate-server evidence.'
    }
}

$directory = Split-Path -Parent $OutputPath
if ($directory) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
[IO.File]::WriteAllText($OutputPath, (($inventory | ConvertTo-Json -Depth 12) + "`n"), [Text.UTF8Encoding]::new($false))
$inventory
