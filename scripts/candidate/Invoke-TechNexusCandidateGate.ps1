param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',

    [ValidateSet('LOCAL_REHEARSAL', 'CANDIDATE_GATE')]
    [string]$Classification = 'LOCAL_REHEARSAL',

    [string]$OutputDirectory = '',

    [int]$SustainedSeconds = 60,
    [int]$BurstSeconds = 30,
    [int]$StabilitySeconds = 1800,
    [int]$MetricsIntervalSeconds = 5,

    [string]$CredentialFile = 'D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt',
    [string]$MySqlPath = 'D:\DevelopTools\mysql-8.4.13-winx64\bin\mysql.exe',

    [switch]$SkipDatabaseSnapshot
)

$ErrorActionPreference = 'Stop'
$scriptRoot = $PSScriptRoot
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $scriptRoot '..\..'))
$stamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot "artifacts\candidate-gate\$stamp"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

if ($Classification -eq 'CANDIDATE_GATE') {
    if ($SustainedSeconds -lt 60 -or $BurstSeconds -lt 30 -or $StabilitySeconds -lt 1800) {
        throw 'CANDIDATE_GATE requires SustainedSeconds >= 60, BurstSeconds >= 30, and StabilitySeconds >= 1800.'
    }
    if (-not $env:TECHNEXUS_METRICS_TOKEN) {
        throw 'CANDIDATE_GATE requires TECHNEXUS_METRICS_TOKEN so JVM, GC, thread, and Hikari metrics can be captured.'
    }
    if ($SkipDatabaseSnapshot) {
        throw 'CANDIDATE_GATE does not allow SkipDatabaseSnapshot.'
    }
}

$inventoryPath = Join-Path $OutputDirectory 'load-generator-inventory.json'
& (Join-Path $scriptRoot 'Get-TechNexusCandidateInventory.ps1') `
    -Classification $Classification `
    -Role LOAD_GENERATOR `
    -BaseUrl $BaseUrl `
    -OutputPath $inventoryPath | Out-Null

$databaseSnapshots = [ordered]@{ available = $false; before = $null; after = $null; error = $null }
$defaultsPath = $null
function Get-DatabaseSnapshot {
    $sql = @"
SELECT 'threads_connected', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'
UNION ALL SELECT 'threads_running', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'
UNION ALL SELECT 'slow_queries', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Slow_queries'
UNION ALL SELECT 'aborted_connects', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Aborted_connects'
UNION ALL SELECT 'questions', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Questions'
UNION ALL SELECT 'max_connections', VARIABLE_VALUE FROM performance_schema.global_variables WHERE VARIABLE_NAME='max_connections';
SELECT CONCAT('file_queue_', LOWER(state)), COUNT(*) FROM tn_file_object GROUP BY state;
SELECT CONCAT('batch_queue_', LOWER(status)), COUNT(*) FROM tn_batch_job GROUP BY status;
"@
    $result = & $MySqlPath "--defaults-extra-file=$defaultsPath" '--batch' '--skip-column-names' "--database=$($script:databaseCredential.Database)" "--execute=$sql" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "MySQL snapshot failed: $($result -join ' ')" }
    $values = [ordered]@{}
    foreach ($line in $result) {
        $parts = [string]$line -split "`t", 2
        if ($parts.Count -eq 2) { $values[$parts[0]] = $parts[1] }
    }
    return $values
}

try {
    if ($SkipDatabaseSnapshot) {
        $databaseSnapshots.error = 'Skipped explicitly for a non-gating local rehearsal.'
    } elseif ((Test-Path -LiteralPath $CredentialFile) -and (Test-Path -LiteralPath $MySqlPath)) {
        Import-Module (Join-Path $repositoryRoot 'scripts\backup\TechNexusBackup.Common.psm1') -Force
        $script:databaseCredential = Get-TechNexusDatabaseCredential -CredentialFile $CredentialFile
        $defaultsPath = New-TechNexusMySqlDefaultsFile -Credential $script:databaseCredential
        $databaseSnapshots.before = Get-DatabaseSnapshot
        $databaseSnapshots.available = $true
    } elseif ($Classification -eq 'CANDIDATE_GATE') {
        throw 'Candidate database credentials or mysql client are unavailable.'
    }

    $nodeArguments = @(
        (Join-Path $scriptRoot 'candidate-load.mjs'),
        '--base-url', $BaseUrl,
        '--output-directory', $OutputDirectory,
        '--classification', $Classification.ToLowerInvariant().Replace('_', '-'),
        '--sustained-seconds', $SustainedSeconds,
        '--burst-seconds', $BurstSeconds,
        '--stability-seconds', $StabilitySeconds,
        '--metrics-interval-seconds', $MetricsIntervalSeconds
    )
    & node @nodeArguments
    $loadExitCode = $LASTEXITCODE
    if ($databaseSnapshots.available) { $databaseSnapshots.after = Get-DatabaseSnapshot }
} catch {
    $databaseSnapshots.error = $_.Exception.Message
    throw
} finally {
    if ($defaultsPath -and (Test-Path -LiteralPath $defaultsPath)) {
        Remove-Item -LiteralPath $defaultsPath -Force
    }
    [IO.File]::WriteAllText(
        (Join-Path $OutputDirectory 'database-snapshots.json'),
        (($databaseSnapshots | ConvertTo-Json -Depth 8) + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}

$loadSummary = Get-Content -LiteralPath (Join-Path $OutputDirectory 'load-summary.json') -Raw | ConvertFrom-Json
$manifest = [ordered]@{
    schemaVersion = 1
    classification = $Classification
    gatingEvidence = $Classification -eq 'CANDIDATE_GATE'
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
    baseUrl = $BaseUrl
    outputDirectory = $OutputDirectory
    artifacts = @(
        'load-generator-inventory.json',
        'load-summary.json',
        'request-samples.csv',
        'runtime-metrics.csv',
        'database-snapshots.json'
    )
    automatedChecksPassed = [bool]$loadSummary.automatedChecksPassed -and $loadExitCode -eq 0
    telemetryCaptured = [bool]$loadSummary.telemetry.metricsAccessible
    databaseSnapshotCaptured = [bool]$databaseSnapshots.available
    result = if ($Classification -eq 'LOCAL_REHEARSAL') {
        'NON_GATING_LOCAL_REHEARSAL'
    } elseif ($loadSummary.candidateReviewReady -and $databaseSnapshots.available) {
        'CANDIDATE_REVIEW_REQUIRED'
    } else {
        'CANDIDATE_FAILED_OR_INCOMPLETE'
    }
    approvalRule = 'Only a remote candidate run with all automated checks passing plus manual review of host, JVM/GC/thread/Hikari, SQL, disk, network and queue trends may close TNX-GATE-004.'
}
[IO.File]::WriteAllText(
    (Join-Path $OutputDirectory 'manifest.json'),
    (($manifest | ConvertTo-Json -Depth 8) + "`n"),
    [Text.UTF8Encoding]::new($false)
)
$manifest
if ($loadExitCode -ne 0) { exit $loadExitCode }
