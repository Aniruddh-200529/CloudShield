[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$environmentNames = @(
    'JAVA_HOME', 'DB_PASSWORD', 'PGPASSWORD', 'PROBE_API_KEY',
    'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD', 'MFA_ENCRYPTION_KEY',
    'CLOUDSHIELD_BACKEND_URL', 'CLOUDSHIELD_PROBE_IDENTIFIER',
    'CLOUDSHIELD_RESOURCE_IDENTIFIER', 'CLOUDSHIELD_PROBE_COLLECTION_INTERVAL_MS'
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Write-Result([string]$label, [string]$message) {
    Write-Host "[$label] $message"
}

function Read-Secret([string]$prompt) {
    $secure = Read-Host -Prompt $prompt -AsSecureString
    if ($secure.Length -eq 0) {
        $secure.Dispose()
        throw "$prompt is required."
    }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

function Set-ProcessEnvironment([string]$name, [AllowNull()][string]$value) {
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

function ConvertTo-WindowsCommandLineArgument([string]$value) {
    # ProcessStartInfo.ArgumentList is unavailable on Windows PowerShell's .NET Framework.
    # Quote each argument using the CommandLineToArgvW backslash/quote convention.
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append([char]34)
    $backslashCount = 0
    foreach ($character in $value.ToCharArray()) {
        if ([int]$character -eq 92) {
            $backslashCount++
            continue
        }
        if ([int]$character -eq 34) {
            for ($index = 0; $index -lt (2 * $backslashCount + 1); $index++) {
                [void]$builder.Append([char]92)
            }
            [void]$builder.Append([char]34)
            $backslashCount = 0
            continue
        }
        for ($index = 0; $index -lt $backslashCount; $index++) {
            [void]$builder.Append([char]92)
        }
        $backslashCount = 0
        [void]$builder.Append($character)
    }
    for ($index = 0; $index -lt (2 * $backslashCount); $index++) {
        [void]$builder.Append([char]92)
    }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function Invoke-CloudShieldPsql([string]$sql, [hashtable]$variables = @{}) {
    $arguments = @('-X', '-w', '-h', 'localhost', '-p', '5432', '-U', 'postgres', '-d', 'cloudshield', '-v', 'ON_ERROR_STOP=1', '-tA')
    foreach ($name in $variables.Keys) {
        $arguments += @('-v', "$name=$($variables[$name])")
    }
    # psql does not interpolate :'name' placeholders in a command passed with -c.
    # Read from stdin (-f -) so the -v values are quoted and expanded by psql itself.
    $arguments += @('-f', '-')
    $output = $sql | & $script:psqlPath @arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'A read-only query against cloudshield failed. Check that local PostgreSQL is running and the entered database credential is correct.'
    }
    return (($output -join "`n").Trim())
}

function ConvertFrom-JavaVersionText([string]$versionText) {
    # Covers both `java version "21.0..."` and `openjdk 21.0...` banners.
    if ($versionText -match '(?im)^\s*(?:openjdk|java)(?:\s+version)?\s+"?(\d+)(?:[._+\-"\s]|$)') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-JavaMajorVersion([string]$javaPath) {
    # java -version writes to stderr. Redirect through ProcessStartInfo so PowerShell 7's
    # native-stderr error handling cannot turn a valid version banner into a terminating error.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaPath
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) { return $null }
    try {
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $versionText = $standardOutput + [Environment]::NewLine + $standardError
        return (ConvertFrom-JavaVersionText $versionText)
    } finally {
        $process.Dispose()
    }
}

function Find-Java21 {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $javaFromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaFromHome)) {
            throw 'JAVA_HOME is set but its bin\java.exe was not found. Correct JAVA_HOME or clear it to use PATH.'
        }
        # An explicit JAVA_HOME is authoritative; never silently select a different JDK from PATH.
        $candidates = @($javaFromHome)
    } else {
        $candidates = @(& where.exe java 2>$null | Where-Object { $_ })
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $majorVersion = Get-JavaMajorVersion $candidate
        if ($null -ne $majorVersion -and $majorVersion -ge 21) {
            return [pscustomobject]@{
                Java = $candidate
                Home = (Split-Path (Split-Path $candidate -Parent) -Parent)
                MajorVersion = $majorVersion
            }
        }
    }
    throw 'Java 21 or newer was not found. Install a Java 21 JDK and add its java.exe to PATH or JAVA_HOME.'
}

function Assert-PortAvailable([int]$port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try {
        $listener.Start()
    } catch {
        throw "Port $port is already in use. Stop or reconfigure that application; the demo will not terminate an existing process."
    } finally {
        $listener.Stop()
    }
}

function Start-ManagedProcess([string]$executable, [string[]]$arguments, [string]$workingDirectory, [hashtable]$environment) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $executable
    $startInfo.WorkingDirectory = $workingDirectory
    $startInfo.UseShellExecute = $false
    foreach ($secretName in @('DB_PASSWORD', 'PGPASSWORD', 'PROBE_API_KEY', 'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD', 'MFA_ENCRYPTION_KEY')) {
        [void]$startInfo.EnvironmentVariables.Remove($secretName)
    }
    foreach ($entry in $environment.GetEnumerator()) {
        $startInfo.EnvironmentVariables[$entry.Key] = [string]$entry.Value
    }
    $startInfo.Arguments = (($arguments | ForEach-Object { ConvertTo-WindowsCommandLineArgument ([string]$_) }) -join ' ')
    $process = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) { throw "Could not start $([IO.Path]::GetFileName($executable))." }
    $appProcesses.Add($process)
    return $process
}

function Wait-ForHttp([string]$url, [System.Diagnostics.Process]$process, [int]$timeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    $lastCheck = 'No response received yet.'
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) {
            throw "Readiness check failed for $url because its managed process exited with code $($process.ExitCode). Review the application output above."
        }
        try {
            $response = Invoke-WebRequest -Uri $url -TimeoutSec 2 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) { return }
            $lastCheck = "HTTP $($response.StatusCode)"
        } catch {
            # Keep diagnostics useful without printing a response body, headers, or credentials.
            $httpStatus = $null
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                try { $httpStatus = [int]$_.Exception.Response.StatusCode } catch { }
            }
            if ($httpStatus) {
                $lastCheck = "HTTP $httpStatus"
            } else {
                $lastCheck = $_.Exception.GetType().Name
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "Readiness check failed for $url after $timeoutSeconds seconds; last result: $lastCheck. Application stdout/stderr is attached to this console. Review it above."
}

function Stop-ManagedProcess([System.Diagnostics.Process]$process) {
    try {
        $process.Refresh()
        if (-not $process.HasExited) {
            # Each managed process is launched directly as Java or Node; never search for or stop other processes.
            $process.Kill()
            [void]$process.WaitForExit(10000)
        }
    } catch { }
    $process.Dispose()
}

$failure = $null
try {
    if ($PSVersionTable.PSVersion -lt [Version]'5.1') {
        throw 'Windows PowerShell 5.1 or PowerShell 7 or newer is required.'
    }
    Set-Location $repoRoot
    $java21 = Find-Java21
    Set-ProcessEnvironment 'JAVA_HOME' $java21.Home
    Write-Result PASS ("Java major version {0} detected." -f $java21.MajorVersion)

    $script:psqlPath = (Get-Command psql -ErrorAction Stop).Source
    $nodePath = (Get-Command node -ErrorAction Stop).Source
    $npmPath = (Get-Command npm -ErrorAction Stop).Source
    if (-not (Test-Path (Join-Path $repoRoot 'frontend/node_modules/vite/bin/vite.js'))) {
        Write-Host 'Frontend dependencies are missing; installing the locked npm dependencies.'
        Push-Location (Join-Path $repoRoot 'frontend')
        try {
            & $npmPath ci
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed. Check Node/npm installation and network access.' }
        } finally { Pop-Location }
    }
    Write-Result PASS 'PostgreSQL client and frontend runtime are available.'

    Assert-PortAvailable 8080
    Assert-PortAvailable 8081
    Assert-PortAvailable 5173

    # Build executable jars before loading credentials into the process environment.
    foreach ($module in @('backend', 'probe')) {
        Push-Location (Join-Path $repoRoot $module)
        try {
            & .\mvnw.cmd -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "$module package build failed." }
        } finally { Pop-Location }
    }
    Write-Result PASS 'Backend and probe packages built.'

    if (-not $env:DB_PASSWORD) { Set-ProcessEnvironment 'DB_PASSWORD' (Read-Secret 'PostgreSQL password for cloudshield') }
    Set-ProcessEnvironment 'PGPASSWORD' $env:DB_PASSWORD
    if (-not $env:PROBE_API_KEY) { Set-ProcessEnvironment 'PROBE_API_KEY' (Read-Secret 'Shared backend/probe API key') }

    $databaseName = Invoke-CloudShieldPsql 'SELECT current_database();'
    if ($databaseName -ne 'cloudshield') { throw 'The configured connection did not select cloudshield; refusing to continue.' }
    Write-Result PASS 'PostgreSQL is reachable and the selected database is cloudshield.'

    Push-Location (Join-Path $repoRoot 'backend')
    try {
        $null = & .\mvnw.cmd -q flyway:info 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Flyway info failed; migrations were not changed.' }
    } finally { Pop-Location }
    $appliedVersionsText = Invoke-CloudShieldPsql 'SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank;'
    $appliedVersions = @($appliedVersionsText -split "`n" | Where-Object { $_ })
    $failedVersions = Invoke-CloudShieldPsql 'SELECT count(*) FROM flyway_schema_history WHERE success = FALSE;'
    $migrationDirectory = Join-Path $repoRoot 'backend/src/main/resources/db/migration'
    $expectedVersions = @(Get-ChildItem $migrationDirectory -Filter 'V*__*.sql' | ForEach-Object { if ($_.Name -match '^V([0-9]+)__') { $Matches[1] } })
    $missingVersions = @($expectedVersions | Where-Object { $_ -notin $appliedVersions })
    $unexpectedVersions = @($appliedVersions | Where-Object { $_ -notin $expectedVersions })
    if ($failedVersions -ne '0' -or $missingVersions.Count -gt 0 -or $unexpectedVersions.Count -gt 0) {
        throw 'Database migrations do not exactly match the local migration files or include a failed entry. Review flyway:info and resolve migrations manually; this script will not apply migrations.'
    }
    Write-Result PASS ("Flyway versions {0} are successful; no migration was applied by this script." -f ($expectedVersions -join ', '))

    $adminCount = [int](Invoke-CloudShieldPsql "SELECT count(*) FROM user_accounts WHERE role = 'ADMIN' AND enabled = TRUE;")
    $bootstrapEnvironment = @{}
    if ($adminCount -eq 0) {
        if (-not $env:BOOTSTRAP_ADMIN_USERNAME) {
            Set-ProcessEnvironment 'BOOTSTRAP_ADMIN_USERNAME' (Read-Host 'No enabled admin exists. Initial admin username')
        }
        if (-not $env:BOOTSTRAP_ADMIN_PASSWORD) {
            Set-ProcessEnvironment 'BOOTSTRAP_ADMIN_PASSWORD' (Read-Secret 'Initial admin password (14-200 characters)')
        }
        if ($env:BOOTSTRAP_ADMIN_PASSWORD.Length -lt 14 -or $env:BOOTSTRAP_ADMIN_PASSWORD.Length -gt 200) {
            throw 'The initial admin password must be 14-200 characters.'
        }
        $bootstrapEnvironment['BOOTSTRAP_ADMIN_USERNAME'] = $env:BOOTSTRAP_ADMIN_USERNAME
        $bootstrapEnvironment['BOOTSTRAP_ADMIN_PASSWORD'] = $env:BOOTSTRAP_ADMIN_PASSWORD
    }

    $mfaAccountCount = [int](Invoke-CloudShieldPsql 'SELECT count(*) FROM user_accounts WHERE enabled = TRUE AND mfa_enabled = TRUE;')
    if ($mfaAccountCount -gt 0 -and -not $env:MFA_ENCRYPTION_KEY) {
        Set-ProcessEnvironment 'MFA_ENCRYPTION_KEY' (Read-Secret 'MFA is enabled for an account; enter the configured Base64 encryption key')
    }

    $resourceIdentifier = Invoke-CloudShieldPsql "SELECT resource_identifier FROM monitored_resources ORDER BY (status = 'ACTIVE') DESC, created_at DESC LIMIT 1;"
    if ([string]::IsNullOrWhiteSpace($resourceIdentifier)) {
        throw 'No monitored resource exists in cloudshield. Register one through the authenticated API, then rerun the demo.'
    }
    $probeIdentifier = 'classroom-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $startedAt = [DateTime]::UtcNow
    $backendEnvironment = @{
        'DB_PASSWORD' = $env:DB_PASSWORD
        'PROBE_API_KEY' = $env:PROBE_API_KEY
    }
    foreach ($entry in $bootstrapEnvironment.GetEnumerator()) { $backendEnvironment[$entry.Key] = $entry.Value }
    if ($env:MFA_ENCRYPTION_KEY) { $backendEnvironment['MFA_ENCRYPTION_KEY'] = $env:MFA_ENCRYPTION_KEY }

    # The migration/plugin and psql checks are complete. Do not pass DB/admin credentials to probe or frontend.
    $backendJar = Get-ChildItem (Join-Path $repoRoot 'backend/target') -Filter 'cloudshield-backend-*.jar' |
        Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-sources.jar' } | Select-Object -First 1
    $probeJar = Get-ChildItem (Join-Path $repoRoot 'probe/target') -Filter 'cloudshield-probe-*.jar' |
        Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-sources.jar' } | Select-Object -First 1
    if (-not $backendJar -or -not $probeJar) { throw 'An executable application jar is missing after packaging.' }

    $backendUrl = 'http://localhost:8080'
    $backend = Start-ManagedProcess $java21.Java @('-jar', $backendJar.FullName) (Join-Path $repoRoot 'backend') $backendEnvironment
    Wait-ForHttp ($backendUrl + '/api/health') $backend 120
    Write-Result PASS 'Backend health endpoint is ready.'

    $probeEnvironment = @{
        'PROBE_API_KEY' = $env:PROBE_API_KEY
        'CLOUDSHIELD_BACKEND_URL' = $backendUrl
        'CLOUDSHIELD_PROBE_IDENTIFIER' = $probeIdentifier
        'CLOUDSHIELD_RESOURCE_IDENTIFIER' = $resourceIdentifier
        'CLOUDSHIELD_PROBE_COLLECTION_INTERVAL_MS' = '5000'
    }
    if (-not [string]::Equals($backendEnvironment['PROBE_API_KEY'], $probeEnvironment['PROBE_API_KEY'], [StringComparison]::Ordinal)) {
        throw 'The backend and probe service key configuration does not match.'
    }
    if ($probeEnvironment['CLOUDSHIELD_RESOURCE_IDENTIFIER'] -cne $resourceIdentifier) {
        throw 'The probe resource identifier does not match the selected database resource.'
    }
    $probeProcess = Start-ManagedProcess $java21.Java @('-jar', $probeJar.FullName) (Join-Path $repoRoot 'probe') $probeEnvironment
    Write-Result PASS ("Probe configured to submit to the launched backend at {0} using the selected resource." -f $backendUrl)

    $viteScript = Join-Path $repoRoot 'frontend/node_modules/vite/bin/vite.js'
    $frontend = Start-ManagedProcess $nodePath @($viteScript, '--host', '127.0.0.1') (Join-Path $repoRoot 'frontend') @{}
    # Vite is explicitly bound to IPv4 loopback. Use that same address for readiness;
    # localhost may resolve to IPv6 first on Windows and fail despite Vite being ready.
    Wait-ForHttp 'http://127.0.0.1:5173' $frontend 60
    Write-Result PASS 'Frontend dashboard is ready at http://localhost:5173.'
    Write-Host "Selected existing resource: $resourceIdentifier"
    Write-Host "Probe identifier for this run: $probeIdentifier"

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $heartbeatCount = '0'
    $metricCount = '0'
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($process in $appProcesses) {
            $process.Refresh()
            if ($process.HasExited) { throw 'A demo application exited while waiting for telemetry.' }
        }
        $heartbeatCount = Invoke-CloudShieldPsql "SELECT count(*) FROM probe_heartbeats h JOIN monitored_resources r ON r.id = h.resource_id WHERE h.probe_identifier = :'probe' AND r.resource_identifier = :'resource';" @{ probe = $probeIdentifier; resource = $resourceIdentifier }
        $metricCount = Invoke-CloudShieldPsql "SELECT count(*) FROM metric_samples m JOIN monitored_resources r ON r.id = m.resource_id WHERE m.probe_identifier = :'probe' AND r.resource_identifier = :'resource' AND m.collected_at >= :'started'::timestamptz;" @{ probe = $probeIdentifier; resource = $resourceIdentifier; started = $startedAt.ToString('o') }
        if ([int]$heartbeatCount -gt 0 -and [int]$metricCount -gt 0) { break }
        Start-Sleep -Seconds 2
    }
    if ([int]$heartbeatCount -lt 1) { throw 'No heartbeat row was persisted for this probe run within 90 seconds. Check the probe HTTP status in the output above.' }
    if ([int]$metricCount -lt 1) { throw 'No metric sample row was persisted for this probe run within 90 seconds. Check the probe HTTP status in the output above.' }
    Write-Result PASS "Persisted $heartbeatCount heartbeat(s) and $metricCount metric sample(s) for this run."
    Write-Host 'Open the dashboard at http://localhost:5173 and sign in with the existing CloudShield administrator account.'
    Write-Host 'Demo is running. Press Ctrl+C here to stop only the backend, probe, and frontend processes started by this script.'

    while ($true) {
        foreach ($process in $appProcesses) {
            $process.Refresh()
            if ($process.HasExited) { throw 'A demo application stopped unexpectedly.' }
        }
        Start-Sleep -Seconds 2
    }
} catch {
    $failure = $_.Exception.Message
    Write-Result FAIL $failure
} finally {
    for ($index = $appProcesses.Count - 1; $index -ge 0; $index--) {
        Stop-ManagedProcess $appProcesses[$index]
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
    }
}

if ($failure) { exit 1 }
