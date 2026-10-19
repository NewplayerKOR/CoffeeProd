#Requires -RunAsAdministrator
param(
    [Parameter(Mandatory=$true)]
    [ValidateScript({
        $address=$null
        if (-not [Net.IPAddress]::TryParse($_,[ref]$address) -or
            $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
            throw 'SshHost must be an IPv4 address.'
        }
        $true
    })]
    [string]$SshHost
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
Set-Location $root
$pwsh=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
$node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
if (-not (Test-Path -LiteralPath $pwsh) -or -not (Test-Path -LiteralPath $node)) {throw 'Configured Codex runtimes missing.'}
$owners=@(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)
$connections=@(Get-NetTCPConnection -State Established -RemoteAddress $SshHost -RemotePort 22 -ErrorAction Stop |
    Where-Object {$_.OwningProcess -in $owners})
if ($connections.Count -ne 1) {throw 'Expected one existing API SSH connection.'}
$connection=$connections[0]
Write-Host ("Selected SSH: {0}:{1} -> {2}:22" -f $connection.LocalAddress,$connection.LocalPort,$connection.RemoteAddress)
& pktmon status
if ($LASTEXITCODE -ne 0) {throw 'Cannot read pktmon state.'}
& pktmon filter list
if ($LASTEXITCODE -ne 0) {throw 'Cannot read pktmon filters.'}
if ((Read-Host 'Only if pktmon is NOT running and the filter list is EMPTY, type NONE; otherwise stop here') -cne 'NONE') {
    throw 'Existing pktmon state must not be replaced.'
}
$ready=Read-Host ("Start Ubuntu capture for client {0}, port {1}. After its listening message, type READY" -f $connection.LocalAddress,$connection.LocalPort)
if ($ready -cne 'READY') {throw 'Ubuntu capture is not ready; no probe started.'}
$current=@(Get-NetTCPConnection -State Established -OwningProcess $connection.OwningProcess -RemoteAddress $SshHost -RemotePort 22 |
    Where-Object {$_.LocalPort -eq $connection.LocalPort})
if ($current.Count -ne 1) {throw 'SSH tuple changed; restart Ubuntu capture with the new tuple.'}
$id='packet-capture-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$directory=Join-Path $root "k6/results/$id"
if (Test-Path -LiteralPath $directory) {throw 'Never overwrite capture files.'}
New-Item -ItemType Directory -Path $directory | Out-Null
$filterName='CoffeeProd-QA-'+[DateTime]::UtcNow.ToString('HHmmss')
$ownFilter=$false;$ownCapture=$false;$filterSnapshot=$null
function Save-Json($name,$data) {
    [IO.File]::WriteAllText((Join-Path $directory $name),($data | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
}
try {
    & pktmon filter add $filterName -d IPv4 -t TCP -i $connection.LocalAddress $connection.RemoteAddress -p $connection.LocalPort 22
    if ($LASTEXITCODE -ne 0) {throw 'Temporary capture filter failed.'}
    $ownFilter=$true
    $filterSnapshot=(& pktmon filter list | Out-String)
    if ($LASTEXITCODE -ne 0) {throw 'Filter snapshot failed.'}
    Save-Json 'conditions.json' @{started_at_utc=[DateTimeOffset]::UtcNow.ToString('o');snaplen=54;components='nics';
        source_ip=$connection.LocalAddress;source_port=$connection.LocalPort;destination_ip=$connection.RemoteAddress;
        destination_port=22;filter_name=$filterName;empty_original_filter_list_confirmed=$true;
        no_original_capture_running_confirmed=$true;probe_requests=30;server_settings_modified=$false}
    & pktmon start --capture --comp nics --pkt-size 54 --flags 0x12 --file-size 32 --log-mode circular --file-name (Join-Path $directory 'windows-headers.etl')
    if ($LASTEXITCODE -ne 0) {throw 'Capture could not start; no probe executed.'}
    $ownCapture=$true
    Write-Host 'CAPTURE READY. Fixed 30 read-only category GETs will run now.'
    & $pwsh -NoProfile -File (Join-Path $root '.md/improvement/monitoring/run-ssh-tool-probe.ps1') -PacketWindow
    $probeExit=$LASTEXITCODE
    Save-Json 'probe-exit.json' @{exit_code=$probeExit;at_utc=[DateTimeOffset]::UtcNow.ToString('o')}
    if ($probeExit -ne 0) {throw 'Probe failed; preserve capture and do not retry.'}
} finally {
    if ($ownCapture) {
        $stop=(& pktmon stop 2>&1 | Out-String)
        [IO.File]::WriteAllText((Join-Path $directory 'pktmon-stop.txt'),$stop,[Text.UTF8Encoding]::new($false))
        if ($LASTEXITCODE -ne 0) {Write-Warning 'Could not stop the owned capture. Check pktmon status.'}
    }
    if ($ownFilter) {
        $now=(& pktmon filter list | Out-String)
        if ($LASTEXITCODE -eq 0 -and $now -ceq $filterSnapshot) {
            # Native remove deletes ALL filters; only do it for our unchanged sole filter, after confirmed empty initial state.
            & pktmon filter remove
            if ($LASTEXITCODE -ne 0) {Write-Warning 'Temporary filter cleanup failed.'}
        } else {Write-Warning 'Filters changed. No filters were removed; preserve other work.'}
    }
}
& pktmon etl2pcap (Join-Path $directory 'windows-headers.etl') --out (Join-Path $directory 'windows-headers.pcapng')
if ($LASTEXITCODE -ne 0) {throw 'ETL conversion failed; source ETL preserved.'}
& $node (Join-Path $root 'src/test/k6/tcp-header-capture.mjs') (Join-Path $directory 'windows-headers.pcapng') (Join-Path $directory 'windows-tcp-metadata.json')
if ($LASTEXITCODE -ne 0) {throw 'Header-only validation failed. Do not share raw capture; preserve it locally.'}
Write-Host "Completed. Windows result directory: $directory"
Write-Host 'Stop ONLY the Ubuntu tcpdump capture with Ctrl+C. Leave app, DB, SSH and monitoring running.'
