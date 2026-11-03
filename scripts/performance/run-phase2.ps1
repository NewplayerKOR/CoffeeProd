param(
    [Parameter(Mandatory)][ValidateSet('before','after')][string]$Phase,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')][string]$RunId,
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_.@-]+$')][string]$SshTarget,
    [Parameter(Mandatory)][ValidatePattern('^/[a-zA-Z0-9_./-]+$')][string]$RemoteRoot,
    [string]$SshExecutable='ssh',
    [string]$ScpExecutable='scp',
    [string]$K6Executable='k6',
    [ValidateSet('All','Diagnostics','Http')][string]$Mode='All',
    [ValidateNotNullOrEmpty()][ValidateRange(1,8)][int[]]$Candidates=1..8
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($RemoteRoot.Split('/') -contains '..') { throw 'Parent path segments are forbidden' }
Set-Location -LiteralPath $root
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Console]::OutputEncoding
$targetUri=[uri]$BaseUrl
$octets=$targetUri.Host.Split('.')
if ($BaseUrl -notmatch '^http://[0-9.]+:18080$' -or $octets.Count -ne 4 -or @($octets|Where-Object{[int]$_ -gt 255}).Count) { throw 'Use dedicated LAN port 18080' }
$private=($octets[0] -eq '10') -or ($octets[0] -eq '192' -and $octets[1] -eq '168') -or ($octets[0] -eq '172' -and [int]$octets[1] -ge 16 -and [int]$octets[1] -le 31) -or $targetUri.Host -eq '127.0.0.1'
if (!$private) { throw 'Public/operational targets are forbidden' }
& node scripts/performance/manifest.mjs verify
if ($LASTEXITCODE -ne 0) { throw 'Generate a current source bundle first' }
$identity=Get-Content -Raw -Encoding UTF8 scripts/performance/.manifest.json | ConvertFrom-Json
$destination=Join-Path $root "build/perf-phase2/$RunId/$Phase"
if (Test-Path -LiteralPath $destination) { throw 'Local phase results already exist; preserve them and choose a new RunId' }
New-Item -ItemType Directory -Path $destination | Out-Null
$results=[Collections.Generic.List[object]]::new()
$secondaryFailures=[Collections.Generic.List[object]]::new()
. (Join-Path $PSScriptRoot runner-functions.ps1)
$environment=@{}
foreach($key in @('PERF_BASE_URL','PERF_RUN_ID','PERF_CANDIDATE','PERF_VARIANT','PERF_DURATION','PERF_STAGE','PERF_INSTANCE_ID','PERF_PROCESS_STARTED_AT','PERF_SOURCE_HASH','PERF_MEASUREMENT_HASH','PERF_OWNER','PERF_USERS_FILE','PERF_PRODUCTS_FILE','PERF_SUMMARY_FILE','K6_HTTP_DEBUG')) { $environment[$key]=[Environment]::GetEnvironmentVariable($key) }
try {
    $remote=Invoke-Remote 'bash scripts/performance/phase2.sh manifest' 'identity' | ConvertFrom-Json
    if ($identity.sourceHash -ne $remote.sourceHash -or $identity.measurementHash -ne $remote.measurementHash) { throw 'Local and remote source/measurement identity differ' }
    $baseline=Invoke-Remote "cat build/perf-phase2/$RunId/baseline-manifest.json" 'baseline-identity' | ConvertFrom-Json
    if ($baseline.measurementHash -ne $identity.measurementHash -or $baseline.fixtureHash -ne $identity.fixtureHash) { throw 'Frozen measurement or fixture changed; collect a new before baseline' }
    Copy-Result "$RemoteRoot/build/perf-phase2/$RunId/users.json" (Join-Path $destination users.json)
    Copy-Result "$RemoteRoot/build/perf-phase2/$RunId/products.txt" (Join-Path $destination products.txt)
    Copy-Result "$RemoteRoot/build/perf-phase2/$RunId/baseline.sha256" (Join-Path $destination baseline.sha256)
    $owner=Invoke-Remote 'bash scripts/performance/phase2.sh owner' 'owner'
    if ($owner -notmatch '^[a-f0-9-]{36}$') { throw 'Missing disposable owner identity' }
    $env:PERF_BASE_URL=$BaseUrl;$env:PERF_RUN_ID=$RunId;$env:PERF_SOURCE_HASH=$identity.sourceHash;$env:PERF_MEASUREMENT_HASH=$identity.measurementHash;$env:PERF_OWNER=$owner
    $env:K6_HTTP_DEBUG=''
    $env:PERF_USERS_FILE=Join-Path $destination users.json;$env:PERF_PRODUCTS_FILE=Join-Path $destination products.txt
    $matrix=& node --input-type=module -e "import {matrix} from './k6/phase2/protocol.mjs';process.stdout.write(JSON.stringify(matrix));" | ConvertFrom-Json
    $conditions=[ordered]@{protocolVersion=2;mode=$Mode;phase=$Phase;runId=$RunId;baseUrl=$BaseUrl;owner=$owner;sourceHash=$identity.sourceHash;measurementHash=$identity.measurementHash;fixtureHash=$identity.fixtureHash;warmupSeconds=30;warmupPolicy='restore, warmup, measurement; same app instance per repetition';warmupWriteOperations=30;ratePerSecond=10;durationSeconds=120;repetitions=3;writeRatePerSecond=1;statistics='30 second serial warmup; single measured operation per repetition';scheduler='45 second lookup delay, three recovery operations';startedKst=[DateTimeOffset]::Now.ToOffset([TimeSpan]::FromHours(9)).ToString('o');k6=(& $K6Executable version);localNode=(& node --version)}
    $conditions | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $destination conditions.json)
    $conditions.localMachine=[Environment]::MachineName
    $conditions.localProcessors=[Environment]::ProcessorCount
    $conditions.localOS=[System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    $conditions | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $destination conditions.json)
    $selected=@($matrix | Where-Object candidate -in $Candidates)
    $expectedCount=0
    foreach($item in $selected){if($Mode -ne 'Http'){$expectedCount+=3};if($Mode -ne 'Diagnostics' -and $item.candidate -ne 4){$expectedCount+=3}}
    if(!$expectedCount){throw 'No samples selected for this mode'}
    foreach($item in $selected) {
        $c=[int]$item.candidate;$v=[string]$item.variant
        # Three server-side diagnostics, restored independently. SQL stats/JWT spies are never in timing runs.
        if($Mode -ne 'Http'){ foreach($repeat in 1..3) {
            $sample="diag-c$c-$v-r$repeat"
            $sampleFailure=$null
            try { Step restore $sample $c $v; Step checks $sample $c $v } catch { $sampleFailure=$_ }
            Save-SampleEvidence $sample $c $v $destination ([ref]$sampleFailure)
            if ($sampleFailure) { throw $sampleFailure }
            $results.Add(@{sample=$sample;kind='diagnostic';candidate=$c;variant=$v;result='PASS'})
        }}
        if ($c -eq 4 -or $Mode -eq 'Diagnostics') { continue }
        foreach($repeat in 1..3) {
            $sample="http-c$c-$v-r$repeat";$local=Join-Path $destination $sample
            Invoke-HttpSession $sample $c $v $local
            $results.Add(@{sample=$sample;kind='measurement';candidate=$c;variant=$v;result='PASS'})
        }
    }
    if($results.Count -ne $expectedCount){throw 'Execution matrix is incomplete'}
    @{protocolVersion=2;mode=$Mode;status='PASS';scope='requested mode and candidates only';allEightCandidates=($Mode -eq 'All' -and @($Candidates|Select-Object -Unique).Count -eq 8 -and $results.Count -eq 81);candidates=$Candidates;samples=$results;sourceHash=$identity.sourceHash;measurementHash=$identity.measurementHash}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 (Join-Path $destination execution.json)
} catch {
    @{protocolVersion=2;mode=$Mode;status='FAILED';reason=$_.Exception.Message;completed=$results;secondaryFailures=$secondaryFailures}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 (Join-Path $destination execution.json)
    throw
} finally {
    foreach($key in $environment.Keys){[Environment]::SetEnvironmentVariable($key,$environment[$key],'Process')}
}
