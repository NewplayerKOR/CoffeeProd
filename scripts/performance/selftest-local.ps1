param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop';$PSNativeCommandUseErrorActionPreference=$false
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $root
$folder=[IO.Path]::GetFullPath($OutputDirectory)
if(!$folder.StartsWith((Join-Path $root 'build/dev-task8/') , [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $folder)){throw 'Use a new output directory inside build/dev-task8'}
New-Item -ItemType Directory -Path $folder|Out-Null
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);$OutputEncoding=[Console]::OutputEncoding
function Require([string]$Reason){if($LASTEXITCODE -ne 0){throw $Reason}}
$context=docker context inspect|ConvertFrom-Json;Require 'Docker context unavailable'
if($context.Endpoints.docker.Host -notlike 'npipe:*'){throw 'This self-test only accepts local Docker Desktop; remote Docker is forbidden'}
docker info --format '{{.ServerVersion}}' > (Join-Path $folder docker.txt);Require 'Start local Docker Desktop first'
$envFile=Join-Path $PSScriptRoot .env.perf
if(Test-Path -LiteralPath $envFile){throw 'Existing performance environment preserved; use an isolated checkout for this self-test'}
$existing=@(docker ps -aq --filter label=com.docker.compose.project=coffeeprod-perf-phase2);Require 'Docker inventory failed'
if($existing.Count){throw 'Existing performance project preserved; use an isolated local Docker environment'}
$before=@(docker ps -a --format '{{.ID}} {{.Names}}')
& node scripts/performance/manifest.mjs verify > (Join-Path $folder manifest.log);Require 'Generate the current source manifest before testing'
$identity=Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot .manifest.json)|ConvertFrom-Json
$owner=[guid]::NewGuid().ToString();$RunId='tool-v2-real-'+$owner.Substring(0,8);$Phase='before';$BaseUrl='http://127.0.0.1:18080'
$password=[guid]::NewGuid().ToString('N');$secret=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
$lines=@("PERF_OWNER=$owner",'PERF_BIND_IP=127.0.0.1',"PERF_SOURCE_HASH=$($identity.sourceHash)","PERF_MEASUREMENT_HASH=$($identity.measurementHash)","PERF_DB_PASSWORD=$password","PERF_REDIS_PASSWORD=$password","PERF_JWT_SECRET=$secret",'PERF_APP_CPUS=2','PERF_APP_MEMORY=2g','PERF_DB_CPUS=2','PERF_DB_MEMORY=2g')
foreach($item in @(@('JDK','container-registry.oracle.com/java/openjdk:27.0.1.0.3@sha256:ec8568d488454d0087e88a38b818abc46098ab8701843e9449db3468a641a326'),@('POSTGRES','postgres:16-alpine'),@('REDIS','redis:7-alpine'))){$digest=(docker image inspect --format '{{index .RepoDigests 0}}' $item[1]).Trim();Require 'Required local base image unavailable';$lines+="PERF_$($item[0])_IMAGE=$digest";if($item[0] -eq 'JDK'){$jdk=$digest}}
[IO.File]::WriteAllText($envFile,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$compose=@('compose','--env-file',$envFile,'-p','coffeeprod-perf-phase2','-f',(Join-Path $PSScriptRoot compose.yml))
$recipe=Join-Path $folder runtime;New-Item -ItemType Directory -Path $recipe|Out-Null
Copy-Item -LiteralPath (Get-ChildItem -LiteralPath build/libs -Filter '*-perf.jar').FullName -Destination (Join-Path $recipe perf.jar)
@"
FROM $jdk
LABEL coffeeprod.perf.source=$($identity.sourceHash)
ENV PERF_COMPILED_SOURCE_HASH=$($identity.sourceHash)
RUN groupadd --system perf && useradd --system --gid perf --no-create-home --shell /sbin/nologin perf
WORKDIR /app
COPY perf.jar /app/perf.jar
USER perf
HEALTHCHECK --interval=3s --timeout=3s --start-period=90s --retries=40 CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/v1/categories || exit 1
ENTRYPOINT ["java","-jar","/app/perf.jar"]
"@|Set-Content -Encoding UTF8 (Join-Path $recipe Dockerfile)
$bash='C:/Program Files/Git/bin/bash.exe'
$RemoteRoot=$root.Replace('\','/');$RemoteRoot=$RemoteRoot -replace '^([A-Za-z]):',('/'+$RemoteRoot.Substring(0,1).ToLowerInvariant())
$SshTarget='local-adapter@localhost';$K6Executable='k6';$destination=Join-Path $folder before
New-Item -ItemType Directory -Path $destination|Out-Null
$SshExecutable=Join-Path $folder local-ssh.ps1;$ScpExecutable=Join-Path $folder local-scp.ps1
@'
# Local process adapter: no SSH command or socket is used.
& 'C:/Program Files/Git/bin/bash.exe' -c ([string]$args[-1])
$code=$LASTEXITCODE;$global:LASTEXITCODE=$code
'@|Set-Content -Encoding UTF8 $SshExecutable
@'
$ErrorActionPreference='Stop'
$source=([string]$args[-2])-replace '^[^:]+:',''
if($source -notmatch '^/[a-zA-Z]/'){throw 'Only the local checkout is supported'}
$source=$source.Substring(1,1)+':'+$source.Substring(2)
$actual=[IO.Path]::GetFullPath($source)
$allowed=[IO.Path]::GetFullPath((Join-Path $env:PERF_SELFTEST_ROOT 'build/perf-phase2/'))
if(!$actual.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Self-test copy outside result directory refused'}
Copy-Item -LiteralPath $actual -Destination ([string]$args[-1]) -Recurse
$global:LASTEXITCODE=0
'@|Set-Content -Encoding UTF8 $ScpExecutable
. (Join-Path $PSScriptRoot runner-functions.ps1)
$secondaryFailures=[Collections.Generic.List[object]]::new()
$saved=@{};$keys=@('MSYS2_ARG_CONV_EXCL','MSYS_NO_PATHCONV','PERF_SELFTEST_ROOT','PERF_BASE_URL','PERF_RUN_ID','PERF_CANDIDATE','PERF_VARIANT','PERF_STAGE','PERF_INSTANCE_ID','PERF_PROCESS_STARTED_AT','PERF_SOURCE_HASH','PERF_MEASUREMENT_HASH','PERF_OWNER','PERF_USERS_FILE','PERF_PRODUCTS_FILE','PERF_SUMMARY_FILE','K6_HTTP_DEBUG')
foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key)}
$env:MSYS2_ARG_CONV_EXCL='/app/perf.jar';$env:MSYS_NO_PATHCONV=$null;$env:PERF_SELFTEST_ROOT=$root
$runs=[Collections.Generic.List[object]]::new();$passed=$false
try{
 docker build --pull=false --tag "coffeeprod-phase2:$($identity.sourceHash)" $recipe *> (Join-Path $folder image-build.log);Require 'Local runtime image build failed'
 docker @compose up -d --no-build --pull never --wait *> (Join-Path $folder up.log);Require 'Dedicated local Compose startup failed'
 & $bash -c "bash scripts/performance/phase2.sh prepare --run-id $RunId" *> (Join-Path $folder prepare.log);Require 'Actual phase2 prepare failed'
 $server=Join-Path $root "build/perf-phase2/$RunId"
 foreach($name in @('users.json','products.txt','baseline.sha256')){Copy-Item -LiteralPath (Join-Path $server $name) -Destination (Join-Path $destination $name)}
 $env:PERF_BASE_URL=$BaseUrl;$env:PERF_RUN_ID=$RunId;$env:PERF_SOURCE_HASH=$identity.sourceHash;$env:PERF_MEASUREMENT_HASH=$identity.measurementHash;$env:PERF_OWNER=$owner
 $env:PERF_USERS_FILE=Join-Path $destination users.json;$env:PERF_PRODUCTS_FILE=Join-Path $destination products.txt;$env:K6_HTTP_DEBUG=''
 try{Invoke-WebRequest "$BaseUrl/api/v1/admin/performance/manifest" -TimeoutSec 10|Out-Null;throw 'Unauthenticated manifest was accepted'}catch{if($_.Exception.Response.StatusCode.value__ -ne 401){throw}}
 foreach($item in @(@{candidate=1;variant='catalog'},@{candidate=6;variant='order5'},@{candidate=3;variant='days30'})){
  $c=$item.candidate;$v=$item.variant;$sample="http-c$c-$v-r1"
  Invoke-HttpSession $sample $c $v (Join-Path $destination $sample) *> (Join-Path $folder "$sample.log")
  $runs.Add(@{candidate=$c;variant=$v;result='PASS';session=Join-Path $destination $sample})
  $runs|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 (Join-Path $folder runs.json)
 }
 $passed=$true
}finally{
 $ids=@(docker @compose ps -aq)
 foreach($id in $ids){if($id){$actual=(docker inspect --format '{{index .Config.Labels "coffeeprod.perf.owner"}}' $id).Trim();Require 'Container inspection failed';if($actual -ne $owner){throw 'Owner mismatch; resources preserved'}}}
 $volume='coffeeprod-perf-phase2_pgdata';$actual=docker volume inspect --format '{{index .Labels "coffeeprod.perf.owner"}}' $volume 2>$null
 if($LASTEXITCODE -eq 0 -and $actual.Trim() -ne $owner){throw 'Volume owner mismatch; resources preserved'}
 docker @compose down --volumes *> (Join-Path $folder cleanup.log);Require 'Owned local project cleanup failed'
 if((Get-Content -Raw -Encoding UTF8 -LiteralPath $envFile) -notmatch "(?m)^PERF_OWNER=$owner$"){throw 'Environment owner changed; file preserved'}
 Remove-Item -LiteralPath $envFile
 foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
 @{passed=$passed;owner=$owner;existingBefore=$before;remaining=@(docker ps -a --format '{{.ID}} {{.Names}}');dedicatedRemoved=$true;runs=$runs;sourceHash=$identity.sourceHash;measurementHash=$identity.measurementHash;scope='local Docker tooling self-test; not home-server baseline';jarSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $recipe perf.jar)).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 (Join-Path $folder summary.json)
}
if(!$passed){throw 'Local self-test did not complete'}
Write-Output "Local real warmup/measurement self-test PASS: $folder"
