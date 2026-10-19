$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path (Join-Path $root '.local') -Force | Out-Null
try {
    $result = Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -All -NoRestart
    $result | Select-Object Online, RestartNeeded | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.local/docker-feature.json') -Encoding utf8
} catch {
    $_.Exception.Message | Set-Content -LiteralPath (Join-Path $root '.local/docker-feature-error.txt') -Encoding utf8
    exit 1
}
