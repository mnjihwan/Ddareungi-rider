$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
foreach ($name in @('web', 'backend')) {
    $stateFile = Join-Path $root ".local/$name.json"
    if (-not (Test-Path -LiteralPath $stateFile)) { continue }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $process = Get-Process -Id $state.Id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().ToString('o') -eq $state.StartTime) {
        & taskkill.exe /PID $process.Id /T /F | Out-Null
    }
    Remove-Item -LiteralPath $stateFile
}
$docker = Get-Command docker.exe -ErrorAction SilentlyContinue
$dockerPath = if ($docker) { $docker.Source } else { Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe' }
if (Test-Path -LiteralPath $dockerPath) {
    & $dockerPath compose --env-file (Join-Path $root '.env') -f (Join-Path $root 'compose.yaml') --profile real stop
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Docker engine is unavailable; host processes have been stopped.' }
}
Write-Host 'Stopped local services. Database volumes were preserved.'
