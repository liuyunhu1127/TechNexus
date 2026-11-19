Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-TechNexusBackupKey {
    param([Parameter(Mandatory = $true)][string]$Base64Key)

    try {
        $key = [Convert]::FromBase64String($Base64Key)
    } catch {
        throw 'TECHNEXUS_BACKUP_KEY_BASE64 must be valid Base64.'
    }
    if ($key.Length -ne 32) {
        throw 'TECHNEXUS_BACKUP_KEY_BASE64 must decode to exactly 32 bytes.'
    }
    return $key
}

function Get-DerivedKey {
    param(
        [Parameter(Mandatory = $true)][byte[]]$MasterKey,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $hmac = [Security.Cryptography.HMACSHA256]::new($MasterKey)
    try {
        return $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("TechNexus backup $Purpose v1"))
    } finally {
        $hmac.Dispose()
    }
}

function Test-ConstantTimeByteArray {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Left,
        [Parameter(Mandatory = $true)][byte[]]$Right
    )
    if ($Left.Length -ne $Right.Length) { return $false }
    $difference = 0
    for ($index = 0; $index -lt $Left.Length; $index++) {
        $difference = $difference -bor ($Left[$index] -bxor $Right[$index])
    }
    return ($difference -eq 0)
}

function Get-HmacForPrefix {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][long]$Length,
        [Parameter(Mandatory = $true)][byte[]]$Key
    )

    $stream = [IO.File]::OpenRead($Path)
    $hmac = [Security.Cryptography.HMACSHA256]::new($Key)
    $buffer = [byte[]]::new(1MB)
    try {
        $remaining = $Length
        while ($remaining -gt 0) {
            $count = [int][Math]::Min($buffer.Length, $remaining)
            $read = $stream.Read($buffer, 0, $count)
            if ($read -le 0) { throw 'Encrypted backup ended before the authenticated length.' }
            [void]$hmac.TransformBlock($buffer, 0, $read, $null, 0)
            $remaining -= $read
        }
        [void]$hmac.TransformFinalBlock([byte[]]::new(0), 0, 0)
        return $hmac.Hash
    } finally {
        $hmac.Dispose()
        $stream.Dispose()
    }
}

function Protect-TechNexusBackup {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][byte[]]$MasterKey
    )

    $magic = [Text.Encoding]::ASCII.GetBytes('TNXBAK01')
    $encryptionKey = Get-DerivedKey -MasterKey $MasterKey -Purpose 'encryption'
    $authenticationKey = Get-DerivedKey -MasterKey $MasterKey -Purpose 'authentication'
    $cipherPath = "$OutputPath.cipher.$PID"
    $aes = [Security.Cryptography.Aes]::Create()
    try {
        $aes.KeySize = 256
        $aes.Mode = [Security.Cryptography.CipherMode]::CBC
        $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
        $aes.Key = $encryptionKey
        $aes.GenerateIV()

        $input = [IO.File]::OpenRead($InputPath)
        $cipher = [IO.File]::Create($cipherPath)
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $cipher,
            $aes.CreateEncryptor(),
            [Security.Cryptography.CryptoStreamMode]::Write
        )
        try {
            $input.CopyTo($crypto)
            $crypto.FlushFinalBlock()
        } finally {
            $crypto.Dispose()
            $input.Dispose()
        }

        $output = [IO.File]::Create($OutputPath)
        $cipherInput = [IO.File]::OpenRead($cipherPath)
        try {
            $output.Write($magic, 0, $magic.Length)
            $output.Write($aes.IV, 0, $aes.IV.Length)
            $cipherInput.CopyTo($output)
        } finally {
            $cipherInput.Dispose()
            $output.Dispose()
        }

        $authenticatedLength = (Get-Item -LiteralPath $OutputPath).Length
        $tag = Get-HmacForPrefix -Path $OutputPath -Length $authenticatedLength -Key $authenticationKey
        $append = [IO.File]::Open($OutputPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $append.Write($tag, 0, $tag.Length)
        } finally {
            $append.Dispose()
        }
    } finally {
        $aes.Dispose()
        [Array]::Clear($encryptionKey, 0, $encryptionKey.Length)
        [Array]::Clear($authenticationKey, 0, $authenticationKey.Length)
        if (Test-Path -LiteralPath $cipherPath) { Remove-Item -LiteralPath $cipherPath -Force }
    }
}

function Unprotect-TechNexusBackup {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][byte[]]$MasterKey
    )

    $magic = [Text.Encoding]::ASCII.GetBytes('TNXBAK01')
    $file = Get-Item -LiteralPath $InputPath
    if ($file.Length -lt 57) { throw 'Encrypted backup is too short.' }
    $authenticatedLength = $file.Length - 32
    $cipherLength = $authenticatedLength - $magic.Length - 16
    $encryptionKey = Get-DerivedKey -MasterKey $MasterKey -Purpose 'encryption'
    $authenticationKey = Get-DerivedKey -MasterKey $MasterKey -Purpose 'authentication'
    $cipherPath = "$OutputPath.cipher.$PID"

    try {
        $stream = [IO.File]::OpenRead($InputPath)
        try {
            $header = [byte[]]::new($magic.Length)
            if ($stream.Read($header, 0, $header.Length) -ne $header.Length -or
                -not (Test-ConstantTimeByteArray -Left $header -Right $magic)) {
                throw 'Encrypted backup has an invalid header.'
            }
            $iv = [byte[]]::new(16)
            if ($stream.Read($iv, 0, $iv.Length) -ne $iv.Length) { throw 'Encrypted backup has no IV.' }
            $stream.Position = $authenticatedLength
            $storedTag = [byte[]]::new(32)
            if ($stream.Read($storedTag, 0, $storedTag.Length) -ne $storedTag.Length) {
                throw 'Encrypted backup has no authentication tag.'
            }
        } finally {
            $stream.Dispose()
        }

        $actualTag = Get-HmacForPrefix -Path $InputPath -Length $authenticatedLength -Key $authenticationKey
        if (-not (Test-ConstantTimeByteArray -Left $storedTag -Right $actualTag)) {
            throw 'Encrypted backup authentication failed; the file or key is invalid.'
        }

        $source = [IO.File]::OpenRead($InputPath)
        $cipher = [IO.File]::Create($cipherPath)
        try {
            $source.Position = $magic.Length + 16
            $buffer = [byte[]]::new(1MB)
            $remaining = $cipherLength
            while ($remaining -gt 0) {
                $count = [int][Math]::Min($buffer.Length, $remaining)
                $read = $source.Read($buffer, 0, $count)
                if ($read -le 0) { throw 'Encrypted backup ciphertext is truncated.' }
                $cipher.Write($buffer, 0, $read)
                $remaining -= $read
            }
        } finally {
            $cipher.Dispose()
            $source.Dispose()
        }

        $aes = [Security.Cryptography.Aes]::Create()
        try {
            $aes.KeySize = 256
            $aes.Mode = [Security.Cryptography.CipherMode]::CBC
            $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
            $aes.Key = $encryptionKey
            $aes.IV = $iv
            $cipherInput = [IO.File]::OpenRead($cipherPath)
            $crypto = [Security.Cryptography.CryptoStream]::new(
                $cipherInput,
                $aes.CreateDecryptor(),
                [Security.Cryptography.CryptoStreamMode]::Read
            )
            $output = [IO.File]::Create($OutputPath)
            try {
                $crypto.CopyTo($output)
            } finally {
                $output.Dispose()
                $crypto.Dispose()
                $cipherInput.Dispose()
            }
        } finally {
            $aes.Dispose()
        }
    } finally {
        [Array]::Clear($encryptionKey, 0, $encryptionKey.Length)
        [Array]::Clear($authenticationKey, 0, $authenticationKey.Length)
        if (Test-Path -LiteralPath $cipherPath) { Remove-Item -LiteralPath $cipherPath -Force }
    }
}

function Compress-TechNexusBackup {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $input = [IO.File]::OpenRead($InputPath)
    $output = [IO.File]::Create($OutputPath)
    $gzip = [IO.Compression.GZipStream]::new($output, [IO.Compression.CompressionLevel]::Optimal)
    try { $input.CopyTo($gzip) } finally { $gzip.Dispose(); $output.Dispose(); $input.Dispose() }
}

function Expand-TechNexusBackup {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $input = [IO.File]::OpenRead($InputPath)
    $gzip = [IO.Compression.GZipStream]::new($input, [IO.Compression.CompressionMode]::Decompress)
    $output = [IO.File]::Create($OutputPath)
    try { $gzip.CopyTo($output) } finally { $output.Dispose(); $gzip.Dispose(); $input.Dispose() }
}

function Get-TechNexusDatabaseCredential {
    param([string]$CredentialFile = 'D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt')

    $values = @{}
    if (Test-Path -LiteralPath $CredentialFile) {
        foreach ($line in [IO.File]::ReadAllLines($CredentialFile)) {
            if ($line -match '^([A-Z0-9_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    $url = if ($env:TECHNEXUS_DB_URL) { $env:TECHNEXUS_DB_URL } else { $values['TECHNEXUS_DB_URL'] }
    $user = if ($env:TECHNEXUS_DB_USER) { $env:TECHNEXUS_DB_USER } else { $values['TECHNEXUS_DB_USER'] }
    $password = if ($env:TECHNEXUS_DB_PASSWORD) { $env:TECHNEXUS_DB_PASSWORD } else { $values['TECHNEXUS_DB_PASSWORD'] }
    if (-not $url -or -not $user -or -not $password) {
        throw 'Database URL, user and password must be supplied through TECHNEXUS_DB_* or the credential file.'
    }
    if ($url -notmatch '^jdbc:mysql://([^/:?]+)(?::([0-9]+))?/([^?]+)') {
        throw 'TECHNEXUS_DB_URL is not a supported MySQL JDBC URL.'
    }
    [pscustomobject]@{
        Host = $Matches[1]
        Port = if ($Matches[2]) { [int]$Matches[2] } else { 3306 }
        Database = $Matches[3]
        User = $user
        Password = $password
    }
}

function New-TechNexusMySqlDefaultsFile {
    param([Parameter(Mandatory = $true)]$Credential)

    foreach ($value in @($Credential.Host, $Credential.User, $Credential.Password)) {
        if ($value -match "[\r\n]") { throw 'MySQL credentials must not contain newline characters.' }
    }
    $path = Join-Path ([IO.Path]::GetTempPath()) ("technexus-mysql-{0}.cnf" -f [Guid]::NewGuid().ToString('N'))
    $escape = { param([string]$Value) $Value.Replace('\', '\\').Replace('"', '\"') }
    $content = @(
        '[client]'
        ('host="{0}"' -f (& $escape $Credential.Host))
        ('port={0}' -f $Credential.Port)
        ('user="{0}"' -f (& $escape $Credential.User))
        ('password="{0}"' -f (& $escape $Credential.Password))
        'protocol=tcp'
    ) -join "`n"
    [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
    if ($IsWindows) {
        & icacls.exe $path '/inheritance:r' "/grant:r" "$($env:USERNAME):(R,W)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary MySQL option file ACL.' }
    }
    return $path
}

function Invoke-TechNexusProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$StandardInputPath,
        [string]$StandardOutputPath
    )

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.UseShellExecute = $false
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = [bool]$StandardInputPath
    $start.RedirectStandardOutput = $true
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if ($StandardInputPath) {
        $reader = [IO.File]::OpenText($StandardInputPath)
        try { $reader.BaseStream.CopyTo($process.StandardInput.BaseStream) } finally { $reader.Dispose(); $process.StandardInput.Close() }
    }
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "Process $([IO.Path]::GetFileName($FilePath)) failed with exit code $($process.ExitCode): $stderr"
    }
    if ($StandardOutputPath) {
        [IO.File]::WriteAllText($StandardOutputPath, $stdout, [Text.UTF8Encoding]::new($false))
    }
    return $stdout
}

function Assert-TechNexusRemoteTarget {
    param(
        [Parameter(Mandatory = $true)][string]$TargetRoot,
        [switch]$AllowLocalTargetForTest
    )

    $isUnc = $TargetRoot.StartsWith('\\')
    if (-not $isUnc -and -not $AllowLocalTargetForTest) {
        throw 'The backup target must be a UNC path on another host. Use -AllowLocalTargetForTest only for a non-gating local rehearsal.'
    }
    if ($isUnc) {
        $server = ($TargetRoot.TrimStart('\') -split '\\')[0]
        if (-not $server) { throw 'The UNC backup target has no server name.' }
        $localNames = @('localhost', '.', '127.0.0.1', '::1', [Environment]::MachineName)
        if ($localNames -contains $server) {
            throw 'The UNC backup target resolves to the current host and is not off-host evidence.'
        }
        try {
            $targetAddresses = @([Net.Dns]::GetHostAddresses($server) | ForEach-Object { $_.ToString() })
            $localAddresses = @([Net.Dns]::GetHostAddresses([Environment]::MachineName) | ForEach-Object { $_.ToString() }) + @('127.0.0.1', '::1')
            if (@($targetAddresses | Where-Object { $localAddresses -contains $_ }).Count -gt 0) {
                throw 'The UNC backup target resolves to an address on the current host and is not off-host evidence.'
            }
        } catch {
            if ($_.Exception.Message -like '*current host*') { throw }
            throw "The UNC backup host cannot be resolved: $server"
        }
    }
    return $(if ($isUnc) { 'REMOTE_UNC' } else { 'LOCAL_TEST_ONLY' })
}

Export-ModuleMember -Function @(
    'Get-TechNexusBackupKey',
    'Protect-TechNexusBackup',
    'Unprotect-TechNexusBackup',
    'Compress-TechNexusBackup',
    'Expand-TechNexusBackup',
    'Get-TechNexusDatabaseCredential',
    'New-TechNexusMySqlDefaultsFile',
    'Invoke-TechNexusProcess',
    'Assert-TechNexusRemoteTarget'
)
