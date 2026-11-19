param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$MySqlCredentials = 'D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt',
    [string]$MySqlExe = 'D:\DevelopTools\mysql-8.4.13-winx64\bin\mysql.exe',
    [string]$Database = 'technexus_it',
    [string]$FilerUrl = 'http://127.0.0.1:8888/buckets/technexus-files'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-CredentialField([string]$Name) {
    $match = [regex]::Match($script:credentialText, '(?m)^' + [regex]::Escape($Name) + '=(.*)$')
    if (-not $match.Success) { throw "Missing credential field: $Name" }
    return $match.Groups[1].Value.Trim()
}

function Invoke-PostJson([string]$Path, $Body, [string]$Token = '', [string]$IdempotencyKey = '') {
    if (-not $IdempotencyKey) { $IdempotencyKey = 'gate-' + [guid]::NewGuid() }
    $headers = @{ 'Idempotency-Key' = $IdempotencyKey }
    if ($Token) { $headers.Authorization = 'Bearer ' + $Token }
    return Invoke-RestMethod -Method Post -Uri ($BaseUrl + $Path) -Headers $headers -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 30
}

function New-GateUser([string]$Prefix) {
    $suffix = [guid]::NewGuid().ToString('N')
    $email = "$Prefix-$suffix@example.com"
    $password = "Gate!${suffix}Aa9"
    $registration = Invoke-PostJson '/api/v1/auth/register' `
        @{ email = $email; password = $password; displayName = $Prefix }
    $login = Invoke-RestMethod -Method Post -Uri ($BaseUrl + '/api/v1/auth/login') -ContentType 'application/json' `
        -Body (@{ account = $email; password = $password } | ConvertTo-Json -Compress) -TimeoutSec 30
    if (-not $login.accessToken) { throw 'Login did not return an access token' }
    return [pscustomobject]@{ Id = [string]$registration.data.id; Token = [string]$login.accessToken }
}

function Get-Sha256([byte[]]$Bytes) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

function New-Upload($User, [string]$Name, [byte[]]$Bytes, [string]$Mime, [string]$Hash = '', [long]$Size = -1) {
    if (-not $Hash) { $Hash = Get-Sha256 $Bytes }
    if ($Size -lt 0) { $Size = $Bytes.Length }
    return (Invoke-PostJson '/api/v1/files/uploads' `
        @{ fileName = $Name; sizeBytes = $Size; declaredMime = $Mime; sha256 = $Hash; purpose = 'ATTACHMENT' } `
        $User.Token).data
}

function Send-Upload($Upload, [byte[]]$Bytes) {
    $client = [Net.Http.HttpClient]::new()
    try {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Put, [uri]$Upload.uploadUrl)
        $request.Content = [Net.Http.ByteArrayContent]::new($Bytes)
        foreach ($property in $Upload.requiredHeaders.PSObject.Properties) {
            if ($property.Name -ieq 'Content-Type') {
                [void]$request.Content.Headers.TryAddWithoutValidation($property.Name, [string]$property.Value)
            } else {
                [void]$request.Headers.TryAddWithoutValidation($property.Name, [string]$property.Value)
            }
        }
        $response = $client.Send($request)
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Success = $response.IsSuccessStatusCode; Body = $body }
    } finally {
        $client.Dispose()
    }
}

function Complete-Upload($User, $Upload, [string]$Key = '') {
    return (Invoke-PostJson ("/api/v1/files/uploads/$($Upload.uploadId)/complete") @{} $User.Token $Key).data
}

function Invoke-Db([string]$Sql) {
    $previous = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = Read-CredentialField 'TECHNEXUS_DB_PASSWORD'
        $user = Read-CredentialField 'TECHNEXUS_DB_USER'
        $result = & $MySqlExe --batch --skip-column-names --host=127.0.0.1 --port=3306 --user=$user $Database `
            --execute=$Sql
        if ($LASTEXITCODE -ne 0) { throw 'MySQL query failed' }
        return $result
    } finally {
        $env:MYSQL_PWD = $previous
    }
}

function Get-FileRow([string]$FileId) {
    $row = Invoke-Db "SELECT state,object_key,COALESCE(scan_error,'') FROM tn_file_object WHERE public_id=UNHEX(REPLACE('$FileId','-',''))"
    $parts = $row -split "`t", 3
    return [pscustomobject]@{ State = $parts[0]; ObjectKey = $parts[1]; ScanError = $parts[2] }
}

function Wait-FileState([string]$FileId, [string[]]$States, [int]$Seconds = 45) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 250
        $row = Get-FileRow $FileId
    } until ($States -contains $row.State -or (Get-Date) -gt $deadline)
    if ($States -notcontains $row.State) { throw "File $FileId did not reach $($States -join '/'); state=$($row.State)" }
    return $row
}

function Get-FilerStatus([string]$ObjectKey) {
    $response = Invoke-WebRequest -Method Head -Uri ($FilerUrl.TrimEnd('/') + '/' + $ObjectKey) -SkipHttpErrorCheck `
        -TimeoutSec 10
    return [int]$response.StatusCode
}

function Assert-ApiStatus([scriptblock]$Action, [int]$Expected) {
    try {
        & $Action | Out-Null
        throw "Expected HTTP $Expected but request succeeded"
    } catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected) { throw }
    }
}

if (-not (Test-Path -LiteralPath $MySqlCredentials)) { throw 'MySQL credentials file is missing' }
if (-not (Test-Path -LiteralPath $MySqlExe)) { throw 'mysql.exe is missing' }
$script:credentialText = [IO.File]::ReadAllText($MySqlCredentials)

$owner = New-GateUser 'FileGateOwner'
$cleanBytes = [Text.Encoding]::UTF8.GetBytes('TechNexus clean E2E ' + [guid]::NewGuid())
$cleanHash = Get-Sha256 $cleanBytes
$clean = New-Upload $owner 'clean.txt' $cleanBytes 'text/plain'
$put = Send-Upload $clean $cleanBytes
if (-not $put.Success) { throw "Clean PUT failed: $($put.Status) $($put.Body)" }
$completeKey = 'gate-complete-' + [guid]::NewGuid()
$completed = Complete-Upload $owner $clean $completeKey
if ($completed.state -ne 'SCANNING') { throw "Unexpected clean complete state: $($completed.state)" }
$replayed = Complete-Upload $owner $clean $completeKey
if ($replayed.id -ne $completed.id) { throw 'Idempotent complete replay returned a different file' }
$cleanRow = Wait-FileState $clean.fileId @('AVAILABLE')
$download = (Invoke-PostJson "/api/v1/files/$($clean.fileId)/download-url" @{} $owner.Token).data
$downloadClient = [Net.Http.HttpClient]::new()
try { $downloaded = $downloadClient.GetByteArrayAsync([uri]$download.url).GetAwaiter().GetResult() } finally { $downloadClient.Dispose() }
if ([Convert]::ToHexString($downloaded) -ne [Convert]::ToHexString($cleanBytes)) { throw 'Clean download differs from upload' }
Assert-ApiStatus { Complete-Upload $owner $clean } 404

$second = New-GateUser 'FileGateSecond'
$proof = New-Upload $second 'same.txt' $cleanBytes 'text/plain'
if ($proof.fileId -ne $clean.fileId) { throw 'Same hash did not reuse the physical file identity' }
$put = Send-Upload $proof $cleanBytes
if (-not $put.Success) { throw "Proof PUT failed: $($put.Status) $($put.Body)" }
$proofComplete = Complete-Upload $second $proof
if ($proofComplete.state -ne 'AVAILABLE') { throw "Unexpected proof complete state: $($proofComplete.state)" }
[void](Invoke-PostJson "/api/v1/files/$($clean.fileId)/download-url" @{} $second.Token)
$third = New-GateUser 'FileGateThird'
Assert-ApiStatus { Invoke-PostJson "/api/v1/files/$($clean.fileId)/download-url" @{} $third.Token } 404
$physical = Invoke-Db "SELECT COUNT(*) FROM tn_file_object WHERE sha256=UNHEX('$cleanHash') AND size_bytes=$($cleanBytes.Length)"
$relations = Invoke-Db "SELECT COUNT(*) FROM tn_file_relation r JOIN tn_file_object f ON f.id=r.file_object_id WHERE f.public_id=UNHEX(REPLACE('$($clean.fileId)','-',''))"
if ([int]$physical -ne 1 -or [int]$relations -ne 2) { throw "Dedup evidence invalid: physical=$physical relations=$relations" }

$malwareUser = New-GateUser 'FileGateMalware'
$malwareBytes = [Text.Encoding]::ASCII.GetBytes('TECHNEXUS_CLAMAV_E2E_TEST_MARKER_2026-' + [guid]::NewGuid())
$malware = New-Upload $malwareUser 'malware-test.txt' $malwareBytes 'text/plain'
$put = Send-Upload $malware $malwareBytes
if (-not $put.Success) { throw "Malware test PUT failed: $($put.Status) $($put.Body)" }
[void](Complete-Upload $malwareUser $malware)
$malwareRow = Wait-FileState $malware.fileId @('BLOCKED')
$expectedQuarantine = 'quarantine/' + $malware.fileId
if ($malwareRow.ObjectKey -ne $expectedQuarantine -or -not $malwareRow.ScanError.StartsWith('MALWARE:')) {
    throw "Test signature was not blocked as malware: $($malwareRow.ScanError)"
}
$malwareOriginal = "uploads/$($malwareUser.Id)/$($malware.fileId)/malware-test.txt"
if ((Get-FilerStatus $malwareOriginal) -ne 404 -or (Get-FilerStatus $expectedQuarantine) -ne 200) {
    throw 'Malware test object was not moved to quarantine'
}
Assert-ApiStatus { Invoke-PostJson "/api/v1/files/$($malware.fileId)/download-url" @{} $malwareUser.Token } 404

$magicBytes = [Text.Encoding]::UTF8.GetBytes('This is text, not a PNG file. ' + [guid]::NewGuid())
$magic = New-Upload $owner 'fake.png' $magicBytes 'image/png'
$put = Send-Upload $magic $magicBytes
if (-not $put.Success) { throw "Magic mismatch PUT failed: $($put.Status) $($put.Body)" }
[void](Complete-Upload $owner $magic)
$magicRow = Wait-FileState $magic.fileId @('BLOCKED')
if (-not $magicRow.ScanError.StartsWith('FILE_MAGIC_MISMATCH:')) { throw "Magic mismatch reason invalid: $($magicRow.ScanError)" }

$hashRun = [guid]::NewGuid().ToString('N')
$expectedBytes = [Text.Encoding]::UTF8.GetBytes('hash-content-A-' + $hashRun)
$forgedBytes = [Text.Encoding]::UTF8.GetBytes('hash-content-B-' + $hashRun)
$forged = New-Upload $owner 'forged.txt' $expectedBytes 'text/plain'
$put = Send-Upload $forged $forgedBytes
if ($put.Success) {
    [void](Complete-Upload $owner $forged)
    $forgedRow = Wait-FileState $forged.fileId @('BLOCKED')
    if (-not $forgedRow.ScanError.StartsWith('FILE_HASH_MISMATCH:')) { throw "Hash mismatch reason invalid: $($forgedRow.ScanError)" }
}

$sizeBytes = [Text.Encoding]::UTF8.GetBytes('size-check-' + [guid]::NewGuid())
$size = New-Upload $owner 'size.txt' $sizeBytes 'text/plain' '' ($sizeBytes.Length + 1)
$put = Send-Upload $size $sizeBytes
if (-not $put.Success) { throw "Size mismatch PUT failed unexpectedly: $($put.Status)" }
Assert-ApiStatus { Complete-Upload $owner $size } 400
$relationCount = Invoke-Db "SELECT COUNT(*) FROM tn_file_relation r JOIN tn_file_object f ON f.id=r.file_object_id WHERE f.public_id=UNHEX(REPLACE('$($size.fileId)','-',''))"
if ([int]$relationCount -ne 0) { throw 'Size mismatch created a file relation' }
[void](Invoke-Db "UPDATE tn_file_upload_session SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE public_id=UNHEX(REPLACE('$($size.uploadId)','-',''))")
$deadline = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Milliseconds 250
    $cleanupState = Invoke-Db "SELECT state FROM tn_file_upload_session WHERE public_id=UNHEX(REPLACE('$($size.uploadId)','-',''))"
} until ($cleanupState -eq 'EXPIRED' -or (Get-Date) -gt $deadline)
if ($cleanupState -ne 'EXPIRED') { throw "Expired upload cleanup did not run; state=$cleanupState" }
$sizeOriginal = "uploads/$($owner.Id)/$($size.fileId)/size.txt"
if ((Get-FilerStatus $sizeOriginal) -ne 404) { throw 'Expired upload object was not deleted' }

Assert-ApiStatus { New-Upload $owner 'wrong.png' $magicBytes 'text/plain' } 400

Write-Output 'clean_upload_scan_download=PASS'
Write-Output 'complete_idempotency=PASS'
Write-Output 'same_hash_two_users_single_object=PASS'
Write-Output 'third_user_idor_404=PASS'
Write-Output 'clamav_detection_quarantine=PASS'
Write-Output 'magic_number_mismatch=PASS'
Write-Output 'actual_sha256_recompute=PASS'
Write-Output 'size_mismatch_no_relation=PASS'
Write-Output 'expired_temporary_cleanup=PASS'
Write-Output 'extension_mime_mismatch=PASS'
Write-Output 'malware_test_sample_persisted_to_disk=false'
Write-Output 'credentials_disclosed=false'
