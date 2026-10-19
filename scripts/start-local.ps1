param([ValidateSet('Sample', 'Real')][string]$Mode = 'Sample')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$rider = Join-Path $root 'Ddareungi-rider'
$web = Join-Path $rider 'web'
$stateDirectory = Join-Path $root '.local'
New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null

# Read values without evaluating shell expressions or displaying credentials.
$localEnvFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $localEnvFile) {
    foreach ($line in Get-Content -LiteralPath $localEnvFile) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $variableName = $Matches[1]
            if ($variableName -in @('SEOUL_API_KEY', 'SEOUL_API_BASE_URL', 'ORS_API_KEY', 'KAKAO_MAPS_REST_API_KEY')) {
                [Environment]::SetEnvironmentVariable($variableName, $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
            }
        }
    }
}

function Wait-Url([string]$Url, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try { $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3; if ($response.StatusCode -eq 200) { return } } catch {}
        Start-Sleep -Seconds 2
    }
    throw "Startup timed out: $Url. Check .local/*.log."
}

function Assert-FreePort([int]$Port) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $Port is already in use. Run scripts/stop-local.ps1 or stop the existing service first."
    }
}

function Save-Process($Process, [string]$Name) {
    @{ Id = $Process.Id; StartTime = $Process.StartTime.ToUniversalTime().ToString('o'); Name = $Name } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateDirectory "$Name.json") -Encoding utf8
}

Assert-FreePort 5173
if ($Mode -eq 'Sample') { Assert-FreePort 8080 }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is required.' }
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Node.js and npm are required.' }
Push-Location $web
try {
    if (-not (Test-Path 'node_modules')) { & npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' } }
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'Web typecheck failed.' }
} finally { Pop-Location }

if ($Mode -eq 'Real') {
    $envFile = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envFile)) { throw 'Copy .env.example to .env and set the credentials.' }
    $keyLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^SEOUL_API_KEY\s*=' } | Select-Object -First 1
    if (-not $keyLine -or [string]::IsNullOrWhiteSpace(($keyLine -split '=', 2)[1])) { throw 'Set SEOUL_API_KEY in the root .env before using Real mode.' }
    $docker = Get-Command docker.exe -ErrorAction SilentlyContinue
    $dockerPath = if ($docker) { $docker.Source } else { Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe' }
    if (-not (Test-Path -LiteralPath $dockerPath)) { throw 'Install Docker Desktop and restart your terminal.' }
    & $dockerPath info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop; restart Windows if VirtualMachinePlatform was just enabled.' }
    & $dockerPath compose --env-file $envFile -f (Join-Path $root 'compose.yaml') --profile real up -d --build --wait --wait-timeout 300
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed. Inspect docker compose logs.' }
} else {
    Push-Location $rider
    try {
        & .\gradlew.bat :backend:bootJar --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
    $jar = Get-ChildItem -LiteralPath (Join-Path $rider 'backend/build/libs') -Filter '*.jar' | Where-Object { $_.Name -notmatch '-plain\.jar$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Backend jar not found.' }
    $backendProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $jar.FullName + '"'), '--spring.profiles.active=local') -WorkingDirectory $rider -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stateDirectory 'backend.log') -RedirectStandardError (Join-Path $stateDirectory 'backend-error.log')
    Save-Process $backendProcess 'backend'
}

Wait-Url 'http://localhost:8080/actuator/health'
$webProcess = Start-Process -FilePath $env:ComSpec -ArgumentList '/d /c npm.cmd run dev' -WorkingDirectory $web -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stateDirectory 'web.log') -RedirectStandardError (Join-Path $stateDirectory 'web-error.log')
Save-Process $webProcess 'web'
Wait-Url 'http://localhost:5173'
Write-Host "Started $Mode mode: http://localhost:5173"
Write-Host 'On a phone, open http://<PC-LAN-IP>:5173 on the same Wi-Fi. Geolocation requires HTTPS; place and station search works on HTTP.'
