[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ManifestPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^technexus_restore_[a-z0-9_]+$')][string]$RestoreDatabase,
    [string]$CredentialFile = 'D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt',
    [string]$MySqlBin = 'D:\DevelopTools\mysql-8.4.13-winx64\bin',
    [string]$BackupKeyBase64 = $env:TECHNEXUS_BACKUP_KEY_BASE64,
    [switch]$CreateDatabase,
    [switch]$AllowLocalSourceForTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TechNexusBackup.Common.psm1') -Force

$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$key = $null
$defaultsFile = $null
$work = $null
$sourceClass = Assert-TechNexusRemoteTarget -TargetRoot (Split-Path -Parent $ManifestPath) -AllowLocalTargetForTest:$AllowLocalSourceForTest
if (-not $BackupKeyBase64) { throw 'TECHNEXUS_BACKUP_KEY_BASE64 is required.' }
$key = Get-TechNexusBackupKey -Base64Key $BackupKeyBase64
$credential = Get-TechNexusDatabaseCredential -CredentialFile $CredentialFile
$defaultsFile = New-TechNexusMySqlDefaultsFile -Credential $credential
$work = Join-Path ([IO.Path]::GetTempPath()) ("technexus-restore-{0}" -f [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

try {
    $manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $backupPath = Join-Path (Split-Path -Parent $ManifestPath) $manifest.fileName
    if (-not (Test-Path -LiteralPath $backupPath)) { throw 'Backup file referenced by the manifest does not exist.' }
    $hash = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $manifest.sha256) { throw 'Backup SHA-256 does not match the manifest.' }

    $gzipPath = Join-Path $work 'restore.sql.gz'
    $sqlPath = Join-Path $work 'restore.sql'
    Unprotect-TechNexusBackup -InputPath $backupPath -OutputPath $gzipPath -MasterKey $key
    Expand-TechNexusBackup -InputPath $gzipPath -OutputPath $sqlPath

    $mysqlTool = Join-Path $MySqlBin 'mysql.exe'
    if (-not (Test-Path -LiteralPath $mysqlTool)) { throw "Required MySQL tool not found: $mysqlTool" }
    if ($CreateDatabase) {
        [void](Invoke-TechNexusProcess -FilePath $mysqlTool -Arguments @(
            "--defaults-extra-file=$defaultsFile", '--batch', "--execute=CREATE DATABASE IF NOT EXISTS ``$RestoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
        ))
    }
    [void](Invoke-TechNexusProcess -FilePath $mysqlTool -Arguments @(
        "--defaults-extra-file=$defaultsFile", '--database', $RestoreDatabase
    ) -StandardInputPath $sqlPath)

    $validationSql = @"
SELECT COALESCE((SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1),'NONE');
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE';
SELECT CONCAT('tn_article=', COUNT(*)) FROM tn_article;
SELECT CONCAT('tn_demand=', COUNT(*)) FROM tn_demand;
SELECT CONCAT('tn_file_object=', COUNT(*)) FROM tn_file_object;
SELECT CONCAT('tn_user=', COUNT(*)) FROM tn_user;
"@
    $validation = Invoke-TechNexusProcess -FilePath $mysqlTool -Arguments @(
        "--defaults-extra-file=$defaultsFile", '--batch', '--skip-column-names', "--database=$RestoreDatabase", "--execute=$validationSql"
    )
    $lines = @($validation -split "`r?`n" | Where-Object { $_ })
    if ($lines.Count -lt 6) { throw 'Restore validation returned incomplete metadata.' }
    $flywayMatches = ($lines[0] -eq [string]$manifest.flywayVersion)
    $tableCountMatches = ([int]$lines[1] -eq [int]$manifest.baseTableCount)
    $keyRows = @($lines[2..5])
    $keyRowsMatch = (($keyRows | ConvertTo-Json -Compress) -eq (@($manifest.keyTableRowCounts) | ConvertTo-Json -Compress))
    if (-not $flywayMatches -or -not $tableCountMatches -or -not $keyRowsMatch) {
        throw "Restore validation mismatch: Flyway=$($lines[0]), baseTables=$($lines[1]), keyRowsMatch=$keyRowsMatch."
    }
    $stopwatch.Stop()
    [pscustomobject]@{
        status = 'SUCCESS'
        backupId = $manifest.backupId
        restoredUtc = [DateTime]::UtcNow.ToString('o')
        sourceClass = $sourceClass
        restoreDatabase = $RestoreDatabase
        flywayVersion = $lines[0]
        baseTableCount = [int]$lines[1]
        keyTableRowCounts = $keyRows
        sha256Verified = $true
        rtoSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
        gatingEvidence = ($sourceClass -eq 'REMOTE_UNC')
    } | ConvertTo-Json -Depth 5 -Compress
} finally {
    if ($key) { [Array]::Clear($key, 0, $key.Length) }
    if ($defaultsFile -and (Test-Path -LiteralPath $defaultsFile)) { Remove-Item -LiteralPath $defaultsFile -Force }
    if ($work -and (Test-Path -LiteralPath $work)) { Remove-Item -LiteralPath $work -Recurse -Force }
}
