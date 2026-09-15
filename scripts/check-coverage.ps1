[CmdletBinding()]
param(
    [double]$MinimumTotalLinePercent = 70,
    [double]$MinimumDomainBranchPercent = 80
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$domainModules = @(
    'technexus-common',
    'technexus-user',
    'technexus-auth',
    'technexus-community',
    'technexus-content',
    'technexus-demand',
    'technexus-audit',
    'technexus-pricing',
    'technexus-file'
)
$allModules = $domainModules + 'technexus-server'

function Get-CounterTotals {
    param(
        [string[]]$Modules,
        [string]$CounterType
    )

    $covered = 0L
    $missed = 0L
    foreach ($module in $Modules) {
        $reportPath = Join-Path $projectRoot "$module/target/site/jacoco/jacoco.xml"
        if (-not (Test-Path -LiteralPath $reportPath)) {
            throw "Missing JaCoCo report: $reportPath. Run 'mvn clean verify' first."
        }
        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        $counter = @($report.report.counter) | Where-Object { $_.type -eq $CounterType }
        if ($counter.Count -ne 1) {
            throw "Expected one $CounterType counter in $reportPath."
        }
        $covered += [long]$counter.covered
        $missed += [long]$counter.missed
    }
    return [pscustomobject]@{ Covered = $covered; Missed = $missed }
}

function Get-Percent {
    param($Counter)
    $total = $Counter.Covered + $Counter.Missed
    if ($total -eq 0) { return 0.0 }
    return 100.0 * $Counter.Covered / $total
}

$totalLines = Get-CounterTotals -Modules $allModules -CounterType 'LINE'
$domainBranches = Get-CounterTotals -Modules $domainModules -CounterType 'BRANCH'
$totalLinePercent = Get-Percent $totalLines
$domainBranchPercent = Get-Percent $domainBranches

Write-Host ('Total Java line coverage: {0:N1}% ({1}/{2})' -f $totalLinePercent, $totalLines.Covered, ($totalLines.Covered + $totalLines.Missed))
Write-Host ('Domain branch coverage: {0:N1}% ({1}/{2})' -f $domainBranchPercent, $domainBranches.Covered, ($domainBranches.Covered + $domainBranches.Missed))

$failed = $false
if ($totalLinePercent -lt $MinimumTotalLinePercent) {
    Write-Error ('Total Java line coverage is below {0:N1}%.' -f $MinimumTotalLinePercent) -ErrorAction Continue
    $failed = $true
}
if ($domainBranchPercent -lt $MinimumDomainBranchPercent) {
    Write-Error ('Domain branch coverage is below {0:N1}%.' -f $MinimumDomainBranchPercent) -ErrorAction Continue
    $failed = $true
}
if ($failed) { exit 1 }
