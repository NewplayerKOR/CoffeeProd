function Assert-PrometheusSampleFreshness {
    param($Response,[string[]]$ExpectedJobs,[double]$ObserverElapsedSeconds=0,[double]$MaxAgeSeconds=45)
    if ($Response.status -ne 'success' -or $Response.data.resultType -ne 'vector') {
        throw 'Invalid Prometheus freshness response.'
    }
    if (-not [double]::IsFinite($ObserverElapsedSeconds) -or $ObserverElapsedSeconds -lt 0) {
        throw 'Invalid observer elapsed time.'
    }
    $records=@()
    foreach ($job in $ExpectedJobs) {
        $state=@($Response.data.result | Where-Object {$_.metric.job -eq $job -and $_.metric.qa_signal -eq 'state'})
        $age=@($Response.data.result | Where-Object {$_.metric.job -eq $job -and $_.metric.qa_signal -eq 'age_seconds'})
        if ($state.Count -ne 1 -or $age.Count -ne 1) {throw "Missing or ambiguous samples: $job"}
        $stateValue=0.0;$ageValue=0.0
        $style=[Globalization.NumberStyles]::Float;$culture=[Globalization.CultureInfo]::InvariantCulture
        if (-not [double]::TryParse([string]$state[0].value[1],$style,$culture,[ref]$stateValue) -or
            -not [double]::TryParse([string]$age[0].value[1],$style,$culture,[ref]$ageValue) -or
            -not [double]::IsFinite($stateValue) -or -not [double]::IsFinite($ageValue)) {
            throw "Nonfinite freshness values: $job"
        }
        if ($stateValue -ne 1 -or $ageValue -lt -5 -or $ageValue+$ObserverElapsedSeconds -gt $MaxAgeSeconds) {
            throw "Down/stale sample: $job"
        }
        $records+=@{job=$job;up=$stateValue;sample_age_seconds=$ageValue;
            conservative_age_seconds=$ageValue+$ObserverElapsedSeconds;maximum_age_seconds=$MaxAgeSeconds}
    }
    return $records
}
