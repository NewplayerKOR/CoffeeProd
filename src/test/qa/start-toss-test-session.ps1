param([switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $repository
$names = @('JAVA_HOME','JAVA_TOOL_OPTIONS','QA_TOSS_TEST_CLIENT_KEY','QA_TOSS_TEST_SECRET_KEY','QA_TASK6_ALLOW_TOSS_TEST','QA_TASK6_TOSS_PG_URL','QA_TASK6_PG_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$containerId = $null
$containerName = 'coffeeprod-qa6-toss-' + (Get-Date -Format 'yyyyMMddHHmmss')
try {
    $env:JAVA_HOME = if ($previous.JAVA_HOME) {
        $previous.JAVA_HOME
    } else {
        Join-Path ([Environment]::GetFolderPath('UserProfile')) '.jdks/graalvm-ce-21.0.2'
    }
    if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        throw 'Set JAVA_HOME to a Java 21 installation before starting the QA session.'
    }
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
    $output = Join-Path $repository 'build/qa-task6/toss-test'
    New-Item -ItemType Directory -Force $output | Out-Null
    $init = Join-Path $output 'classpath.init.gradle'
    @'
allprojects {
    afterEvaluate {
        if (plugins.hasPlugin('java')) {
            tasks.register('qaTask6Classpath') {
                doLast {
                    def target = file('build/qa-task6/toss-test/runtime-classpath.txt')
                    target.parentFile.mkdirs()
                    target.setText(sourceSets.test.runtimeClasspath.asPath, 'UTF-8')
                }
            }
        }
    }
}
'@ | Set-Content -Encoding utf8 $init
    & .\gradlew.bat --offline --no-daemon -I $init compileTestJava qaTask6Classpath --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Test helper compilation failed' }
    if ($CompileOnly) { return }
    $values = @{}
    if (Test-Path -LiteralPath '.env') {
        foreach ($line in Get-Content -LiteralPath '.env' -Encoding UTF8) {
            if ($line -match '^\s*(TOSS_CLIENT_KEY|TOSS_SECRET_KEY)\s*=\s*(.*?)\s*$') {
                $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
            }
        }
    }
    $env:QA_TOSS_TEST_CLIENT_KEY = if ($previous.QA_TOSS_TEST_CLIENT_KEY) { $previous.QA_TOSS_TEST_CLIENT_KEY } else { $values.TOSS_CLIENT_KEY }
    $env:QA_TOSS_TEST_SECRET_KEY = if ($previous.QA_TOSS_TEST_SECRET_KEY) { $previous.QA_TOSS_TEST_SECRET_KEY } else { $values.TOSS_SECRET_KEY }
    if ($env:QA_TOSS_TEST_CLIENT_KEY -notlike 'test_ck_*' -or $env:QA_TOSS_TEST_SECRET_KEY -notlike 'test_sk_*') {
        throw 'Payment-window test_ck/test_sk pair required. Key values are not printed.'
    }
    $env:QA_TASK6_PG_PASSWORD = 'qa6-toss-' + [guid]::NewGuid().ToString('N')
    $env:QA_TASK6_TOSS_PG_URL = 'jdbc:postgresql://127.0.0.1:55487/qa6_toss'
    $env:QA_TASK6_ALLOW_TOSS_TEST = 'true'
    $containerId = & docker run -d --name $containerName --label coffeeprod.qa.task=6-toss -e POSTGRES_USER=qa6 -e "POSTGRES_PASSWORD=$env:QA_TASK6_PG_PASSWORD" -e POSTGRES_DB=qa6_toss -p 127.0.0.1:55487:5432 postgres:16-alpine
    if ($LASTEXITCODE -ne 0) { $containerId = $null; throw 'Dedicated QA container creation failed' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        & docker exec $containerName pg_isready -U qa6 -d qa6_toss *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$ready) { throw 'Dedicated QA database readiness timeout' }
    $classpath = Get-Content -Raw -Encoding UTF8 (Join-Path $output 'runtime-classpath.txt')
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $classpath com.back.coffeeprod.domain.payment.service.QaTossTestSession
    if ($LASTEXITCODE -ne 0) { throw 'Toss test session failed; credentials and provider bodies remain private' }
}
finally {
    if ($containerId) {
        $actualId = & docker inspect --format '{{.Id}}' $containerName 2>$null
        if ($LASTEXITCODE -eq 0 -and $actualId -eq $containerId.Trim()) { & docker rm -f $containerName | Out-Null }
    }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
