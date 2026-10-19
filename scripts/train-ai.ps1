param(
    [Parameter(Mandatory=$true)][datetime]$StartDate,
    [Parameter(Mandatory=$true)][datetime]$EndDate,
    [ValidateRange(1,5000)][int]$Stations=200,
    [switch]$SmokeTest,
    [switch]$ZeroFillActiveDays
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$python=Join-Path $root '.venv-ai\Scripts\python.exe'
if(!(Test-Path -LiteralPath $python)){throw 'Install the ai project into .venv-ai first; see ai/README.md.'}
if($StartDate.Date -gt $EndDate.Date){throw 'StartDate must precede EndDate.'}
# Fail before starting Docker if a moved environment still points to an old source directory.
& $python -B -c 'import ddareungrider_ai.cli'
if($LASTEXITCODE -ne 0){throw 'AI environment is unavailable. From the project root run: .\.venv-ai\Scripts\python.exe -m pip install --no-deps -e ./ai'}
$dockerPath=Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
$envFile=Join-Path $root '.env'
$compose=Join-Path $root 'compose.yaml'
$runId=Get-Date -Format 'yyyyMMdd-HHmmss'
$data=Join-Path $root "ai\data\$runId"
$artifacts=Join-Path $root "ai\artifacts\$runId"
$startedDocker=$false;$startedMysql=$false
Push-Location $root
try{
    & $dockerPath info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if($LASTEXITCODE -ne 0){$startedDocker=$true;& $dockerPath desktop start --timeout 60;if($LASTEXITCODE -ne 0){throw 'Docker startup failed.'}}
    $state=& $dockerPath compose --env-file $envFile -f $compose ps mysql --format '{{.State}}'
    if($state -ne 'running'){
        $startedMysql=$true
        & $dockerPath compose --env-file $envFile -f $compose up -d --no-deps --wait --wait-timeout 60 mysql
        if($LASTEXITCODE -ne 0){throw 'MySQL startup failed.'}
    }
    $exportArgs=@('-m','ddareungrider_ai.cli','export-od','--start',$StartDate.ToString('yyyy-MM-dd'),'--end',$EndDate.ToString('yyyy-MM-dd'),'--output',$data)
    if($ZeroFillActiveDays){$exportArgs+='--zero-fill-active-days'}
    & $python @exportArgs
    if($LASTEXITCODE -ne 0){throw 'AI export failed.'}
    foreach($target in @('rentals','returns')){
        $trainArgs=@('-m','ddareungrider_ai.cli','train','--data',$data,'--output',(Join-Path $artifacts $target),'--target',$target,'--stations',"$Stations")
        if($SmokeTest){$trainArgs+='--smoke-test'}
        & $python @trainArgs
        if($LASTEXITCODE -ne 0){throw "AI training failed: $target"}
    }
    Write-Host "Offline experiment saved: $artifacts"
}finally{
    if($startedMysql){& $dockerPath compose --env-file $envFile -f $compose stop mysql}
    if($startedDocker){& $dockerPath desktop stop --timeout 60}
    Pop-Location
}
