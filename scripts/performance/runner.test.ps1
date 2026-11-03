param([string]$OutputPath)
$ErrorActionPreference='Stop'
$WarningPreference='SilentlyContinue'
$PSNativeCommandUseErrorActionPreference=$false
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot runner-functions.ps1),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw ($parseErrors.Message -join '; ')}
foreach($name in @('Invoke-Remote','Copy-Result','Save-SampleEvidence')){
    $definition=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)|Where-Object Name -eq $name
    . ([scriptblock]::Create($definition.Extent.Text))
}
$destination=Join-Path $root "build/dev-task8/runner-test-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $destination | Out-Null
$SshTarget='fixture@localhost';$RemoteRoot='/owned-fixture';$RunId='fixture';$Phase='before'
$secondaryFailures=[Collections.Generic.List[object]]::new()
$SshExecutable=Join-Path $destination fake-ssh.ps1
$ScpExecutable=Join-Path $destination fake-scp.ps1
@'
Write-Output 'fixture: no space left on device'
$global:LASTEXITCODE=[int]$env:PERF_FAKE_EXIT
'@ | Set-Content -Encoding UTF8 $SshExecutable
@'
Write-Output 'fixture: Permission denied'
$global:LASTEXITCODE=[int]$env:PERF_FAKE_EXIT
'@ | Set-Content -Encoding UTF8 $ScpExecutable
$results=[Collections.Generic.List[object]]::new()
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
function Case([string]$Name,[scriptblock]$Body){
    try{& $Body;$results.Add(@{name=$Name;result='PASS'})}
    catch{$results.Add(@{name=$Name;result='FAIL';reason=$_.Exception.Message})}
}
$oldExit=$env:PERF_FAKE_EXIT
try{
    Case 'remote exit 1 preserves the actual error and log without blaming authentication' {
        $env:PERF_FAKE_EXIT='1';$failure=$null
        try{Invoke-Remote 'false' 'disk-check'}catch{$failure=$_}
        Check ($null -ne $failure) 'Remote failure was ignored'
        Check ($failure.Exception.Message -match 'Remote step disk-check failed \(exit 1\)') 'Wrong failure classification'
        Check ($failure.Exception.Message -match 'no space left on device') 'Actual error was hidden'
        Check ($failure.Exception.Message -notmatch 'key/agent|authentication') 'Remote failure blamed authentication'
        Check (Test-Path -LiteralPath (Join-Path $destination disk-check.ssh.log)) 'Missing SSH log'
    }
    Case 'transport exit 255 is reported separately' {
        $env:PERF_FAKE_EXIT='255';$failure=$null
        try{Invoke-Remote 'false' 'transport-check'}catch{$failure=$_}
        Check ($failure.Exception.Message -match 'SSH transport/session.*255') 'Transport failure misclassified'
    }
    Case 'SCP failure names the remote evidence path and preserves its log' {
        $env:PERF_FAKE_EXIT='1';$failure=$null
        try{Copy-Result '/owned-fixture/before/sample' $destination -Recurse}catch{$failure=$_}
        Check ($failure.Exception.Message -match 'exit 1.*owned-fixture/before/sample') 'Copy failure lacks exit/path'
        Check ((Get-Content -Raw -Encoding UTF8 (Join-Path $destination scp-sample.log)) -match 'Permission denied') 'Copy stderr was not preserved'
    }
    Case 'test failure remains primary and transfer is attempted even when collection fails' {
        $script:copyAttempted=$false;$failure=$null
        try{throw 'primary checks failure'}catch{$failure=$_}
        function Step {throw 'secondary collection failure'}
        function Copy-Result {$script:copyAttempted=$true;throw 'secondary copy failure'}
        $previous=$secondaryFailures.Count
        Save-SampleEvidence sample 1 catalog $destination ([ref]$failure)
        Check ($failure.Exception.Message -eq 'primary checks failure') 'Original error was replaced'
        Check $script:copyAttempted 'Transfer was skipped after collection failure'
        Check ($secondaryFailures.Count -eq $previous+2) 'Secondary failures were lost'
    }
    Case 'collection failure becomes primary when the test passed and transfer still runs' {
        $script:copyAttempted=$false;$failure=$null
        function Step {throw 'collection failure'}
        function Copy-Result {$script:copyAttempted=$true}
        Save-SampleEvidence sample 1 catalog $destination ([ref]$failure)
        Check ($failure.Exception.Message -eq 'collection failure') 'Collection failure was ignored'
        Check $script:copyAttempted 'Copy was not attempted'
    }
    Case 'successful collection and transfer leave the sample successful' {
        $script:copyAttempted=$false;$failure=$null
        function Step {}
        function Copy-Result {$script:copyAttempted=$true}
        Save-SampleEvidence sample 1 catalog $destination ([ref]$failure)
        Check ($null -eq $failure -and $script:copyAttempted) 'Successful sample was changed'
    }
}finally{$env:PERF_FAKE_EXIT=$oldExit}
$summary=@{tests=$results.Count;failures=@($results|Where-Object result -eq FAIL).Count;results=$results;evidence=$destination}
if($OutputPath){$summary|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 $OutputPath}
$summary|ConvertTo-Json -Depth 5
if($summary.failures){throw 'Performance runner regression failed'}
