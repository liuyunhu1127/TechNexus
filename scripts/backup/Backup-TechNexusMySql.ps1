[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TargetRoot,
    [string]$CredentialFile = 'D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt',
    [string]$MySqlBin = 'D:\DevelopTools\mysql-8.4.13-winx64\bin',
    [string]$BackupKeyBase64 = $env:TECHNEXUS_BACKUP_KEY_BASE64,
    [ValidateRange(1, 3650)][int]$RetentionDays = 30,
    [switch]$AllowLocalTargetForTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TechNexusBackup.Common.psm1') -Force

$key = $null
$defaultsFile = $null
$work = $null
$targetClass = Assert-TechNexusRemoteTarget -TargetRoot $TargetRoot -AllowLocalTargetForTest:$AllowLocalTargetForTest
if (-not $BackupKeyBase64) { throw 'TECHNEXUS_BACKUP_KEY_BASE64 is required.' }
$key = Get-TechNexusBackupKey -Base64Key $BackupKeyBase64
$credential = Get-TechNexusDatabaseCredential -CredentialFile $CredentialFile
$defaultsFile = New-TechNexusMySqlDefaultsFile -Credential $credential
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$backupId = "technexus-$stamp-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$work = Join-Path ([IO.Path]::GetTempPath()) $backupId
$remoteDirectory = Join-Path $TargetRoot ([DateTime]::UtcNow.ToString('yyyy\MM\dd'))
$dumpPath = Join-Path $work "$backupId.sql"
$gzipPath = "$dumpPath.gz"
$encryptedPath = Join-Path $work "$backupId.sql.gz.tnxbak"
$manifestPath = Join-Path $work "$backupId.manifest.json"

New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $dumpTool = Join-Path $MySqlBin 'mysqldump.exe'
    $mysqlTool = Join-Path $MySqlBin 'mysql.exe'
    foreach ($tool in @($dumpTool, $mysqlTool)) {
        if (-not (Test-Path -LiteralPath $tool)) { throw "Required MySQL tool not found: $tool" }
    }

    $metadataSql = @"
SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1),'NONE');
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE';
SELECT CONCAT('tn_article=', COUNT(*)) FROM tn_article;
SELECT CONCAT('tn_demand=', COUNT(*)) FROM tn_demand;
SELECT CONCAT('tn_file_object=', COUNT(*)) FROM tn_file_object;
SELECT CONCAT('tn_user=', COUNT(*)) FROM tn_user;
SELECT @@version;
SELECT @@global.log_bin;
SELECT @@global.binlog_format;
SELECT @@global.binlog_expire_logs_seconds;
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();
"@
    $metadata = Invoke-TechNexusProcess -FilePath $mysqlTool -Arguments @(
        "--defaults-extra-file=$defaultsFile", '--batch', '--skip-column-names', "--database=$($credential.Database)", "--execute=$metadataSql"
    )
    $metadataLines = @($metadata -split "`r?`n" | Where-Object { $_ })
    if ($metadataLines.Count -lt 13) { throw 'Could not read complete database metadata before backup.' }

    $dumpArguments = [Collections.Generic.List[string]]::new()
    foreach ($argument in @(
        "--defaults-extra-file=$defaultsFile", '--single-transaction', '--quick', '--hex-blob',
        '--set-gtid-purged=OFF', '--default-character-set=utf8mb4', '--no-tablespaces', "--result-file=$dumpPath"
    )) { $dumpArguments.Add($argument) }
    if ([int]$metadataLines[10] -gt 0) { $dumpArguments.Add('--routines') }
    if ([int]$metadataLines[11] -gt 0) { $dumpArguments.Add('--events') }
    if ([int]$metadataLines[12] -gt 0) { $dumpArguments.Add('--triggers') } else { $dumpArguments.Add('--skip-triggers') }
    $dumpArguments.Add($credential.Database)

    [void](Invoke-TechNexusProcess -FilePath $dumpTool -Arguments $dumpArguments.ToArray())
    if (-not (Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -eq 0) {
        throw 'mysqldump produced no data.'
    }

    Compress-TechNexusBackup -InputPath $dumpPath -OutputPath $gzipPath
    Protect-TechNexusBackup -InputPath $gzipPath -OutputPath $encryptedPath -MasterKey $key
    $hash = (Get-FileHash -LiteralPath $encryptedPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $createdUtc = [DateTime]::UtcNow
    $manifest = [ordered]@{
        formatVersion = 1
        backupId = $backupId
        database = $credential.Database
        createdUtc = $createdUtc.ToString('o')
        sourceHost = [Environment]::MachineName
        targetClass = $targetClass
        fileName = [IO.Path]::GetFileName($encryptedPath)
        encryption = 'AES-256-CBC + HMAC-SHA-256 (encrypt-then-MAC)'
        sha256 = $hash
        sizeBytes = (Get-Item -LiteralPath $encryptedPath).Length
        flywayVersion = $metadataLines[0]
        baseTableCount = [int]$metadataLines[1]
        keyTableRowCounts = @($metadataLines[2..5])
        mysqlVersion = $metadataLines[6]
        binaryLogEnabled = ($metadataLines[7] -eq '1')
        binaryLogFormat = $metadataLines[8]
        binaryLogExpireSeconds = [long]$metadataLines[9]
        storedRoutineCount = [int]$metadataLines[10]
        eventCount = [int]$metadataLines[11]
        triggerCount = [int]$metadataLines[12]
        retentionDays = $RetentionDays
        rpoTargetHours = 24
        restoreRtoTargetHours = 4
        gatingEvidence = ($targetClass -eq 'REMOTE_UNC')
    }
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Path $remoteDirectory -Force | Out-Null
    foreach ($source in @($encryptedPath, $manifestPath)) {
        $destination = Join-Path $remoteDirectory ([IO.Path]::GetFileName($source))
        $partial = "$destination.partial"
        Copy-Item -LiteralPath $source -Destination $partial -Force
        Move-Item -LiteralPath $partial -Destination $destination -Force
    }
    $remoteBackup = Join-Path $remoteDirectory ([IO.Path]::GetFileName($encryptedPath))
    $remoteHash = (Get-FileHash -LiteralPath $remoteBackup -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($remoteHash -ne $hash) { throw 'Remote backup hash verification failed.' }

    $status = [ordered]@{
        status = 'SUCCESS'
        backupId = $backupId
        completedUtc = [DateTime]::UtcNow.ToString('o')
        targetClass = $targetClass
        manifestPath = (Join-Path $remoteDirectory ([IO.Path]::GetFileName($manifestPath)))
        sha256Verified = $true
        gatingEvidence = ($targetClass -eq 'REMOTE_UNC')
    }
    $statusPath = Join-Path $TargetRoot 'technexus-backup-status.json'
    $statusTemp = "$statusPath.partial"
    [IO.File]::WriteAllText($statusTemp, ($status | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $statusTemp -Destination $statusPath -Force
    $status | ConvertTo-Json -Compress
} catch {
    $failure = [ordered]@{
        status = 'FAILED'
        completedUtc = [DateTime]::UtcNow.ToString('o')
        targetClass = $targetClass
        error = $_.Exception.Message
        gatingEvidence = $false
    }
    try {
        if (Test-Path -LiteralPath $TargetRoot) {
            $failurePath = Join-Path $TargetRoot 'technexus-backup-status.json'
            [IO.File]::WriteAllText($failurePath, ($failure | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
        }
    } catch {
        # The original backup failure remains authoritative when the target is also unwritable.
    }
    ($failure | ConvertTo-Json -Compress) | Write-Error
    throw
} finally {
    if ($key) { [Array]::Clear($key, 0, $key.Length) }
    if ($defaultsFile -and (Test-Path -LiteralPath $defaultsFile)) { Remove-Item -LiteralPath $defaultsFile -Force }
    if ($work -and (Test-Path -LiteralPath $work)) { Remove-Item -LiteralPath $work -Recurse -Force }
}
