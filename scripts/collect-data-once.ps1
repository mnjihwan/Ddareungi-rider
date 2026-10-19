param(
    [ValidateRange(1,7)][int]$DownloadMaxFiles=2,
    [string]$UsageTasks='',
    [string]$DownloadsDirectory=(Join-Path $env:USERPROFILE 'Downloads'),
    [switch]$SkipDownload
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$collector=Join-Path $root 'bikeseoul_data'
$dockerPath=Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
$envFile=Join-Path $root '.env'
$odDirectory=Join-Path $collector 'data\oa-21229'
$usageDirectory=Join-Path $collector 'data\usage'
if([string]::IsNullOrWhiteSpace($UsageTasks)){
    $localKoreanToday=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'Korea Standard Time').Date
    $localPreviousMonth=$localKoreanToday.AddDays(1-$localKoreanToday.Day).AddMonths(-1).ToString('yyyy-MM')
    $localPublishedDay=$localKoreanToday.AddDays(-6).ToString('yyyy-MM-dd')
    $localUsageTasks=@("station-month:$localPreviousMonth","day:$localPublishedDay")
    $localUsageTasks+=@(0..23 | ForEach-Object {"hour:${localPublishedDay}:$_"})
    $UsageTasks=$localUsageTasks -join ','
}
New-Item -ItemType Directory -Path $odDirectory,$usageDirectory,(Join-Path $root '.local') -Force | Out-Null
$values=@{}
foreach($line in Get-Content -LiteralPath $envFile){
    if($line -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$'){$values[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'")}
}
if([string]::IsNullOrWhiteSpace($values['SEOUL_API_KEY'])){throw 'SEOUL_API_KEY is required in the root .env.'}
if(Test-Path -LiteralPath $DownloadsDirectory){
    foreach($file in Get-ChildItem -LiteralPath $DownloadsDirectory -File){
        if($file.Name -match '^tpss_bcycl_od_statnhm_[0-9]{6}([0-9]{2})?\.(csv|zip)$'){
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $odDirectory $file.Name) -Force
        }elseif($file.Name -match '공공자전거.?이용정보.*\.csv$'){
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $usageDirectory $file.Name) -Force
        }
    }
}
Push-Location $collector
try{& .\gradlew.bat bootJar --console=plain;if($LASTEXITCODE -ne 0){throw 'Collector build failed.'}}
finally{Pop-Location}
$trackedNames=@('SEOUL_API_KEY','SEOUL_API_BASE_URL','MYSQL_USER','MYSQL_PASSWORD','FLOW_CSV_INPUT_DIR','USAGE_CSV_INPUT_DIR')
$previousEnvironment=@{}
foreach($name in $trackedNames){
    $previousEnvironment[$name]=@{Present=(Test-Path -LiteralPath "Env:$name");Value=[Environment]::GetEnvironmentVariable($name,'Process')}
}
$startedDocker=$false;$startedMysql=$false
try{
    & $dockerPath info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if($LASTEXITCODE -ne 0){$startedDocker=$true;& $dockerPath desktop start --timeout 60;if($LASTEXITCODE -ne 0){throw 'Docker Desktop startup failed.'}}
    $previousMysql=& $dockerPath compose --env-file $envFile -f (Join-Path $root 'compose.yaml') ps mysql --format '{{.State}}'
    if($previousMysql -ne 'running'){
        $startedMysql=$true
        & $dockerPath compose --env-file $envFile -f (Join-Path $root 'compose.yaml') up -d --no-deps --wait --wait-timeout 60 mysql
        if($LASTEXITCODE -ne 0){throw 'MySQL startup failed.'}
    }
    foreach($name in @('SEOUL_API_KEY','SEOUL_API_BASE_URL','MYSQL_USER','MYSQL_PASSWORD')){
        if($values.ContainsKey($name)){[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
    }
    $env:FLOW_CSV_INPUT_DIR=$odDirectory;$env:USAGE_CSV_INPUT_DIR=$usageDirectory
    $localDbPort=if($values['MYSQL_PORT']){$values['MYSQL_PORT']}else{'3307'}
    $localDbName=if($values['MYSQL_DATABASE']){$values['MYSQL_DATABASE']}else{'ddareungi'}
    $jar=Get-ChildItem -LiteralPath (Join-Path $collector 'build\libs') -Filter '*-SNAPSHOT.jar' | Where-Object {$_.Name -notmatch '-plain\.jar$'} | Select-Object -First 1
    $localJavaArguments=@('-jar',$jar.FullName,'--spring.main.web-application-type=none',
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:$localDbPort/${localDbName}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&rewriteBatchedStatements=true",
        '--collector.collect-on-startup=false','--collector.schedule.cron=-','--collector.master.cron=-','--collector.extended.cron=-',
        '--collector.extended.enabled=true','--collector.extended.once=true',"--collector.extended.download-max-files=$DownloadMaxFiles",
        "--collector.extended.backfill-tasks=$UsageTasks")
    if($SkipDownload){$localJavaArguments+='--collector.extended.download-enabled=false'}
    & java @localJavaArguments 2>&1 | Tee-Object -FilePath (Join-Path $root '.local\collection-once.log')
    if($LASTEXITCODE -ne 0){throw 'Collection failed. See .local/collection-once.log. Previous successful datasets are retained.'}
    Get-Content -LiteralPath (Join-Path $PSScriptRoot 'collector-status.sql') -Raw |
        & $dockerPath compose --env-file $envFile -f (Join-Path $root 'compose.yaml') exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw' |
        Tee-Object -FilePath (Join-Path $root '.local\collection-status.tsv')
    if($LASTEXITCODE -ne 0){throw 'Stored dataset verification failed.'}
    Write-Host 'Collection completed. CSV files remain in bikeseoul_data/data and records are saved to MySQL.'
}finally{
    if($startedMysql){& $dockerPath compose --env-file $envFile -f (Join-Path $root 'compose.yaml') stop mysql}
    if($startedDocker){& $dockerPath desktop stop --timeout 60}
    foreach($name in $trackedNames){
        if($previousEnvironment[$name].Present){[Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name].Value,'Process')}
        else{Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
    }
}
