param([string]$OutputPath)
$ErrorActionPreference='Stop';$PSNativeCommandUseErrorActionPreference=$false
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suite=Join-Path $root ('build/dev-task8/runner-flow-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $suite|Out-Null
$results=[Collections.Generic.List[object]]::new()
function Out-Host { param([Parameter(ValueFromPipeline)]$InputObject) process {} }
$identity=Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot .manifest.json)|ConvertFrom-Json
$ssh=Join-Path $suite ssh.ps1;$scp=Join-Path $suite scp.ps1;$k6=Join-Path $suite k6.ps1
@'
$ErrorActionPreference='Stop'
$command=[string]$args[-1]
$m=Get-Content -Raw -Encoding UTF8 $env:PERF_FLOW_MANIFEST|ConvertFrom-Json
if($command -match 'cat .*baseline-manifest.json'){ $m|ConvertTo-Json -Depth 6;$global:LASTEXITCODE=0;return }
if($command -notmatch 'phase2.sh ([a-z-]+)'){throw 'Unexpected fake SSH command'}
$action=$Matches[1]
if($action -eq 'manifest'){$m|ConvertTo-Json -Depth 6;$global:LASTEXITCODE=0;return}
if($action -eq 'owner'){'11111111-1111-4111-8111-111111111111';$global:LASTEXITCODE=0;return}
$sample=if($command -match '--sample ([\w-]+)'){$Matches[1]}else{'none'}
$c=if($command -match '--candidate ([1-8])'){[int]$Matches[1]}else{1}
$variant=if($command -match '--variant ([\w-]+)'){$Matches[1]}else{'catalog'}
@{action=$action;sample=$sample;candidate=$c;variant=$variant}|ConvertTo-Json -Compress|Add-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/events.jsonl"
$remote=Join-Path $env:PERF_FLOW_HOME "remote/$sample"
if($action -eq 'restore'){
 New-Item -ItemType Directory -Path $remote|Out-Null
 @{instanceId=[guid]::NewGuid().ToString();processStartedAt=[DateTimeOffset]::UtcNow.ToString('o');warmed=$false;candidate=$c;variant=$variant}|ConvertTo-Json|Set-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"
 'fixture restore'|Set-Content -Encoding UTF8 (Join-Path $remote restore.log)
}
if($action -eq 'write-state'){
 $state=Get-Content -Raw -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"|ConvertFrom-Json
 $n=[int]$variant.Replace('order','');$delta=if($state.warmed){30}else{0}
 $products=@(1..$n|ForEach-Object{@{productId=$_;stock=10000000-$delta}})
 if($env:PERF_FLOW_SCENARIO -eq 'stock' -and $delta){$products[0].stock++}
 @{orders=150000+$delta;orderItems=375000+$delta*$n;mileageTotal=100000;products=$products}|ConvertTo-Json -Depth 5
}
if($action -eq 'collect'){
 if($env:PERF_FLOW_SCENARIO -in @('collect','combined')){'fixture collect failure';$global:LASTEXITCODE=9;return}
 New-Item -ItemType Directory -Path $remote -Force|Out-Null
 'fixture collection'|Set-Content -Encoding UTF8 (Join-Path $remote collection.txt)
}
$global:LASTEXITCODE=0
'@|Set-Content -Encoding UTF8 $ssh
@'
$ErrorActionPreference='Stop'
$source=[string]$args[-2];$target=[string]$args[-1];$leaf=Split-Path -Leaf $source
@{action='copy';sample=$leaf}|ConvertTo-Json -Compress|Add-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/events.jsonl"
if($source -match '/before/' -and $env:PERF_FLOW_SCENARIO -in @('copy','combined')){'fixture SCP failure';$global:LASTEXITCODE=1;return}
if($source -match '/before/') {Copy-Item -LiteralPath (Join-Path $env:PERF_FLOW_HOME "remote/$leaf") -Destination $target -Recurse}
elseif($leaf -eq 'users.json'){@(1..20|ForEach-Object{@{email="fixture$_@coffeeprod.local";password='password';addressId=$_}})|ConvertTo-Json|Set-Content -Encoding UTF8 $target}
elseif($leaf -eq 'products.txt'){1..20|Set-Content -Encoding UTF8 $target}
elseif($leaf -eq 'baseline.sha256'){'fixture snapshot hash'|Set-Content -Encoding UTF8 $target}
else{throw 'Unexpected fake SCP source'}
$global:LASTEXITCODE=0
'@|Set-Content -Encoding UTF8 $scp
"'fixture k6';`$global:LASTEXITCODE=0"|Set-Content -Encoding UTF8 $k6
# Only transports/processes are mocked. The actual public script, all loops, validation,
# evidence collection and error handling run unchanged. No socket or SSH is opened.
function Invoke-RestMethod {
 param($Uri,$Method,$ContentType,$Body,$TimeoutSec,$Headers)
 if($Uri -like '*/auth/login'){return [pscustomobject]@{data=[pscustomobject]@{accessToken='fixture-token'}}}
 $state=Get-Content -Raw -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"|ConvertFrom-Json
 return [pscustomobject]@{protocolVersion=2;owner='11111111-1111-4111-8111-111111111111';sourceHash=$identity.sourceHash;measurementHash=$identity.measurementHash;diagnostic=$false;disposable=$true;instanceId=$state.instanceId;processStartedAt=$state.processStartedAt}
}
function Start-Process {
 param($FilePath,$ArgumentList,[switch]$PassThru,$WindowStyle,$RedirectStandardOutput,$RedirectStandardError)
 $stage=$env:PERF_STAGE;$c=[int]$env:PERF_CANDIDATE;$v=$env:PERF_VARIANT
 $sample=Split-Path -Leaf (Split-Path -Parent (Split-Path -Parent $env:PERF_SUMMARY_FILE))
 @{action="k6-$stage";sample=$sample;candidate=$c;variant=$v}|ConvertTo-Json -Compress|Add-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/events.jsonl"
 $count=if($stage -eq 'warmup'){if($c -eq 3){4}elseif($c -eq 6){30}else{300}}else{if($c -eq 3){1}elseif($c -eq 6){120}else{1200}}
 $elapsed=if($stage -eq 'warmup'){30001}elseif($c -eq 3){100}else{120001}
 $drop=0;$rate=0;$exitCode=0
 if($stage -eq 'warmup'){
  switch($env:PERF_FLOW_SCENARIO){warmup{$exitCode=7}combined{$exitCode=7}drop{$drop=1}incomplete{$count--}http{$rate=0.1}short{$elapsed=1000}}
  $state=Get-Content -Raw -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"|ConvertFrom-Json;$state.warmed=$true
  if($env:PERF_FLOW_SCENARIO -eq 'restart'){$state.instanceId=[guid]::NewGuid().ToString()}
  $state|ConvertTo-Json|Set-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"
 }
 if($stage -eq 'measurement' -and $env:PERF_FLOW_SCENARIO -eq 'late-restart'){
  $state=Get-Content -Raw -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"|ConvertFrom-Json;$state.instanceId=[guid]::NewGuid().ToString();$state|ConvertTo-Json|Set-Content -Encoding UTF8 "$env:PERF_FLOW_HOME/state.json"
 }
 $summary=@{protocolVersion=2;stage=$stage;candidate=$c;variant=$v;sourceHash=$env:PERF_SOURCE_HASH;measurementHash=$env:PERF_MEASUREMENT_HASH;instanceId=$env:PERF_INSTANCE_ID;processStartedAt=$env:PERF_PROCESS_STARTED_AT;state=@{testRunDurationMs=$elapsed};metrics=@{checks=@{values=@{passes=100;fails=0}};http_req_failed=@{values=@{rate=$rate}};dropped_iterations=@{values=@{count=$drop}};phase2_completed=@{values=@{count=$count}};phase2_business_duration=@{values=@{count=$count;avg=100;'p(95)'=100}}}}
 if(!($stage -eq 'warmup' -and $env:PERF_FLOW_SCENARIO -eq 'missing')){$summary|ConvertTo-Json -Depth 9|Set-Content -Encoding UTF8 $env:PERF_SUMMARY_FILE}
 '{"type":"synthetic fixture"}'|Set-Content -Encoding UTF8 (Join-Path (Split-Path -Parent $env:PERF_SUMMARY_FILE) timeseries.json)
 'synthetic process fixture'|Set-Content -Encoding UTF8 $RedirectStandardOutput
 ''|Set-Content -Encoding UTF8 $RedirectStandardError
 $process=[pscustomobject]@{HasExited=$true;ExitCode=$exitCode}
 $process|Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {}
 $process|Add-Member -MemberType ScriptMethod -Name Refresh -Value {}
 return $process
}
function Assert([bool]$Condition,[string]$Reason){if(!$Condition){throw $Reason}}
function Run-Case([string]$Scenario,[string]$Mode,[int[]]$Candidates,[bool]$Success){
 $case=Join-Path $suite $Scenario;New-Item -ItemType Directory -Path $case|Out-Null
 $run='tool-v2-'+[guid]::NewGuid().ToString('N').Substring(0,10)
 $env:PERF_FLOW_HOME=$case;$env:PERF_FLOW_SCENARIO=$Scenario;$env:PERF_FLOW_MANIFEST=Join-Path $PSScriptRoot .manifest.json
 $errorRecord=$null
 try{& (Join-Path $PSScriptRoot run-phase2.ps1) -Phase before -RunId $run -BaseUrl http://127.0.0.1:18080 -SshTarget fixture@localhost -RemoteRoot /fixture -SshExecutable $ssh -ScpExecutable $scp -K6Executable $k6 -Mode $Mode -Candidates $Candidates *> (Join-Path $case runner.log)}catch{$errorRecord=$_}
 $folder=Join-Path $root "build/perf-phase2/$run/before"
 try{
  $execution=Get-Content -Raw -Encoding UTF8 (Join-Path $folder execution.json)|ConvertFrom-Json
  $events=@(Get-Content -Encoding UTF8 (Join-Path $case events.jsonl)|ForEach-Object{$_|ConvertFrom-Json})
  Assert (($execution.status -eq 'PASS') -eq $Success) "Unexpected execution status: $($execution.reason) / $errorRecord"
  if($Success){
   $expected=switch($Mode){All{81}Http{39}Diagnostics{42}}
   Assert ($execution.samples.Count -eq $expected) 'Incomplete execution matrix'
   Assert ($execution.allEightCandidates -eq ($Mode -eq 'All')) 'Partial mode claimed full acceptance'
   Assert (@($events|Where-Object action -eq checks).Count -eq $(if($Mode -eq 'Http'){0}else{42})) 'Wrong diagnostic count'
   $sessions=@($execution.samples|Where-Object kind -eq measurement)
   foreach($s in $sessions){
    $steps=@($events|Where-Object sample -eq $s.sample)
    Assert (@($steps|Where-Object action -eq restore).Count -eq 1) 'HTTP session restored more than once'
    Assert ((@($steps|Where-Object action -like 'k6-*'|ForEach-Object action)-join ',') -eq 'k6-warmup,k6-measurement') 'Wrong HTTP phase order'
    $a=[array]::IndexOf($steps,($steps|Where-Object action -eq 'k6-warmup'))
    $b=[array]::IndexOf($steps,($steps|Where-Object action -eq 'k6-measurement'))
    Assert (@($steps[($a+1)..($b-1)]|Where-Object action -in @('restore','checks')).Count -eq 0) 'Reset between warmup and measurement'
   }
  }else{
   Assert ($null -ne $errorRecord) 'Failure was swallowed'
   Assert (@($events|Where-Object action -eq collect).Count -gt 0) 'Failed sample was not collected'
   Assert (@($events|Where-Object action -eq copy).Count -ge 4) 'Transfer was not attempted'
   if($Scenario -notin @('late-restart','collect','copy')){Assert (@($events|Where-Object action -eq 'k6-measurement').Count -eq 0) 'Measurement started after warmup failure'}
   if($Scenario -eq 'combined'){Assert ($execution.reason -match 'k6 warmup failed') 'Original k6 error was replaced';Assert ($execution.secondaryFailures.Count -eq 2) 'Secondary errors missing'}
  }
  $results.Add(@{scenario=$Scenario;result='PASS';output=$folder})
 }catch{$results.Add(@{scenario=$Scenario;result='FAIL';reason=$_.Exception.Message;output=$folder})}
}
$saved=@{};foreach($key in @('PERF_FLOW_HOME','PERF_FLOW_SCENARIO','PERF_FLOW_MANIFEST')){$saved[$key]=[Environment]::GetEnvironmentVariable($key)}
try{
 Run-Case all All (1..8) $true
 Run-Case http-only Http (1..8) $true
 Run-Case diagnostics-only Diagnostics (1..8) $true
 foreach($name in @('warmup','drop','incomplete','http','missing','restart','late-restart','collect','copy','combined')){Run-Case $name Http @(1) $false}
 Run-Case short Http @(3) $false
 Run-Case stock Http @(6) $false
}finally{foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
$report=@{tests=$results.Count;failures=@($results|Where-Object result -eq FAIL).Count;results=$results;evidence=$suite;scope='actual runner with synthetic transports and processes; no network or measured performance'}
if($OutputPath){$report|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 $OutputPath}
$report|ConvertTo-Json -Depth 7
if($report.failures){throw 'Runner flow regression failed'}
