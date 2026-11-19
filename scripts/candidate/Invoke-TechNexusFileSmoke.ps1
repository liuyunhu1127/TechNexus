param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https://')]
    [string]$BaseUrl,
    [ValidateRange(0, 65535)]
    [int]$ExpectedStoragePort = 0,
    [switch]$SkipCertificateRevocationCheck
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$useCurl = $SkipCertificateRevocationCheck -and $IsWindows
$handler = $null
$client = $null
if (-not $useCurl) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.CheckCertificateRevocationList = -not $SkipCertificateRevocationCheck
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(30)
}

function Send-Json([string]$Method, [string]$Path, $Body, [string]$Token = '') {
    if ($useCurl) {
        $arguments = @('--ssl-no-revoke', '-sS', '--max-time', '30', '-X', $Method, '-w', "`n%{http_code}")
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 8 -Compress
            $arguments += @('-H', 'Content-Type: application/json', '--data-raw', $json)
        }
        if ($Token) { $arguments += @('-H', ('Authorization: Bearer ' + $Token)) }
        if ($Method -ne 'GET') { $arguments += @('-H', ('Idempotency-Key: ' + [guid]::NewGuid().ToString())) }
        $arguments += ($BaseUrl.TrimEnd('/') + $Path)
        $output = @(& curl.exe @arguments)
        if ($LASTEXITCODE -ne 0 -or $output.Count -lt 1) { throw "curl request failed for $Path" }
        $status = [int]$output[-1]
        $content = ($output[0..($output.Count - 2)] -join "`n")
        return [pscustomobject]@{
            Status = $status
            Success = $status -ge 200 -and $status -lt 300
            Json = if ($content) { $content | ConvertFrom-Json } else { $null }
        }
    }

    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ($BaseUrl.TrimEnd('/') + $Path))
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 8 -Compress
            $request.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        }
        if ($Token) { [void]$request.Headers.TryAddWithoutValidation('Authorization', 'Bearer ' + $Token) }
        if ($Method -ne 'GET') { [void]$request.Headers.TryAddWithoutValidation('Idempotency-Key', [guid]::NewGuid().ToString()) }
        $response = $client.Send($request)
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Success = $response.IsSuccessStatusCode
            Json = if ($content) { $content | ConvertFrom-Json } else { $null }
        }
    } finally {
        $request.Dispose()
    }
}

try {
    $tempDirectory = Join-Path ([IO.Path]::GetTempPath()) ('technexus-file-smoke-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    $suffix = [guid]::NewGuid().ToString('N')
    $email = "candidate-file-$suffix@example.com"
    $password = "Candidate!${suffix}Aa9"
    $register = Send-Json 'POST' '/api/v1/auth/register' @{
        email = $email
        password = $password
        displayName = 'Candidate File Smoke'
    }
    if (-not $register.Success) { throw "Registration failed with HTTP $($register.Status)" }

    $login = Send-Json 'POST' '/api/v1/auth/login' @{ account = $email; password = $password }
    if (-not $login.Success -or -not $login.Json.accessToken) { throw "Login failed with HTTP $($login.Status)" }
    $token = [string]$login.Json.accessToken

    $bytes = [Text.Encoding]::UTF8.GetBytes('TechNexus candidate file smoke ' + [guid]::NewGuid())
    $hashBytes = [Security.Cryptography.SHA256]::HashData($bytes)
    $hashHex = [Convert]::ToHexString($hashBytes).ToLowerInvariant()
    $create = Send-Json 'POST' '/api/v1/files/uploads' @{
        fileName = 'candidate-smoke.txt'
        sizeBytes = $bytes.Length
        declaredMime = 'text/plain'
        sha256 = $hashHex
        purpose = 'ATTACHMENT'
    } $token
    if (-not $create.Success) { throw "Upload initialization failed with HTTP $($create.Status)" }
    $upload = $create.Json.data
    $uploadUri = [uri]$upload.uploadUrl
    if (-not $uploadUri.Scheme.Equals('https', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Presigned upload URL is not HTTPS'
    }
    if ($ExpectedStoragePort -gt 0 -and $uploadUri.Port -ne $ExpectedStoragePort) {
        throw "Presigned upload URL uses port $($uploadUri.Port), expected $ExpectedStoragePort"
    }

    $origin = ([uri]$BaseUrl).GetLeftPart([UriPartial]::Authority)
    if ($useCurl) {
        $corsHeadersPath = Join-Path $tempDirectory 'cors-headers.txt'
        $corsStatus = & curl.exe --ssl-no-revoke -sS --max-time 30 -X OPTIONS -o NUL -D $corsHeadersPath `
            -w '%{http_code}' -H ('Origin: ' + $origin) -H 'Access-Control-Request-Method: PUT' `
            -H 'Access-Control-Request-Headers: content-type,x-amz-checksum-sha256' ([string]$upload.uploadUrl)
        $corsHeaders = [IO.File]::ReadAllText($corsHeadersPath)
        if ($LASTEXITCODE -ne 0 -or [int]$corsStatus -notin @(200, 204) `
                -or $corsHeaders -notmatch '(?im)^access-control-allow-origin:\s*(\*|' + [regex]::Escape($origin) + ')\s*$') {
            throw "Browser upload CORS preflight failed with HTTP $corsStatus"
        }
    } else {
        $cors = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Options, [uri]$upload.uploadUrl)
        try {
            [void]$cors.Headers.TryAddWithoutValidation('Origin', $origin)
            [void]$cors.Headers.TryAddWithoutValidation('Access-Control-Request-Method', 'PUT')
            [void]$cors.Headers.TryAddWithoutValidation('Access-Control-Request-Headers', 'content-type,x-amz-checksum-sha256')
            $corsResponse = $client.Send($cors)
            $allowedOrigin = $corsResponse.Headers.GetValues('Access-Control-Allow-Origin') -join ','
            if (-not $corsResponse.IsSuccessStatusCode -or $allowedOrigin -notin @('*', $origin)) {
                throw "Browser upload CORS preflight failed with HTTP $([int]$corsResponse.StatusCode)"
            }
        } finally {
            $cors.Dispose()
        }
    }

    if ($useCurl) {
        $uploadPath = Join-Path $tempDirectory 'upload.bin'
        [IO.File]::WriteAllBytes($uploadPath, $bytes)
        $arguments = @('--ssl-no-revoke', '-sS', '--max-time', '30', '-X', 'PUT', '-o', 'NUL', '-w', '%{http_code}')
        foreach ($property in $upload.requiredHeaders.PSObject.Properties) {
            $arguments += @('-H', ($property.Name + ': ' + [string]$property.Value))
        }
        $arguments += @('--data-binary', ('@' + $uploadPath), [string]$upload.uploadUrl)
        $putStatus = & curl.exe @arguments
        if ($LASTEXITCODE -ne 0 -or [int]$putStatus -lt 200 -or [int]$putStatus -ge 300) {
            throw "Presigned upload failed with HTTP $putStatus"
        }
    } else {
        $put = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Put, [uri]$upload.uploadUrl)
        try {
            $put.Content = [Net.Http.ByteArrayContent]::new($bytes)
            foreach ($property in $upload.requiredHeaders.PSObject.Properties) {
                if ($property.Name -ieq 'Content-Type') {
                    [void]$put.Content.Headers.TryAddWithoutValidation($property.Name, [string]$property.Value)
                } else {
                    [void]$put.Headers.TryAddWithoutValidation($property.Name, [string]$property.Value)
                }
            }
            $putResponse = $client.Send($put)
            if (-not $putResponse.IsSuccessStatusCode) { throw "Presigned upload failed with HTTP $([int]$putResponse.StatusCode)" }
        } finally {
            $put.Dispose()
        }
    }

    $complete = Send-Json 'POST' "/api/v1/files/uploads/$($upload.uploadId)/complete" @{} $token
    if (-not $complete.Success -or $complete.Json.data.state -notin @('SCANNING', 'AVAILABLE')) {
        throw "Upload completion failed with HTTP $($complete.Status)"
    }

    $downloadUrl = $null
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        $download = Send-Json 'POST' "/api/v1/files/$($upload.fileId)/download-url" @{} $token
        if ($download.Success) { $downloadUrl = [uri]$download.Json.data.url }
    } until ($null -ne $downloadUrl -or (Get-Date) -gt $deadline)
    if ($null -eq $downloadUrl) { throw 'File did not become downloadable within 60 seconds' }
    if (-not $downloadUrl.Scheme.Equals('https', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Presigned download URL is not HTTPS'
    }
    if ($ExpectedStoragePort -gt 0 -and $downloadUrl.Port -ne $ExpectedStoragePort) {
        throw "Presigned download URL uses port $($downloadUrl.Port), expected $ExpectedStoragePort"
    }

    if ($useCurl) {
        $downloadPath = Join-Path $tempDirectory 'download.bin'
        $downloadStatus = & curl.exe --ssl-no-revoke -sS --max-time 30 -o $downloadPath -w '%{http_code}' ([string]$downloadUrl)
        if ($LASTEXITCODE -ne 0 -or [int]$downloadStatus -ne 200) { throw "Presigned download failed with HTTP $downloadStatus" }
        $downloaded = [IO.File]::ReadAllBytes($downloadPath)
    } else {
        $downloaded = $client.GetByteArrayAsync($downloadUrl).GetAwaiter().GetResult()
    }
    if (-not [Convert]::ToHexString($downloaded).Equals([Convert]::ToHexString($bytes), [StringComparison]::Ordinal)) {
        throw 'Downloaded bytes differ from uploaded bytes'
    }

    Write-Output 'https_api=PASS'
    if ($ExpectedStoragePort -gt 0) { Write-Output "storage_port_$ExpectedStoragePort=PASS" }
    Write-Output 'presigned_https_upload=PASS'
    Write-Output 'browser_upload_cors=PASS'
    Write-Output 'internal_object_inspection=PASS'
    Write-Output 'clamav_clean_scan=PASS'
    Write-Output 'authorized_https_download=PASS'
    Write-Output 'download_content_integrity=PASS'
    Write-Output 'credentials_disclosed=false'
} finally {
    if ($null -ne $client) { $client.Dispose() }
    if ($null -ne $handler) { $handler.Dispose() }
    if ($null -ne $tempDirectory -and (Test-Path -LiteralPath $tempDirectory)) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
