param(
    [string]$PublicUrl = 'http://127.0.0.1:3000',
    [string]$ApiUrl = 'http://127.0.0.1:8080',
    [ValidateSet('LOCAL_REHEARSAL', 'CANDIDATE_GATE')]
    [string]$Classification = 'LOCAL_REHEARSAL',
    [int[]]$AllowedPublicPorts = @(80, 443),
    [int[]]$ProbePorts = @(21, 22, 23, 25, 80, 443, 3306, 5432, 6379, 8080, 9000, 9090),
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$publicUri = [Uri]$PublicUrl
$apiUri = [Uri]$ApiUrl
$loopback = @('localhost', '127.0.0.1', '::1')
if ($Classification -eq 'CANDIDATE_GATE') {
    if ($loopback -contains $publicUri.Host -or $loopback -contains $apiUri.Host) {
        throw 'CANDIDATE_GATE refuses loopback URLs.'
    }
    if ($publicUri.Scheme -ne 'https' -or $apiUri.Scheme -ne 'https') {
        throw 'CANDIDATE_GATE requires HTTPS public and API URLs.'
    }
}

function Get-HeaderValue {
    param($Response, [string]$Name)
    $values = $Response.Headers.GetValues($Name)
    if ($values) { return ($values -join ', ') }
    return $null
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 800)
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return $task.Wait($TimeoutMs) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$findings = [Collections.Generic.List[object]]::new()
$public = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 $PublicUrl
$liveness = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 "$($apiUri.GetLeftPart([UriPartial]::Authority))/actuator/health/liveness"

$requiredHeaders = [ordered]@{
    'X-Content-Type-Options' = '^nosniff$'
    'X-Frame-Options' = '^(DENY|SAMEORIGIN)$'
    'Referrer-Policy' = '.+'
    'Permissions-Policy' = '.+'
}
foreach ($target in @([ordered]@{ name = 'public'; response = $public }, [ordered]@{ name = 'api'; response = $liveness })) {
    foreach ($entry in $requiredHeaders.GetEnumerator()) {
        $value = Get-HeaderValue $target.response $entry.Key
        if (-not $value -or $value -notmatch $entry.Value) {
            $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'SECURITY_HEADER_MISSING'; target = $target.name; detail = $entry.Key })
        }
    }
}
$apiCsp = Get-HeaderValue $liveness 'Content-Security-Policy'
if (-not $apiCsp -or $apiCsp -notmatch "default-src 'none'") {
    $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'API_CSP_WEAK'; target = 'api'; detail = [string]$apiCsp })
}

$tls = $null
$httpRedirect = $null
$openPorts = @()
if ($Classification -eq 'CANDIDATE_GATE') {
    $hsts = Get-HeaderValue $public 'Strict-Transport-Security'
    if (-not $hsts -or $hsts -notmatch 'max-age=(\d+)' -or [int64]$matches[1] -lt 31536000) {
        $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'HSTS_MISSING_OR_SHORT'; target = 'public'; detail = [string]$hsts })
    }

    $tcp = [Net.Sockets.TcpClient]::new($publicUri.Host, $publicUri.Port)
    try {
        $ssl = [Net.Security.SslStream]::new($tcp.GetStream(), $false)
        try {
            $ssl.AuthenticateAsClient($publicUri.Host)
            $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($ssl.RemoteCertificate)
            $tls = [ordered]@{
                protocol = [string]$ssl.SslProtocol
                subject = $certificate.Subject
                issuer = $certificate.Issuer
                notAfterUtc = $certificate.NotAfter.ToUniversalTime().ToString('o')
                daysRemaining = [math]::Floor(($certificate.NotAfter.ToUniversalTime() - [DateTime]::UtcNow).TotalDays)
            }
            if ($tls.protocol -notmatch 'Tls12|Tls13') {
                $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'TLS_VERSION_WEAK'; target = 'public'; detail = $tls.protocol })
            }
            if ($tls.daysRemaining -lt 30) {
                $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'CERTIFICATE_EXPIRING'; target = 'public'; detail = "days=$($tls.daysRemaining)" })
            }
        } finally { $ssl.Dispose() }
    } finally { $tcp.Dispose() }

    $redirectUri = [UriBuilder]::new($publicUri)
    $redirectUri.Scheme = 'http'
    $redirectUri.Port = 80
    $redirect = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -MaximumRedirection 0 -SkipHttpErrorCheck $redirectUri.Uri
    $location = Get-HeaderValue $redirect 'Location'
    $httpRedirect = [ordered]@{ status = [int]$redirect.StatusCode; location = $location }
    if ($redirect.StatusCode -notin @(301, 308) -or -not $location -or -not $location.StartsWith('https://')) {
        $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'HTTPS_REDIRECT_MISSING'; target = 'public'; detail = "status=$($redirect.StatusCode) location=$location" })
    }

    $openPorts = @($ProbePorts | Where-Object { Test-TcpPort -HostName $publicUri.Host -Port $_ })
    $unexpected = @($openPorts | Where-Object { $_ -notin $AllowedPublicPorts })
    foreach ($port in $unexpected) {
        $findings.Add([ordered]@{ severity = 'CRITICAL'; code = 'UNEXPECTED_PUBLIC_PORT'; target = $publicUri.Host; detail = "port=$port" })
    }
}

$critical = @($findings | Where-Object severity -eq 'CRITICAL').Count
$result = [ordered]@{
    schemaVersion = 1
    classification = $Classification
    gatingEvidence = $Classification -eq 'CANDIDATE_GATE'
    checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    publicUrl = $PublicUrl
    apiUrl = $ApiUrl
    publicStatus = [int]$public.StatusCode
    livenessStatus = [int]$liveness.StatusCode
    tls = $tls
    httpRedirect = $httpRedirect
    probedPorts = if ($Classification -eq 'CANDIDATE_GATE') { $ProbePorts } else { @() }
    openPorts = $openPorts
    findings = $findings
    result = if ($critical) { 'FAIL' } elseif ($Classification -eq 'CANDIDATE_GATE') { 'CANDIDATE_REVIEW_REQUIRED' } else { 'PASS_LOCAL' }
}
if ($OutputPath) {
    $directory = Split-Path -Parent $OutputPath
    if ($directory) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    [IO.File]::WriteAllText($OutputPath, (($result | ConvertTo-Json -Depth 8) + "`n"), [Text.UTF8Encoding]::new($false))
}
$result
if ($critical) { exit 1 }
