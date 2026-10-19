param(
    [Parameter(Mandatory=$true)][datetime]$StartDate,
    [Parameter(Mandatory=$true)][datetime]$EndDate,
    [switch]$IncludeDaily,
    [switch]$PlanOnly
)
$ErrorActionPreference='Stop'
if($StartDate.Date -gt $EndDate.Date){throw 'StartDate must precede EndDate.'}
# One complete day per execution avoids command-line length limits and permits retries by day.
for($date=$StartDate.Date;$date -le $EndDate.Date;$date=$date.AddDays(1)){
    $day=$date.ToString('yyyy-MM-dd')
    $tasks=@(0..23 | ForEach-Object {"hour:${day}:$_"})
    if($IncludeDaily){$tasks+="day:$day"}
    if($PlanOnly){Write-Output ($tasks -join ',');continue}
    & (Join-Path $PSScriptRoot 'collect-data-once.ps1') -SkipDownload -UsageTasks ($tasks -join ',')
}
