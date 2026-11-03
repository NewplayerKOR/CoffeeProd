function Invoke-Remote([string]$Command,[string]$Label) {
    $args=@('-o','BatchMode=yes','-o','ConnectTimeout=10',$SshTarget,"cd '$RemoteRoot' && $Command")
    $lines=& $SshExecutable @args 2>&1
    $code=$LASTEXITCODE
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $destination "$Label.ssh.log") -Value ($lines -join "`n")
    if ($code -ne 0) {
        $log=Join-Path $destination "$Label.ssh.log"
        $tail=($lines | Select-Object -Last 12) -join "`n"
        if ($code -eq 255) { throw "SSH transport/session failed for $Label (exit 255). Log: $log`n$tail" }
        throw "Remote step $Label failed (exit $code). Log: $log`n$tail"
    }
    return ($lines -join "`n")
}
function Step([string]$Command,[string]$Sample,[int]$Candidate,[string]$Variant) {
    $line="bash scripts/performance/phase2.sh $Command --run-id $RunId --phase $Phase --candidate $Candidate --variant $Variant --sample $Sample"
    Invoke-Remote $line "$Sample-$Command" | Out-Host
}
function Copy-Result([string]$RemotePath,[string]$LocalPath,[switch]$Recurse) {
    $arguments=@('-o','BatchMode=yes');if($Recurse){$arguments+='-r'}
    $arguments+=@("${SshTarget}:$RemotePath",$LocalPath)
    $lines=& $ScpExecutable @arguments 2>&1
    $code=$LASTEXITCODE
    $log=Join-Path $destination "scp-$(Split-Path -Leaf $RemotePath).log"
    $lines | Set-Content -Encoding UTF8 $log
    $lines | Out-Host
    if ($code -ne 0) { throw "SCP evidence transfer failed (exit $code) for $RemotePath. Log: $log" }
}
function Save-SampleEvidence([string]$Sample,[int]$Candidate,[string]$Variant,[string]$CopyDestination,[ref]$Failure) {
    # Collection and transfer are independent; retain the original test/restore error.
    foreach($action in @(
        { Step collect $Sample $Candidate $Variant },
        { Copy-Result "$RemoteRoot/build/perf-phase2/$RunId/$Phase/$Sample" $CopyDestination -Recurse }
    )) {
        try { & $action } catch {
            if ($Failure.Value) {
                $secondaryFailures.Add(@{sample=$Sample;reason=$_.Exception.Message})
                Write-Warning "Additional evidence failure for ${Sample}: $($_.Exception.Message)"
            } else { $Failure.Value=$_ }
        }
    }
}
function Test-Evidence([string[]]$Arguments,[string]$Log) {
    $lines=& node (Join-Path $PSScriptRoot evidence.mjs) @Arguments 2>&1
    $code=$LASTEXITCODE
    $lines | Set-Content -Encoding UTF8 $Log
    if($code -ne 0){$reason=($lines|Where-Object{[string]$_ -match '^Error:'}|Select-Object -First 1);throw "Measurement evidence rejected: $reason. Log: $Log"}
}
function Get-AppCheckpoint([string]$Point) {
    try {
        if(!$script:appToken){
            $login=Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"email":"loadtest-admin@coffeeprod.local","password":"password"}' -TimeoutSec 60
            $script:appToken=$login.data.accessToken
            if(!$script:appToken){throw 'Missing access token'}
        }
        $m=Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/performance/manifest" -Headers @{Authorization="Bearer $script:appToken"} -TimeoutSec 60
    } catch { throw "App lifecycle probe failed at $Point (credentials and response omitted)" }
    # PowerShell 7.6 converts ISO JSON strings to DateTime; keep a culture-independent instant.
    $m.processStartedAt=([DateTimeOffset]$m.processStartedAt).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
    if($m.protocolVersion -ne 2 -or $m.owner -ne $owner -or $m.sourceHash -ne $identity.sourceHash -or $m.measurementHash -ne $identity.measurementHash -or $m.diagnostic -ne $false -or $m.disposable -ne $true -or $m.instanceId -notmatch '^[a-f0-9-]{36}$' -or !$m.processStartedAt){throw "App identity mismatch at $Point"}
    if($script:sessionInstance -and ($m.instanceId -ne $script:sessionInstance.instanceId -or $m.processStartedAt -ne $script:sessionInstance.processStartedAt)){throw "App restarted between warmup and measurement at $Point"}
    $m | Add-Member -NotePropertyName point -NotePropertyValue $Point -Force
    $m | Add-Member -NotePropertyName observedAtUtc -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o')) -Force
    return $m
}
function Invoke-K6Stage([string]$Stage,[string]$SessionFolder,[int]$Candidate) {
    $folder=Join-Path $SessionFolder $Stage
    New-Item -ItemType Directory -Path $folder | Out-Null
    $env:PERF_STAGE=$Stage;$env:PERF_SUMMARY_FILE=Join-Path $folder summary.json
    $started=[DateTimeOffset]::UtcNow
    $k6args=@('run','--out',"json=$(Join-Path $folder timeseries.json)",(Join-Path $root k6/phase2/workload.js))
    $arguments=($k6args|ForEach-Object{'"'+$_+'"'}) -join ' '
    $process=Start-Process -FilePath (Get-Command $K6Executable).Source -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $folder stdout.log) -RedirectStandardError (Join-Path $folder stderr.log)
    while(!$process.HasExited){
        try{$process.Refresh();[ordered]@{atUtc=[DateTimeOffset]::UtcNow.ToString('o');cpuSeconds=$process.TotalProcessorTime.TotalSeconds;workingSetBytes=$process.WorkingSet64}|ConvertTo-Json -Compress|Add-Content -Encoding UTF8 (Join-Path $folder load-generator.jsonl)}catch{}
        Start-Sleep -Seconds 1
    }
    $process.WaitForExit();$process.Refresh()
    [ordered]@{stage=$Stage;startedAtUtc=$started.ToString('o');finishedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');exitCode=$process.ExitCode}|ConvertTo-Json|Set-Content -Encoding UTF8 (Join-Path $folder process.json)
    if($process.ExitCode -ne 0){throw "k6 $Stage failed; evidence preserved in $folder"}
    Test-Evidence @('stage',$folder,[string]$Candidate,$Stage) (Join-Path $folder validation.log)
}
function Read-WriteState([string]$Sample,[string]$Variant,[string]$Checkpoint,[string]$SessionFolder) {
    $data=Invoke-Remote "bash scripts/performance/phase2.sh write-state --run-id $RunId --phase $Phase --candidate 6 --variant $Variant --sample $Sample" "$Sample-write-state-$Checkpoint" | ConvertFrom-Json
    $data|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 (Join-Path $SessionFolder "write-state-$Checkpoint.json")
}
function Invoke-HttpSession([string]$sample,[int]$c,[string]$v,[string]$local) {
    New-Item -ItemType Directory -Path $local | Out-Null
    $env:PERF_CANDIDATE=[string]$c;$env:PERF_VARIANT=$v
    $sampleFailure=$null;$monitorAttempted=$false
    $life=[ordered]@{protocolVersion=2;candidate=$c;variant=$v;sample=$sample;status='IN_PROGRESS';checkpoints=[Collections.Generic.List[object]]::new()}
    $script:appToken=$null;$script:sessionInstance=$null
    try {
        Step restore $sample $c $v
        $monitorAttempted=$true; Step monitor-start $sample $c $v
        $script:sessionInstance=Get-AppCheckpoint beforeWarmup
        $life.checkpoints.Add($script:sessionInstance)
        $env:PERF_INSTANCE_ID=$script:sessionInstance.instanceId;$env:PERF_PROCESS_STARTED_AT=$script:sessionInstance.processStartedAt
        @{sourceHash=$identity.sourceHash;measurementHash=$identity.measurementHash;owner=$owner}|ConvertTo-Json|Set-Content -Encoding UTF8 (Join-Path $local identity.json)
        if($c -eq 6){Read-WriteState $sample $v before $local}
        Invoke-K6Stage warmup $local $c
        $life.checkpoints.Add((Get-AppCheckpoint afterWarmup))
        if($c -eq 6){Read-WriteState $sample $v after $local;Test-Evidence @('write',$local,$v.Replace('order','')) (Join-Path $local write-validation.log)}
        $life.checkpoints.Add((Get-AppCheckpoint beforeMeasurement))
        Invoke-K6Stage measurement $local $c
        $life.checkpoints.Add((Get-AppCheckpoint afterMeasurement))
        $life.status='PASS'
        $life|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $local lifecycle.json)
        Test-Evidence @('session',$local,[string]$c) (Join-Path $local lifecycle-validation.log)
    } catch { $sampleFailure=$_;$life.status='FAILED' } finally {
        $life|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $local lifecycle.json)
        if ($monitorAttempted) {
            try { Step monitor-stop $sample $c $v } catch {
                if ($sampleFailure) { $secondaryFailures.Add(@{sample=$sample;reason=$_.Exception.Message}); Write-Warning $_.Exception.Message }
                else { $sampleFailure=$_ }
            }
        }
    }
    $remoteCopy=Join-Path $local server;New-Item -ItemType Directory -Path $remoteCopy | Out-Null
    Save-SampleEvidence $sample $c $v $remoteCopy ([ref]$sampleFailure)
    if ($sampleFailure) { throw $sampleFailure }
}
