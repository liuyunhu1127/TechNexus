param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 1800,

    [ValidateRange(1, 300)]
    [int]$IntervalSeconds = 5
)

$ErrorActionPreference = 'Stop'
$directory = Split-Path -Parent $OutputPath
if ($directory) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
$rows = [Collections.Generic.List[object]]::new()
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($DurationSeconds)

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $cpuPercent = $null
    $freeMemoryBytes = $null
    $totalMemoryBytes = $null
    $disk = Get-PSDrive -PSProvider FileSystem | Measure-Object -Property Free -Sum
    try {
        $counter = Get-Counter '\Processor(_Total)\% Processor Time', '\Memory\Available Bytes' -ErrorAction Stop
        $cpuSample = $counter.CounterSamples | Where-Object Path -Like '*processor(_total)*' | Select-Object -First 1
        $memorySample = $counter.CounterSamples | Where-Object Path -Like '*memory*available bytes' | Select-Object -First 1
        if ($cpuSample) { $cpuPercent = [math]::Round([double]$cpuSample.CookedValue, 3) }
        if ($memorySample) { $freeMemoryBytes = [long]$memorySample.CookedValue }
    } catch { }
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        $totalMemoryBytes = [long]$os.TotalVisibleMemorySize * 1KB
        if ($null -eq $freeMemoryBytes) { $freeMemoryBytes = [long]$os.FreePhysicalMemory * 1KB }
    } catch { }
    $networkBytesPerSecond = $null
    try {
        $counter = Get-Counter '\Network Interface(*)\Bytes Total/sec' -ErrorAction Stop
        $networkBytesPerSecond = [math]::Round((($counter.CounterSamples | Measure-Object -Property CookedValue -Sum).Sum), 3)
    } catch { }
    $rows.Add([pscustomobject]@{
        collectedAt = [DateTimeOffset]::UtcNow.ToString('o')
        cpuPercent = $cpuPercent
        totalMemoryBytes = $totalMemoryBytes
        freeMemoryBytes = $freeMemoryBytes
        fixedDiskFreeBytes = [long]$disk.Sum
        networkBytesPerSecond = $networkBytesPerSecond
    })
    Start-Sleep -Seconds $IntervalSeconds
}

$rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding utf8
[pscustomobject]@{
    outputPath = [IO.Path]::GetFullPath($OutputPath)
    samples = $rows.Count
    durationSeconds = $DurationSeconds
    intervalSeconds = $IntervalSeconds
}
