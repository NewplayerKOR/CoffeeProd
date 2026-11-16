param([ValidateSet('Prepare','ResetFixture','Compare','CompareCover','Final','Functional','VerifyMigration','Cleanup')][string]$Mode='Compare',[string]$Results)
$ErrorActionPreference='Stop'
$OutputEncoding=[System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
if(-not $Results){$Results=(Get-Content -Raw -Encoding UTF8 build/dev-task9/indexes-current-run.txt).Trim()}
$repo=(Resolve-Path .).Path
$destination=[IO.Path]::GetFullPath((Join-Path $repo $Results))
if(-not $destination.StartsWith((Join-Path $repo 'build/dev-task9')+[IO.Path]::DirectorySeparatorChar)){throw 'Evidence must stay under build/dev-task9'}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$settingsFile=Join-Path $destination 'postgres-settings.json'
if([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))){throw 'JAVA_HOME에 JDK 27.0.1.0.3 경로를 지정한다.'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
function CheckExit {if($LASTEXITCODE -ne 0){throw "Command failed: $LASTEXITCODE"}}
function Probe([string]$Action){
    & java -cp (Get-Content -Raw -Encoding UTF8 (Join-Path $destination 'classpath.txt')).Trim() com.back.coffeeprod.dev.indexes.DevTask9IndexProbe $Action *> (Join-Path $destination ($Action+'.log'))
    CheckExit
    Get-Content -Encoding UTF8 (Join-Path $destination ($Action+'.log')) -Tail 12
}
function CompileProbe {
    $init=Join-Path $destination 'classpath.gradle'
    @'
allprojects {
    tasks.register('dev9IndexClasspath') {
        dependsOn testClasses
        doLast { file(System.getenv('DEV_TASK9_8_RESULTS') + '/classpath.txt').text = sourceSets.test.runtimeClasspath.asPath }
    }
}
'@ | Set-Content -Encoding UTF8 $init
    & ./gradlew.bat --offline --no-daemon -I $init dev9IndexClasspath --console=plain *> (Join-Path $destination 'compile.log')
    CheckExit
}
if($Mode -eq 'Prepare'){
    if(Test-Path -LiteralPath $settingsFile){throw 'Existing run preserved; choose a new Results directory'}
    $original=@(docker ps -aq --no-trunc);CheckExit
    $owner=[guid]::NewGuid().ToString();$password=[guid]::NewGuid().ToString('N')
    $image=(docker image inspect postgres:16-alpine --format '{{.Id}}').Trim();CheckExit
    $id=(docker run -d --pull never --label "coffeeprod.dev9.owner=$owner" --name "dev9-indexes-$($owner.Substring(0,8))" --tmpfs /var/lib/postgresql/data:rw --publish 127.0.0.1::5432 -e POSTGRES_USER=devtask9 -e "POSTGRES_PASSWORD=$password" -e POSTGRES_DB=devtask9_8_before $image).Trim();CheckExit
    $port=[int](docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' $id);CheckExit
    $settings=@{id=$id;owner=$owner;password=$password;port=$port;image=$image;originalContainers=$original}
    $settings|ConvertTo-Json -Depth 4|Set-Content -Encoding UTF8 $settingsFile
    for($i=0;$i -lt 30;$i++){docker exec $id pg_isready -U devtask9 -d devtask9_8_before *> $null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Milliseconds 500}
    CheckExit
}
$settings=Get-Content -Raw -Encoding UTF8 $settingsFile|ConvertFrom-Json
$actual=(docker inspect --format '{{index .Config.Labels "coffeeprod.dev9.owner"}}' $settings.id).Trim();CheckExit
if($actual -ne $settings.owner){throw 'Owner mismatch; resource preserved'}
$env:DEV_TASK9_8_OWNER=$settings.owner
$env:DEV_TASK9_8_PG_PREFIX="jdbc:postgresql://127.0.0.1:$($settings.port)/"
$env:DEV_TASK9_8_PG_PASSWORD=$settings.password
$env:DEV_TASK9_8_RESULTS=$destination
if($Mode -eq 'Cleanup'){
    docker rm --force $settings.id|Out-Null;CheckExit
    $remaining=@(docker ps -aq --no-trunc);CheckExit
    if(@($settings.originalContainers|Where-Object{$_ -notin $remaining}).Count){throw 'An original container is missing'}
    @{ownedContainerRemoved=$true;originalContainersPreserved=$true}|ConvertTo-Json|Set-Content -Encoding UTF8 (Join-Path $destination 'cleanup.json')
    exit
}
CompileProbe
if($Mode -eq 'Prepare' -or $Mode -eq 'ResetFixture'){
    if($Mode -eq 'ResetFixture'){
        foreach($db in @('devtask9_8_candidate','devtask9_8_before')){
            docker exec $settings.id dropdb -U devtask9 $db;CheckExit
        }
        docker exec $settings.id createdb -U devtask9 devtask9_8_before;CheckExit
    }
    Probe 'migrate18'
    docker exec $settings.id mkdir -p /seed/catalog /seed/commerce;CheckExit
    docker cp scripts/seed/catalog/csv/. "$($settings.id):/seed/catalog";CheckExit
    docker cp scripts/seed/catalog/sql/. "$($settings.id):/seed/catalog";CheckExit
    docker cp scripts/seed/commerce/sql/. "$($settings.id):/seed/commerce";CheckExit
    docker exec $settings.id psql -U devtask9 -d devtask9_8_before -f /seed/catalog/load_catalog.sql *> (Join-Path $destination 'seed-catalog.log');CheckExit
    docker exec $settings.id psql -U devtask9 -d devtask9_8_before -v member_count=10000 -v order_count=150000 -v review_count=30000 -v qna_count=20000 -v history_days=730 -v k6_user_count=200 -v seed_value=20260722 -v seed_end_date=2026-06-30 -f /seed/commerce/seed_commerce.sql *> (Join-Path $destination 'seed-commerce.log');CheckExit
    docker exec $settings.id psql -U devtask9 -d devtask9_8_before -f /seed/catalog/verify_catalog.sql *> (Join-Path $destination 'verify-catalog.log');CheckExit
    docker exec $settings.id psql -U devtask9 -d devtask9_8_before -v member_count=10000 -v order_count=150000 -v review_count=30000 -v qna_count=20000 -v history_days=730 -v k6_user_count=200 -v seed_value=20260722 -v seed_end_date=2026-06-30 -f /seed/commerce/verify_commerce.sql *> (Join-Path $destination 'verify-commerce.log');CheckExit
    docker exec $settings.id psql -U devtask9 -d devtask9_8_before -c 'VACUUM ANALYZE' *> (Join-Path $destination 'vacuum.log');CheckExit
    docker exec $settings.id createdb -U devtask9 -T devtask9_8_before devtask9_8_candidate;CheckExit
    if($Mode -eq 'Prepare'){docker exec $settings.id createdb -U devtask9 devtask9_8_functional;CheckExit}
    Write-Output 'Dedicated fixture ready: 192 products, 150000 orders, 375000 items, 30000 reviews, 20000 QnA'
}elseif($Mode -eq 'Compare'){Probe 'compare'}
elseif($Mode -eq 'CompareCover'){Probe 'compare-cover'}
elseif($Mode -eq 'Final'){Probe 'final'}
elseif($Mode -eq 'Functional'){Probe 'migrate-final'}
elseif($Mode -eq 'VerifyMigration'){
    & java -cp (Get-Content -Raw -Encoding UTF8 (Join-Path $destination 'classpath.txt')).Trim() com.back.coffeeprod.dev.indexes.DevTask9IndexMigrationProbe *> (Join-Path $destination 'migration-validation.log')
    CheckExit
    Get-Content -Encoding UTF8 (Join-Path $destination 'migration-validation.log') -Tail 5
}
