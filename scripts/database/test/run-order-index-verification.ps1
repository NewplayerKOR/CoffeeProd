param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
Set-Location -LiteralPath $repo
$output = [IO.Path]::GetFullPath($OutputDirectory)
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
if (!$output.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) {
    throw 'OutputDirectory must be a new directory below this repository build directory'
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome must point to Java 21' }
New-Item -ItemType Directory -Path $output | Out-Null
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome/bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
$owner = [guid]::NewGuid().ToString()
$name = 'coffeeprod-dev2-' + (Get-Date -Format 'yyyyMMddHHmmss')
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$id = $null
$originalEnvironment = @{}
foreach ($key in @('DEV_TASK2_PG_URL','DEV_TASK2_PG_PASSWORD','DEV_TASK2_OWNER','DEV_TASK2_CLASSPATH_FILE')) {
    $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$beforeContainers = @(docker ps --format '{{.ID}} {{.Names}}')
if ($LASTEXITCODE -ne 0) { throw 'Docker is not available' }
$started = Get-Date
$sourceFiles = @(@(git -c core.quotepath=false ls-files) + @(git -c core.quotepath=false ls-files --others --exclude-standard))
@($sourceFiles | ForEach-Object { [pscustomobject]@{path=$_;sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash} }) |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $output 'source-hashes.json')

function Invoke-Probe([string]$Mode, [string]$Label) {
    $log = Join-Path $output "$Label.log"
    # A Java argument file preserves Windows classpath quoting and command-length limits.
    $arguments = Join-Path $output 'probe.args'
    $cp = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $output 'classpath.txt')).Trim()
    @('-cp', ('"' + $cp.Replace('\','/') + '"'), 'com.back.coffeeprod.dev.order.DevTask2PostgresProbe', $Mode) |
        Set-Content -Encoding UTF8 -LiteralPath $arguments
    & "$JavaHome/bin/java.exe" "@$arguments" *> $log
    if ($LASTEXITCODE -ne 0) { throw "DEV2 probe failed; see $log" }
    $line = @(Get-Content -Encoding UTF8 -LiteralPath $log | Where-Object { $_.StartsWith('DEV2_SNAPSHOT ') })
    if ($line.Count -ne 1) { throw "Missing snapshot in $log" }
    $snapshot = $line[0].Substring('DEV2_SNAPSHOT '.Length)
    $snapshot | Set-Content -Encoding UTF8 (Join-Path $output "$Label.json")
}
function Invoke-Sql([string]$File, [string]$Label) {
    $log = Join-Path $output "$Label.log"
    Get-Content -Raw -Encoding UTF8 -LiteralPath $File |
        & docker exec -i --env "PGPASSWORD=$password" $id psql -X -v ON_ERROR_STOP=1 -U devtask2 -d devtask2_verification *> $log
    if ($LASTEXITCODE -ne 0) { throw "DEV2 SQL check failed; see $log" }
}
function Wait-Postgres([int]$Port) {
    for ($attempt=0; $attempt -lt 100; $attempt++) {
        docker exec $id pg_isready -h 127.0.0.1 -U devtask2 -d devtask2_verification *> $null
        if ($LASTEXITCODE -eq 0) {
            $client = [Net.Sockets.TcpClient]::new()
            try {
                $connected = $client.ConnectAsync('127.0.0.1', $Port).Wait(500)
                if ($connected -and $client.Connected) { return }
            } catch { } finally { $client.Dispose() }
        }
        Start-Sleep -Milliseconds 200
    }
    throw 'Dedicated PostgreSQL host TCP endpoint did not become ready'
}
function Invoke-Regression([string]$Label) {
    $destination = Join-Path $output $Label
    New-Item -ItemType Directory -Path $destination | Out-Null
    $runStart = Get-Date
    & ./gradlew.bat --offline --no-daemon test --rerun-tasks --console=plain --tests '*DevTask2OrderQueryIntegrationTest' *> (Join-Path $destination 'console.log')
    $result = $LASTEXITCODE
    $fresh = @(Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml' | Where-Object LastWriteTime -ge $runStart)
    if ($fresh.Count -ne 1) { throw 'Expected one fresh DEV2 test suite' }
    Copy-Item -LiteralPath 'build/test-results/test' -Destination (Join-Path $destination 'xml') -Recurse
    Copy-Item -LiteralPath 'build/reports/tests/test' -Destination (Join-Path $destination 'html') -Recurse
    [xml]$xml = Get-Content -Raw -Encoding UTF8 -LiteralPath $fresh[0].FullName
    $cases = @($xml.testsuite.testcase | ForEach-Object { [pscustomobject]@{id="$($_.classname)#$($_.name)";result=$(if($_.failure){'failure'}elseif($_.error){'error'}elseif($null -ne $_.skipped){'skipped'}else{'pass'})} })
    $cases | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $destination 'cases.json')
    $runtime = [regex]::Match($xml.SelectSingleNode('/testsuite/system-out').InnerText, 'DEV2_RUNTIME pid=(\d+) database=PostgreSQL')
    if (!$runtime.Success) { throw 'PostgreSQL application process marker missing' }
    $run = [pscustomobject]@{startedKst=$runStart.ToString('yyyy-MM-dd HH:mm:ss');endedKst=(Get-Date -Format 'yyyy-MM-dd HH:mm:ss');exitCode=$result;total=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped;processId=$runtime.Groups[1].Value;command='./gradlew.bat --offline --no-daemon test --rerun-tasks --console=plain --tests *DevTask2OrderQueryIntegrationTest'}
    $run | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $destination 'run.json')
    if ($result -ne 0 -or $run.total -ne 10 -or $run.failures -ne 0 -or $run.errors -ne 0 -or $run.skipped -ne 0) { throw "DEV2 regression failed; see $destination" }
    return $run
}
try {
    $id = (docker run -d --name $name --label "coffeeprod.dev2.owner=$owner" --publish '127.0.0.1::5432' `
        --env POSTGRES_USER=devtask2 --env "POSTGRES_PASSWORD=$password" --env POSTGRES_DB=devtask2_verification postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or !$id) { throw 'Failed to start dedicated PostgreSQL' }
    $mapping = (docker port $id 5432/tcp).Trim()
    if ($mapping -notmatch '^127\.0\.0\.1:(\d+)$' -or $Matches[1] -eq '5432') { throw 'PostgreSQL must use a dedicated localhost port' }
    $hostPort = [int]$Matches[1]
    $env:DEV_TASK2_PG_URL = "jdbc:postgresql://$mapping/devtask2_verification"
    $env:DEV_TASK2_PG_PASSWORD = $password
    $env:DEV_TASK2_OWNER = $owner
    Wait-Postgres $hostPort
    $version = (docker exec $id postgres --version).Trim()
    [pscustomobject]@{id=$id;owner=$owner;name=$name;mapping=$mapping;version=$version;previousContainers=$beforeContainers} |
        ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $output 'container.json')
    $env:DEV_TASK2_CLASSPATH_FILE = Join-Path $output 'classpath.txt'
    $init = Join-Path $output 'classpath.init.gradle'
    @'
allprojects {
    afterEvaluate {
        tasks.register('devTask2WriteTestClasspath') {
            dependsOn testClasses
            doLast { file(System.getenv('DEV_TASK2_CLASSPATH_FILE')).text = sourceSets.test.runtimeClasspath.asPath }
        }
    }
}
'@ | Set-Content -Encoding UTF8 -LiteralPath $init
    & ./gradlew.bat --offline --no-daemon devTask2WriteTestClasspath --init-script $init --console=plain *> (Join-Path $output 'classpath.log')
    if ($LASTEXITCODE -ne 0) { throw 'Could not build Java probe classpath' }
    Invoke-Probe migrate16 'flyway-v16'
    Invoke-Sql 'scripts/database/precheck/order_query_index_precheck.sql' 'precheck-v16'
    Invoke-Probe migrate18 'flyway-v18'
    Invoke-Sql 'scripts/database/verify-order-query-indexes.sql' 'verify-v18'
    $firstRun = Invoke-Regression 'before-restart'
    Invoke-Probe snapshot 'before-restart-state'
    docker restart $id *> (Join-Path $output 'restart.log')
    if ($LASTEXITCODE -ne 0) { throw 'Restart failed' }
    $restartMapping = (docker port $id 5432/tcp).Trim()
    if ($restartMapping -notmatch '^127\.0\.0\.1:(\d+)$' -or $Matches[1] -eq '5432') { throw 'Restarted PostgreSQL lost its isolated binding' }
    $env:DEV_TASK2_PG_URL = "jdbc:postgresql://$restartMapping/devtask2_verification"
    Wait-Postgres ([int]$Matches[1])
    [pscustomobject]@{before=$mapping;after=$restartMapping;containerId=$id} | ConvertTo-Json |
        Set-Content -Encoding UTF8 (Join-Path $output 'restart-mapping.json')
    Invoke-Probe snapshot 'after-restart-state'
    if ((Get-Content -Raw -LiteralPath (Join-Path $output 'before-restart-state.json')) -ne (Get-Content -Raw -LiteralPath (Join-Path $output 'after-restart-state.json'))) { throw 'Data, indexes or Flyway history changed across restart' }
    Invoke-Probe migrate18 'flyway-restart'
    Invoke-Sql 'scripts/database/verify-order-query-indexes.sql' 'verify-restart'
    $secondRun = Invoke-Regression 'after-restart'
    if ($firstRun.processId -eq $secondRun.processId) { throw 'Expected a new application process' }
    [pscustomobject]@{startedKst=$started.ToString('yyyy-MM-dd HH:mm:ss');endedKst=(Get-Date -Format 'yyyy-MM-dd HH:mm:ss');firstRun=$firstRun;secondRun=$secondRun;snapshotUnchanged=$true;result='PASS'} |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $output 'summary.json')
    Write-Output 'DEV2 PostgreSQL: real V1-V18, verify, restart persistence, HTTP10 x2 PASS'
} finally {
    foreach ($key in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$originalEnvironment[$key],'Process') }
    if ($id) {
        $identity = (docker inspect --format '{{.Id}}|{{index .Config.Labels "coffeeprod.dev2.owner"}}' $id).Trim()
        if ($LASTEXITCODE -ne 0 -or $identity -ne "$id|$owner") { throw 'Container identity mismatch; preserved without cleanup' }
        docker rm -f $id *> (Join-Path $output 'cleanup.log')
        $removed = $LASTEXITCODE -eq 0
        [pscustomobject]@{id=$id;ownerMatched=$true;removed=$removed;remainingContainers=@(docker ps --format '{{.ID}} {{.Names}}');endedKst=(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')} |
            ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $output 'cleanup.json')
        if (!$removed) { throw 'Dedicated PostgreSQL cleanup failed' }
    }
    $password=$null
}
