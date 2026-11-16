[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TargetRoot,
    [int]$MaximumAgeHours = 26,
    [int]$MinimumFreePercent = 20,
    [string]$WebhookUri,
    [switch]$AllowLocalTargetForTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TechNexusBackup.Common.psm1') -Force

$targetClass = Assert-TechNexusRemoteTarget -TargetRoot $TargetRoot -AllowLocalTargetForTest:$AllowLocalTargetForTest
$problems = [Collections.Generic.List[string]]::new()
$latest = Get-ChildItem -LiteralPath $TargetRoot -Filter '*.manifest.json' -File -Recurse |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $latest) {
    $problems.Add('No backup manifest exists.')
} else {
    $manifest = Get-Content -LiteralPath $latest.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $ageHours = ([DateTime]::UtcNow - [DateTime]::Parse($manifest.createdUtc).ToUniversalTime()).TotalHours
    if ($ageHours -gt $MaximumAgeHours) { $problems.Add("Latest backup age is $([Math]::Round($ageHours,2)) hours.") }
    $backupPath = Join-Path $latest.DirectoryName $manifest.fileName
    if (-not (Test-Path -LiteralPath $backupPath)) {
        $problems.Add('Latest backup file is missing.')
    } elseif ((Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256) {
        $problems.Add('Latest backup SHA-256 verification failed.')
    }
}

$freePercent = $null
try {
    if ($TargetRoot.StartsWith('\\') -and $IsWindows) {
        if (-not ('TechNexusBackup.NativeDisk' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace TechNexusBackup {
    public static class NativeDisk {
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        public static extern bool GetDiskFreeSpaceEx(
            string directory,
            out ulong freeBytesAvailable,
            out ulong totalBytes,
            out ulong totalFreeBytes);
    }
}
'@
        }
        [ulong]$available = 0
        [ulong]$total = 0
        [ulong]$totalFree = 0
        if (-not [TechNexusBackup.NativeDisk]::GetDiskFreeSpaceEx($TargetRoot, [ref]$available, [ref]$total, [ref]$totalFree)) {
            throw "GetDiskFreeSpaceEx failed with Win32 error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())."
        }
        if ($total -gt 0) { $freePercent = [Math]::Round(($available * 100.0 / $total), 2) }
    } else {
        $root = [IO.Path]::GetPathRoot((Resolve-Path -LiteralPath $TargetRoot).Path)
        $drive = [IO.DriveInfo]::new($root)
        if ($drive.TotalSize -gt 0) { $freePercent = [Math]::Round(($drive.AvailableFreeSpace * 100.0 / $drive.TotalSize), 2) }
    }
    if ($null -ne $freePercent) {
        if ($freePercent -lt $MinimumFreePercent) { $problems.Add("Backup target free space is $freePercent percent.") }
    }
} catch {
    $problems.Add('Backup target capacity could not be measured.')
}

$result = [ordered]@{
    status = if ($problems.Count -eq 0) { 'HEALTHY' } else { 'ALERT' }
    checkedUtc = [DateTime]::UtcNow.ToString('o')
    targetClass = $targetClass
    latestManifest = if ($latest) { $latest.FullName } else { $null }
    maximumAgeHours = $MaximumAgeHours
    freePercent = $freePercent
    problems = @($problems)
    gatingEvidence = ($targetClass -eq 'REMOTE_UNC')
}
$json = $result | ConvertTo-Json -Depth 5 -Compress
if ($WebhookUri) {
    Invoke-RestMethod -Method Post -Uri $WebhookUri -ContentType 'application/json' -Body $json | Out-Null
}
$json
if ($problems.Count -gt 0) { exit 2 }
