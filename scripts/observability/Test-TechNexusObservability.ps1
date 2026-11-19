param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',

    [ValidateSet('LOCAL_REHEARSAL', 'CANDIDATE_GATE')]
    [string]$Classification = 'LOCAL_REHEARSAL',

    [string]$BearerToken = $env:TECHNEXUS_METRICS_TOKEN,
    [string]$DiskPath = '.',
    [double]$MinimumDiskFreePercent = 20,
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$uri = [Uri]$BaseUrl
if ($Classification -eq 'CANDIDATE_GATE' -and @('localhost', '127.0.0.1', '::1') -contains $uri.Host) {
    throw 'CANDIDATE_GATE refuses a loopback BaseUrl.'
}
if (-not $BearerToken) { throw 'A short-lived operations/admin BearerToken is required.' }

function Get-PrometheusValue {
    param([string]$Text, [string]$MetricPattern)
    $line = $Text -split "`r?`n" | Where-Object { $_ -match $MetricPattern } | Select-Object -First 1
    if (-not $line) { return $null }
    $parts = $line -split '\s+'
    $value = 0.0
    if ([double]::TryParse($parts[-1], [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
        return $value
    }
    return $null
}

$authority = $uri.GetLeftPart([UriPartial]::Authority)
$headers = @{ Authorization = "Bearer $BearerToken" }
$liveness = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 "$authority/actuator/health/liveness"
$readiness = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 "$authority/actuator/health/readiness"
$metricsResponse = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 -Headers $headers "$authority/actuator/prometheus"
$metrics = $metricsResponse.Content

$requiredPatterns = [ordered]@{
    databaseUp = '^technexus_database_up\s'
    databasePing = '^technexus_database_ping_seconds_count\s'
    queues = '^technexus_queue_depth\{'
    jvmMemory = '^jvm_memory_used_bytes\{'
    jvmThreads = '^jvm_threads_live_threads\s'
    hikariActive = '^hikaricp_connections_active\{'
    hikariPending = '^hikaricp_connections_pending\{'
    httpRequests = '^http_server_requests_seconds_count\{'
}
$missing = [Collections.Generic.List[string]]::new()
foreach ($entry in $requiredPatterns.GetEnumerator()) {
    if ($metrics -notmatch "(?m)$($entry.Value)") { $missing.Add($entry.Key) }
}

$databaseUp = Get-PrometheusValue $metrics $requiredPatterns.databaseUp
$hikariPending = Get-PrometheusValue $metrics $requiredPatterns.hikariPending
$drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot((Resolve-Path $DiskPath)).TrimEnd('\').TrimEnd(':'))
$diskFreePercent = if (($drive.Used + $drive.Free) -gt 0) {
    [math]::Round(($drive.Free / ($drive.Used + $drive.Free)) * 100, 2)
} else { $null }

$findings = [Collections.Generic.List[object]]::new()
if ($missing.Count) { $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'METRICS_MISSING'; detail = ($missing -join ',') }) }
if ($databaseUp -ne 1) { $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'DATABASE_DOWN'; detail = "value=$databaseUp" }) }
if ($null -ne $hikariPending -and $hikariPending -gt 0) { $findings.Add([ordered]@{ severity = 'WARNING'; code = 'HIKARI_PENDING'; detail = "value=$hikariPending" }) }
if ($null -ne $diskFreePercent -and $diskFreePercent -lt $MinimumDiskFreePercent) {
    $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'DISK_LOW'; detail = "freePercent=$diskFreePercent" })
}

$critical = @($findings | Where-Object severity -eq 'CRITICAL').Count
$result = [ordered]@{
    schemaVersion = 1
    classification = $Classification
    gatingEvidence = $Classification -eq 'CANDIDATE_GATE'
    checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    baseUrl = $BaseUrl
    livenessStatus = [int]$liveness.StatusCode
    readinessStatus = [int]$readiness.StatusCode
    metricsStatus = [int]$metricsResponse.StatusCode
    databaseUp = $databaseUp
    hikariPending = $hikariPending
    diskFreePercent = $diskFreePercent
    minimumDiskFreePercent = $MinimumDiskFreePercent
    missingMetrics = $missing
    findings = $findings
    result = if ($critical -eq 0) { if ($Classification -eq 'CANDIDATE_GATE') { 'CANDIDATE_REVIEW_REQUIRED' } else { 'PASS_LOCAL' } } else { 'FAIL' }
}

if ($OutputPath) {
    $directory = Split-Path -Parent $OutputPath
    if ($directory) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    [IO.File]::WriteAllText($OutputPath, (($result | ConvertTo-Json -Depth 8) + "`n"), [Text.UTF8Encoding]::new($false))
}
$result
if ($critical -gt 0) { exit 1 }
