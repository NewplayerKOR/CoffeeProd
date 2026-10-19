$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'monitoring-freshness.ps1')
$tests=@()
function New-Payload {
    $rows=@()
    foreach ($job in @('coffeeprod','cloudflared')) {
        $rows+=[pscustomobject]@{metric=@{job=$job;qa_signal='state'};value=@(1000,'1')}
        $rows+=[pscustomobject]@{metric=@{job=$job;qa_signal='age_seconds'};value=@(1000,'14')}
    }
    return [pscustomobject]@{status='success';data=[pscustomobject]@{resultType='vector';result=$rows}}
}
function Must-Reject($name,$payload,[double]$elapsed=0) {
    $rejected=$false
    try {Assert-PrometheusSampleFreshness $payload @('coffeeprod','cloudflared') $elapsed | Out-Null} catch {$rejected=$true}
    if (-not $rejected) {throw "Expected rejection: $name"}
    $script:tests+=@{name=$name;passed=$true}
}
$p=New-Payload
$r=@(Assert-PrometheusSampleFreshness $p @('coffeeprod','cloudflared') 0.2)
if ($r.Count -ne 2 -or $r[1].conservative_age_seconds -ne 14.2) {throw 'Healthy samples failed.'}
$tests+=@{name='fresh samples with observer delay';passed=$true}
$p=New-Payload;$p.data.result[3].value[1]='45'
Assert-PrometheusSampleFreshness $p @('coffeeprod','cloudflared') 0 | Out-Null
$tests+=@{name='45-second boundary accepted';passed=$true}
$p=New-Payload;$p.data.result[3].value[1]='46';Must-Reject 'stale sample' $p
$p=New-Payload;$p.data.result[3].value[1]='44';Must-Reject 'response delay exceeds boundary' $p 2
$p=New-Payload;$p.data.result[2].value[1]='0';Must-Reject 'down sample' $p
$p=New-Payload;$p.data.result=@($p.data.result | Where-Object {$_.metric.job -eq 'coffeeprod'});Must-Reject 'missing job' $p
$p=New-Payload;$p.data.result+=($p.data.result[2]);Must-Reject 'duplicate target' $p
$p=New-Payload;$p.data.result[3].value[1]='NaN';Must-Reject 'nonfinite age' $p
$p=New-Payload;$p.data.result[2].value[1]='+Inf';Must-Reject 'nonfinite state' $p
$p=New-Payload;$p.data.result[3].value[1]='-6';Must-Reject 'future sample' $p
$p=New-Payload;$p.status='error';Must-Reject 'API error' $p
$p=New-Payload;$p.data.resultType='matrix';Must-Reject 'wrong result type' $p
$p=New-Payload;Must-Reject 'negative observer duration' $p -1
$p=New-Payload;$p.data.result=@($p.data.result | Where-Object {-not ($_.metric.job -eq 'cloudflared' -and $_.metric.qa_signal -eq 'age_seconds')});Must-Reject 'missing age' $p
@{passed=$tests.Count;failed=0;tests=$tests} | ConvertTo-Json -Depth 5
