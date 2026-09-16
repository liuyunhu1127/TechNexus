param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$script:Passed = 0
$script:Failed = 0
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseCookies = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token,
        [string]$IdempotencyKey,
        [hashtable]$ExtraHeaders
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        "$BaseUrl$Path"
    )
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
    }
    if ($Token) {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    }
    if ($IdempotencyKey) {
        [void]$request.Headers.TryAddWithoutValidation("Idempotency-Key", $IdempotencyKey)
    }
    if ($ExtraHeaders) {
        foreach ($header in $ExtraHeaders.GetEnumerator()) {
            [void]$request.Headers.TryAddWithoutValidation($header.Key, [string]$header.Value)
        }
    }

    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $jsonBody = $null
    if ($text) {
        try { $jsonBody = $text | ConvertFrom-Json } catch { }
    }
    $headers = @{}
    foreach ($header in $response.Headers) { $headers[$header.Key] = ($header.Value -join ",") }
    foreach ($header in $response.Content.Headers) { $headers[$header.Key] = ($header.Value -join ",") }
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Body = $jsonBody
        Text = $text
        Headers = $headers
    }
}

function Assert-Status {
    param([string]$Name, $Response, [int[]]$Expected)
    if ($Expected -contains $Response.Status) {
        $script:Passed++
        Write-Host "PASS $Name [$($Response.Status)]"
        return $true
    }
    $script:Failed++
    $errorCode = $Response.Body.code
    Write-Host "FAIL $Name expected=$($Expected -join ',') actual=$($Response.Status) code=$errorCode"
    return $false
}

function New-Key { [guid]::NewGuid().ToString() }

try {
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $password = "Tn-Phase4-Smoke!2026"
    $emailA = "phase4-a-$stamp@technexus.local"
    $emailB = "phase4-b-$stamp@technexus.local"

    Assert-Status "health" (Invoke-Api GET "/actuator/health") @(200) | Out-Null
    Assert-Status "public content list" (Invoke-Api GET "/api/v1/contents") @(200) | Out-Null
    Assert-Status "public demand list" (Invoke-Api GET "/api/v1/demands") @(200) | Out-Null
    Assert-Status "public search" (Invoke-Api GET "/api/v1/search?q=phase4") @(200) | Out-Null
    Assert-Status "anonymous profile denied" (Invoke-Api GET "/api/v1/me") @(401) | Out-Null

    $registerBodyA = @{ email = $emailA; password = $password; displayName = "Phase Four A" }
    Assert-Status "mutation requires idempotency key" (Invoke-Api POST "/api/v1/auth/register" $registerBodyA) @(400) | Out-Null
    $registerA = Invoke-Api POST "/api/v1/auth/register" $registerBodyA "" (New-Key)
    if (-not (Assert-Status "register user A" $registerA @(201))) { throw "User A registration failed" }
    Assert-Status "duplicate account rejected" (Invoke-Api POST "/api/v1/auth/register" $registerBodyA "" (New-Key)) @(409) | Out-Null
    Assert-Status "wrong password rejected" (Invoke-Api POST "/api/v1/auth/login" @{ account = $emailA; password = "Wrong-Password!2026" }) @(401) | Out-Null
    $loginA = Invoke-Api POST "/api/v1/auth/login" @{ account = $emailA; password = $password }
    if (-not (Assert-Status "login user A" $loginA @(200))) { throw "User A login failed" }
    $tokenA = $loginA.Body.accessToken
    $csrfA = [string]$loginA.Body.csrfToken
    $setCookieA = [string]$loginA.Headers["Set-Cookie"]
    $refreshA = [regex]::Match($setCookieA, 'tn_refresh=([^;,]+)').Groups[1].Value
    Assert-Status "refresh rejects missing origin and CSRF" (Invoke-Api POST "/api/v1/auth/refresh" $null "" "" @{ Cookie = "tn_refresh=$refreshA; tn_csrf=$csrfA" }) @(403) | Out-Null
    $refreshHeadersA = @{ Origin = "http://localhost:3000"; "X-CSRF-Token" = $csrfA; Cookie = "tn_refresh=$refreshA; tn_csrf=$csrfA" }
    $refreshResponseA = Invoke-Api POST "/api/v1/auth/refresh" $null "" "" $refreshHeadersA
    Assert-Status "refresh rotates session" $refreshResponseA @(200) | Out-Null
    Assert-Status "refresh token reuse is rejected" (Invoke-Api POST "/api/v1/auth/refresh" $null "" "" $refreshHeadersA) @(401) | Out-Null
    $rotatedTokenA = [string]$refreshResponseA.Body.accessToken
    Assert-Status "reused refresh revokes token family" (Invoke-Api GET "/api/v1/me" $null $rotatedTokenA) @(401) | Out-Null

    $logoutLoginA = Invoke-Api POST "/api/v1/auth/login" @{ account = $emailA; password = $password }
    if (-not (Assert-Status "login for logout verification" $logoutLoginA @(200))) { throw "Logout setup failed" }
    $logoutTokenA = [string]$logoutLoginA.Body.accessToken
    $logoutCsrfA = [string]$logoutLoginA.Body.csrfToken
    $logoutSetCookieA = [string]$logoutLoginA.Headers["Set-Cookie"]
    $logoutRefreshA = [regex]::Match($logoutSetCookieA, 'tn_refresh=([^;,]+)').Groups[1].Value
    $logoutHeadersA = @{ Origin = "http://localhost:3000"; "X-CSRF-Token" = $logoutCsrfA; Cookie = "tn_refresh=$logoutRefreshA; tn_csrf=$logoutCsrfA" }
    Assert-Status "logout revokes refresh session" (Invoke-Api POST "/api/v1/auth/logout" $null "" (New-Key) $logoutHeadersA) @(204) | Out-Null
    Assert-Status "logout invalidates access session" (Invoke-Api GET "/api/v1/me" $null $logoutTokenA) @(401) | Out-Null

    $loginA = Invoke-Api POST "/api/v1/auth/login" @{ account = $emailA; password = $password }
    if (-not (Assert-Status "login user A after session tests" $loginA @(200))) { throw "User A session rebuild failed" }
    $tokenA = [string]$loginA.Body.accessToken
    $profileA = Invoke-Api GET "/api/v1/me" $null $tokenA
    Assert-Status "read own profile" $profileA @(200) | Out-Null
    $profileVersion = [long]$profileA.Body.data.version
    Assert-Status "update own profile" (Invoke-Api PATCH "/api/v1/me" @{ displayName = "Phase Four A Updated"; bio = "phase four system test"; expectedVersion = $profileVersion } $tokenA (New-Key)) @(200) | Out-Null
    Assert-Status "stale profile version rejected" (Invoke-Api PATCH "/api/v1/me" @{ displayName = "Stale Update"; expectedVersion = $profileVersion } $tokenA (New-Key)) @(409) | Out-Null

    $uploadHash = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")).ToLowerInvariant()
    $uploadCreate = Invoke-Api POST "/api/v1/files/uploads" @{
        fileName = "phase4.txt"; sizeBytes = 12; declaredMime = "text/plain"
        sha256 = $uploadHash; purpose = "ATTACHMENT"
    } $tokenA (New-Key)
    if (Assert-Status "create direct upload session" $uploadCreate @(201)) {
        $uploadId = [string]$uploadCreate.Body.data.uploadId
        Assert-Status "object storage outage is service unavailable" (Invoke-Api POST "/api/v1/files/uploads/$uploadId/complete" $null $tokenA (New-Key)) @(503) | Out-Null
    }

    $contentBody = @{
        type = "POST"; visibility = "PUBLIC"; title = "Phase 4 unpublished content $stamp"
        summary = "system smoke"; body = "Unpublished content used for authorization verification."
        tagIds = @(); fileIds = @()
    }
    $contentKey = New-Key
    $contentCreate = Invoke-Api POST "/api/v1/contents" $contentBody $tokenA $contentKey
    if (-not (Assert-Status "create unpublished content" $contentCreate @(201))) { throw "Content creation failed" }
    $contentId = [string]$contentCreate.Body.data.id
    $contentVersion = [long]$contentCreate.Body.data.version
    $contentReplay = Invoke-Api POST "/api/v1/contents" $contentBody $tokenA $contentKey
    Assert-Status "idempotent replay" $contentReplay @(201) | Out-Null
    $sameContentId = [string]$contentReplay.Body.data.id -eq $contentId
    if ($contentReplay.Headers["X-Idempotent-Replay"] -eq "true" -and $sameContentId) {
        $script:Passed++; Write-Host "PASS idempotent replay response is stable"
    } else {
        $script:Failed++; Write-Host "FAIL idempotent replay response is stable"
    }
    $conflictingContent = $contentBody.Clone(); $conflictingContent.title = "Conflicting request $stamp"
    Assert-Status "idempotency conflict rejected" (Invoke-Api POST "/api/v1/contents" $conflictingContent $tokenA $contentKey) @(409) | Out-Null
    Assert-Status "owner reads unpublished content" (Invoke-Api GET "/api/v1/contents/$contentId" $null $tokenA) @(200) | Out-Null
    Assert-Status "anonymous cannot enumerate unpublished content" (Invoke-Api GET "/api/v1/contents/$contentId") @(403,404) | Out-Null
    $contentUpdate = Invoke-Api PATCH "/api/v1/contents/$contentId" @{ title = "Phase 4 unpublished content updated $stamp"; expectedVersion = $contentVersion } $tokenA (New-Key)
    if (Assert-Status "owner updates unpublished content" $contentUpdate @(200)) {
        $updatedContentVersion = [long]$contentUpdate.Body.data.version
        Assert-Status "stale content version rejected" (Invoke-Api PATCH "/api/v1/contents/$contentId" @{ title = "Stale content"; expectedVersion = $contentVersion } $tokenA (New-Key)) @(409) | Out-Null
    }
    $contentSubmit = Invoke-Api POST "/api/v1/contents/$contentId/submit" @{ expectedVersion = $updatedContentVersion } $tokenA (New-Key)
    if (Assert-Status "submit content for audit" $contentSubmit @(202)) {
        $updatedContentVersion = [long]$contentSubmit.Body.data.version
    }

    $registerBodyB = @{ email = $emailB; password = $password; displayName = "Phase Four B" }
    Assert-Status "register user B" (Invoke-Api POST "/api/v1/auth/register" $registerBodyB "" (New-Key)) @(201) | Out-Null
    $loginB = Invoke-Api POST "/api/v1/auth/login" @{ account = $emailB; password = $password }
    if (-not (Assert-Status "login user B" $loginB @(200))) { throw "User B login failed" }
    $tokenB = $loginB.Body.accessToken
    Assert-Status "other user cannot read unpublished content" (Invoke-Api GET "/api/v1/contents/$contentId" $null $tokenB) @(403,404) | Out-Null
    Assert-Status "other user cannot update unpublished content" (Invoke-Api PATCH "/api/v1/contents/$contentId" @{ title = "Unauthorized update"; expectedVersion = $updatedContentVersion } $tokenB (New-Key)) @(403,404) | Out-Null

    $demandBody = @{
        title = "Phase 4 demand $stamp"; description = "Public demand used for proposal and ownership verification."
        budgetMin = @{ amount = "1000.00"; currency = "CNY" }
        budgetMax = @{ amount = "5000.00"; currency = "CNY" }
        deadlineAt = [DateTime]::UtcNow.AddDays(30).ToString("o"); visibility = "PUBLIC"
    }
    $demandCreate = Invoke-Api POST "/api/v1/demands" $demandBody $tokenA (New-Key)
    if (-not (Assert-Status "create public demand" $demandCreate @(201))) { throw "Demand creation failed" }
    $demandId = [string]$demandCreate.Body.data.id
    $demandVersion = [long]$demandCreate.Body.data.version
    Assert-Status "owner reads draft demand" (Invoke-Api GET "/api/v1/demands/$demandId" $null $tokenA) @(200) | Out-Null
    Assert-Status "anonymous cannot enumerate draft demand" (Invoke-Api GET "/api/v1/demands/$demandId") @(404) | Out-Null
    Assert-Status "other user cannot update demand" (Invoke-Api PATCH "/api/v1/demands/$demandId" @{ title = "Unauthorized demand update"; expectedVersion = $demandVersion } $tokenB (New-Key)) @(403,404) | Out-Null
    Assert-Status "submit demand for audit" (Invoke-Api POST "/api/v1/demands/$demandId/submit" @{ expectedVersion = $demandVersion } $tokenA (New-Key)) @(202) | Out-Null

    Assert-Status "regular user cannot access admin dashboard" (Invoke-Api GET "/api/v1/admin/dashboard" $null $tokenA) @(403) | Out-Null
    $adminEmail = "phase4-admin@technexus.local"
    $adminPassword = "Tn-Phase4-Admin!2026"
    $adminLogin = Invoke-Api POST "/api/v1/auth/login" @{ account = $adminEmail; password = $adminPassword }
    if ($adminLogin.Status -eq 401) {
        $adminRegister = Invoke-Api POST "/api/v1/auth/register" @{ email = $adminEmail; password = $adminPassword; displayName = "Phase Four Admin" } "" (New-Key)
        if (-not (Assert-Status "register bootstrap admin" $adminRegister @(201))) { throw "Bootstrap admin registration failed" }
        $adminLogin = Invoke-Api POST "/api/v1/auth/login" @{ account = $adminEmail; password = $adminPassword }
    }
    if (-not (Assert-Status "login bootstrap admin" $adminLogin @(200))) { throw "Bootstrap admin login failed" }
    $adminToken = [string]$adminLogin.Body.accessToken
    Assert-Status "admin dashboard" (Invoke-Api GET "/api/v1/admin/dashboard" $null $adminToken) @(200) | Out-Null
    $auditTasks = Invoke-Api GET "/api/v1/admin/audit-tasks" $null $adminToken
    if (Assert-Status "admin lists audit tasks" $auditTasks @(200)) {
        $contentTask = $auditTasks.Body.items | Where-Object { [string]$_.targetId -eq $contentId } | Select-Object -First 1
        $demandTask = $auditTasks.Body.items | Where-Object { [string]$_.targetId -eq $demandId } | Select-Object -First 1
        if ($null -eq $contentTask -or $null -eq $demandTask) {
            $script:Failed++; Write-Host "FAIL submitted targets produce audit tasks"
        } else {
            $script:Passed++; Write-Host "PASS submitted targets produce audit tasks"
            Assert-Status "admin approves content" (Invoke-Api POST "/api/v1/admin/audit-tasks/$($contentTask.id)/decisions" @{ decision = "APPROVED"; reasonCode = "OTHER"; note = "Phase four approval"; expectedTaskVersion = [long]$contentTask.version } $adminToken (New-Key)) @(200) | Out-Null
            Assert-Status "admin approves demand" (Invoke-Api POST "/api/v1/admin/audit-tasks/$($demandTask.id)/decisions" @{ decision = "APPROVED"; reasonCode = "OTHER"; note = "Phase four approval"; expectedTaskVersion = [long]$demandTask.version } $adminToken (New-Key)) @(200) | Out-Null
            Assert-Status "approved content becomes public" (Invoke-Api GET "/api/v1/contents/$contentId") @(200) | Out-Null
            Assert-Status "approved demand awaits pricing" (Invoke-Api GET "/api/v1/demands/$demandId") @(404) | Out-Null
            $priceBody = @{ mode = "FREE"; amount = @{ amount = "0.00"; currency = "CNY" }; validFrom = [DateTime]::UtcNow.ToString("o"); reason = "Phase four free publication"; expectedVersion = 0 }
            Assert-Status "admin confirms free price" (Invoke-Api POST "/api/v1/admin/prices/DEMAND/$demandId/confirmations" $priceBody $adminToken (New-Key)) @(200) | Out-Null
            Assert-Status "priced demand becomes public" (Invoke-Api GET "/api/v1/demands/$demandId") @(200) | Out-Null
        }
    }

    Write-Host "RESULT passed=$script:Passed failed=$script:Failed"
    if ($script:Failed -gt 0) { exit 1 }
}
finally {
    $client.Dispose()
}
