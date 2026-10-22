# Run once from project root. Only k6 is started/stopped; application services stay running.
# Runner revision: target-rate-1
# Compatible syntax: Windows PowerShell 5.1. No Python/Pester/admin installation required.
[CmdletBinding()]
param(
    [int[]]$Stages = @(10,20,30,40,60,80,100),
    [ValidateRange(0,10000)][int]$TargetRps = 0,
    [ValidateRange(1,100)][int]$MaxVUs = 40,
    [ValidateRange(10,120)][int]$StageSeconds = 30,
    [ValidateRange(30,600)][int]$ConfirmSeconds = 120,
    [ValidateRange(1,20)][int]$WalletPairs = 1,
    [ValidateRange(60,1800)][int]$DrainTimeoutSeconds = 600,
    [ValidateRange(30,300)][int]$RecoveryTimeoutSeconds = 120,
    [ValidateRange(75,95)][double]$MaxUsedMemoryPercent = 90,
    [ValidateRange(256,2048)][int]$MinFreeMemoryMiB = 512,
    [ValidateRange(600,4096)][int]$MinStartFreeMemoryMiB = 800,
    [ValidatePattern('^http://(localhost|127\.0\.0\.1):[0-9]+$')][string]$BaseUrl = 'http://localhost:9030',
    [switch]$FunctionsOnly
)
Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$script:Project = Split-Path -Parent $PSScriptRoot
$script:OwnedK6 = $null
$script:LastLaunchDiagnostic = $null

function Resolve-ApplicationPath([string]$Name) {
    # Get-Command can return multiple application matches. Never assign that array
    # to ProcessStartInfo.FileName: it becomes one invalid space-joined filename.
    $command = Get-Command -Name $Name -CommandType Application -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and (Test-Path -LiteralPath $_.Path -PathType Leaf) } |
        Select-Object -First 1
    if ($null -eq $command) { throw ('NATIVE_TOOL_NOT_FOUND: ' + $Name + ' is not available on PATH.') }
    return [string]$command.Path
}

function Read-DatabaseJson([string]$Database, [string]$Sql) {
    if (@('offlinepay_load','offlinepay_notifications_load') -notcontains $Database) {
        throw 'Database checks are restricted to the two isolated load databases.'
    }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    if ($script:DockerExe -is [array] -or -not (Test-Path -LiteralPath $script:DockerExe -PathType Leaf)) {
        throw 'NATIVE_EXECUTABLE_MISSING: Docker must resolve to one existing executable file.'
    }
    if (-not (Test-Path -LiteralPath $script:Project -PathType Container)) {
        throw 'PROJECT_DIRECTORY_MISSING: the project working directory does not exist.'
    }
    $info.FileName = [string]$script:DockerExe
    $info.Arguments = 'compose exec -T postgres psql -X -q -A -t -v ON_ERROR_STOP=1 -U offlinepay -d ' + $Database + ' -f -'
    $info.WorkingDirectory = $script:Project
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $child = New-Object System.Diagnostics.Process
    $child.StartInfo = $info
    try {
        try { $null = $child.Start() }
        catch {
            $cause = $_.Exception.GetBaseException()
            $code = $null
            if ($cause -is [ComponentModel.Win32Exception]) { $code = $cause.NativeErrorCode }
            $script:LastLaunchDiagnostic = [ordered]@{
                executable=$info.FileName; workingDirectory=$info.WorkingDirectory
                exceptionType=$cause.GetType().FullName; nativeErrorCode=$code; message=$cause.Message
            }
            throw ('NATIVE_DOCKER_START_FAILED: ' + $cause.Message)
        }
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        $child.StandardInput.WriteLine("SET statement_timeout='8s'; SET lock_timeout='2s'; SET default_transaction_read_only=on;")
        $child.StandardInput.WriteLine($Sql)
        $child.StandardInput.Close()
        if (-not $child.WaitForExit(15000)) {
            $child.Kill()
            throw 'Database check timed out; the search will stop.'
        }
        if ($child.ExitCode -ne 0) { throw ('DATABASE_CHECK_FAILED: ' + $Database + '; check Docker/PostgreSQL.') }
        return ($stdout.GetAwaiter().GetResult().Trim() | ConvertFrom-Json)
    } finally { $child.Dispose() }
}
function Read-Snapshot {
    $coreSql = Get-Content -Raw -LiteralPath (Join-Path $script:Project 'benchmarks/capacity-core.sql')
    $noticeSql = Get-Content -Raw -LiteralPath (Join-Path $script:Project 'benchmarks/capacity-notifications.sql')
    return [pscustomobject]@{
        core = Read-DatabaseJson 'offlinepay_load' $coreSql
        notifications = Read-DatabaseJson 'offlinepay_notifications_load' $noticeSql
        timeIst = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromMinutes(330)).ToString('o')
    }
}
function Test-Invariants($Snapshot) {
    $c = $Snapshot.core; $n = $Snapshot.notifications
    return ($c.negativeWallets -eq 0 -and $c.unbalancedOperations -eq 0 -and $c.duplicateKeys -eq 0 -and $c.walletLedgerMismatches -eq 0 -and
        $c.walletPaise -eq $c.issuedPaise -and $c.outboxEvents -eq $c.operations -and
        $c.failedEvents -eq 0 -and $n.duplicateNotices -eq 0 -and $n.openDeadLetters -eq 0)
}
function Test-Drained($Snapshot) {
    return ((Test-Invariants $Snapshot) -and $Snapshot.core.unpublished -eq 0 -and
        $Snapshot.notifications.consumedEvents -eq $Snapshot.core.operations -and
        $Snapshot.notifications.actualNotices -eq $Snapshot.core.expectedNotices)
}
function Get-HostSample {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average
    return [pscustomobject]@{
        timeIst = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromMinutes(330)).ToString('o')
        freeMemoryMiB = [math]::Round($os.FreePhysicalMemory / 1024,1)
        usedMemoryPercent = [math]::Round(100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize),2)
        cpuPercent = $cpu.Average
        javaWorkingSetMiB = [math]::Round((Get-Process java -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum / 1MB,1)
    }
}
function Get-MemoryDecision($Sample, [int]$PressureSamples) {
    if ($Sample.freeMemoryMiB -lt $MinFreeMemoryMiB -or $Sample.usedMemoryPercent -ge 95) { return 'HOST_MEMORY_LIMIT' }
    if ($Sample.usedMemoryPercent -ge $MaxUsedMemoryPercent -and $PressureSamples -ge 5) { return 'HOST_MEMORY_LIMIT' }
    return ''
}
function Save-Report {
    $script:Report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $script:Output 'capacity-report.json') -Encoding UTF8
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add('# OfflinePay local capacity search')
    $lines.Add('')
    $lines.Add('Stop reason: **' + $script:Report.stopReason + '**. This is a bounded local search, not an unconditional maximum-capacity claim.')
    $lines.Add('')
    $lines.Add('| Kind | VUs | Business req/s | Transfer p95 ms | Read p95 ms | Status |')
    $lines.Add('|---|---:|---:|---:|---:|---|')
    foreach ($stage in $script:Report.stages) {
        $lines.Add('| ' + $stage.kind + ' | ' + $stage.vus + ' | ' + $stage.businessRps + ' | ' +
            $stage.transferP95Ms + ' | ' + $stage.readP95Ms + ' | ' + $stage.status + ' |')
    }
    $lines.Add('')
    if ($TargetRps -eq 0) { $lines.Add('Highest observed passing short-stage rate: ' + $script:Report.bestShortStageRps + ' mixed requests/s.') }
    else { $lines.Add('Direct target-rate attempt: ' + $TargetRps + ' requests/s. No VU capacity search or longer confirmation was requested.') }
    $lines.Add('Confirmation: ' + $script:Report.confirmationStatus + '.')
    foreach ($stage in $script:Report.stages) {
        if ($stage.targetRps -gt 0) {
            $lines.Add('Target-rate stage: requested ' + $stage.targetRps + ' RPS, VU cap ' + $stage.vus +
                ', dropped iterations=' + $stage.droppedIterations + ', measured business-window RPS=' + $stage.businessWindowRps + '.')
            $lines.Add('The normal k6 counter rate includes setup; business-window rate excludes setup and includes observed completion tail. A dropped/aborted/incomplete stage does not establish target capacity.')
        }
    }
    $lines.Add('Workload: about 70% reads / 30% INR1 transfers, wallet pairs=' + $WalletPairs + ', same-host generator.')
    $lines.Add('The normal demo databases are not used. Test rate budgets are raised by the existing application configuration; this runner does not change application controls.')
    $lines.Add('API thresholds passing plus eventual queue drain does not establish sustained notification capacity. See each stage queueGrowthDuringTest and reconciliation/drain timestamps.')
    $lines.Add('A host-memory stop, search ceiling or throughput plateau is not a proven backend maximum. No result implies 10,000 concurrent requests.')
    $lines | Set-Content -LiteralPath (Join-Path $script:Output 'capacity-report.md') -Encoding UTF8
}
function Add-Sample($Stage, [string]$Phase) {
    $sample = Get-HostSample
    $sample | Add-Member -NotePropertyName phase -NotePropertyValue $Phase
    $Stage.samples.Add($sample)
    $sample | Export-Csv -LiteralPath (Join-Path $script:Output ($Stage.name + '-resources.csv')) -Append -NoTypeInformation -Encoding UTF8
    return $sample
}
function Wait-ForDrain($Stage) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $snapshot = Read-Snapshot
        if (-not (Test-Invariants $snapshot)) { throw 'RECONCILIATION_FAILED: money/journal/outbox/DLT/duplicate check failed.' }
        if (Test-Drained $snapshot) {
            $Stage.reconciliation = $snapshot
            $Stage.drainWaitSeconds = [math]::Round($timer.Elapsed.TotalSeconds,2)
            return $snapshot
        }
        if ($timer.Elapsed.TotalSeconds -ge $DrainTimeoutSeconds) { throw 'DELIVERY_DRAIN_TIMEOUT: applications stay running; no higher load started.' }
        $null = Add-Sample $Stage 'drain'
        Write-Host ('Waiting for delivery: unpublished=' + $snapshot.core.unpublished +
            ', notices=' + $snapshot.notifications.actualNotices + '/' + $snapshot.core.expectedNotices)
        Start-Sleep -Seconds 10
    }
}
function Wait-ForHeadroom($Stage) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $sample = Add-Sample $Stage 'before'
        if ($sample.freeMemoryMiB -ge $MinStartFreeMemoryMiB -and $sample.usedMemoryPercent -lt $MaxUsedMemoryPercent) { return }
        Write-Host ('Waiting for RAM headroom: free=' + $sample.freeMemoryMiB + ' MiB. Close nonessential apps; services stay running.')
        if ($timer.Elapsed.TotalSeconds -ge $RecoveryTimeoutSeconds) { throw 'HOST_MEMORY_LIMIT: insufficient headroom; no new load started.' }
        Start-Sleep -Seconds 5
    } while ($true)
}
function Assert-IsolatedRoutes {
    # Reuse one EXISTING load fixture to prove routing BEFORE new users/money are created.
    $fixture = Read-DatabaseJson 'offlinepay_load' "SELECT json_build_object('userId',u.id,'walletId',w.id,'mobile',u.mobile_number) FROM app_user u JOIN wallet w ON w.user_id=u.id WHERE u.name='Load Alice' ORDER BY u.created_at DESC LIMIT 1;"
    if ($null -eq $fixture) { throw 'Run the isolated replay fixture once first; no Load Alice exists in the load database.' }
    $userId = [guid]::Parse($fixture.userId).ToString()
    $notice = Read-DatabaseJson 'offlinepay_notifications_load' ("SELECT json_build_object('id',id) FROM user_notification WHERE user_id='" + $userId + "' ORDER BY created_at DESC,id DESC LIMIT 1;")
    if ($null -eq $notice) { throw 'No migrated load-fixture notice found; check notification consumer/topic before testing.' }
    try {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $csrf = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/auth/csrf') -WebSession $session -TimeoutSec 10
        $headers = @{}; $headers[$csrf.headerName] = $csrf.token
        $body = @{ mobileNumber=$fixture.mobile; password='LoadDemoPassword123!' } | ConvertTo-Json -Compress
        $login = Invoke-RestMethod -Method Post -Uri ($BaseUrl + '/api/v1/auth/login') -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
        $bearer = @{ Authorization=('Bearer ' + $login.accessToken) }
        $wallet = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/wallets/me') -Headers $bearer -TimeoutSec 10
        if ($wallet.walletId -ne $fixture.walletId) { throw 'Wrong core route.' }
        $notices = @(Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/notifications?size=100') -Headers $bearer -TimeoutSec 15)
        if (@($notices | Where-Object { $_.notificationId -eq $notice.id }).Count -ne 1) { throw 'Wrong notification route.' }
    } catch { throw 'ISOLATION_PREFLIGHT_FAILED: gateway login/wallet/inbox did not match the isolated SQL fixture. No financial load started.' }
    finally { $session=$null; $login=$null; $bearer=$null; $csrf=$null; $body=$null; $headers=$null }
}
function Stop-OwnedK6($Process, [int]$Port) {
    if ($null -eq $Process -or $Process.HasExited) { return }
    try {
        $body = '{"data":{"type":"status","id":"default","attributes":{"stopped":true}}}'
        $null = Invoke-RestMethod -Method Patch -Uri ('http://127.0.0.1:' + $Port + '/v1/status') -ContentType 'application/json' -Body $body -TimeoutSec 3
    } catch { }
    if (-not $Process.WaitForExit(5000)) { $Process.Kill(); $null = $Process.WaitForExit(5000) }
}
function Metric-Value($Summary, [string]$Name, [string]$Value) {
    if ($null -eq $Summary.metrics.PSObject.Properties[$Name]) { return $null }
    $values = $Summary.metrics.PSObject.Properties[$Name].Value.values
    if ($null -eq $values.PSObject.Properties[$Value]) { return $null }
    return $values.PSObject.Properties[$Value].Value
}
function Test-ApiSummary($Summary, [int]$ExitCode, [string]$Stopped) {
    if ($Stopped) { return $false }
    if ($ExitCode -ne 0 -or (Metric-Value $Summary 'business_requests' 'count') -le 0 -or
        (Metric-Value $Summary 'transfers_accepted' 'count') -le 0) { return $false }
    foreach ($name in @('business_failures','http_req_failed','wallet_read_ms','transfer_ms')) {
        $property = $Summary.metrics.PSObject.Properties[$name]
        if ($null -eq $property -or $null -eq $property.Value.PSObject.Properties['thresholds']) { return $false }
        $rules = @($property.Value.thresholds.PSObject.Properties)
        if ($rules.Count -eq 0 -or @($rules | Where-Object { -not $_.Value.ok }).Count -ne 0) { return $false }
    }
    return $true
}
function Test-TargetSummary($Summary, [int]$Rate, [int]$Seconds) {
    $drops=Metric-Value $Summary 'dropped_iterations' 'count'
    $iterations=Metric-Value $Summary 'iterations' 'count'
    if ($null -eq $drops -or $drops -ne 0 -or $null -eq $iterations -or $iterations -lt ($Rate * $Seconds)) { return $false }
    $metric=$Summary.metrics.PSObject.Properties['dropped_iterations']
    if ($null -eq $metric.Value.PSObject.Properties['thresholds']) { return $false }
    $rules=@($metric.Value.thresholds.PSObject.Properties)
    return ($rules.Count -gt 0 -and @($rules | Where-Object { -not $_.Value.ok }).Count -eq 0)
}
function New-Stage([string]$Kind, [int]$Vus, [int]$Seconds) {
    return [pscustomobject][ordered]@{
        name=($Kind + '-' + $Vus + 'vu'); kind=$Kind; vus=$Vus; durationSeconds=$Seconds
        targetRps=0; droppedIterations=$null; businessWindowRps=$null
        status='STARTING'; stopReason=''; exitCode=$null; businessRps=$null; acceptedTransfers=$null
        transferP95Ms=$null; readP95Ms=$null; apiPassed=$false; queueGrowthDuringTest=$null
        drainWaitSeconds=$null; before=$null; reconciliation=$null
        queues=(New-Object 'System.Collections.Generic.List[object]')
        samples=(New-Object 'System.Collections.Generic.List[object]')
    }
}
function Invoke-Stage($Stage) {
    $script:Report.stages.Add($Stage)
    $Stage.before = Wait-ForDrain $Stage
    $Stage.reconciliation = $null; $Stage.drainWaitSeconds = $null
    Wait-ForHeadroom $Stage
    $summaryPath = Join-Path $script:Output ($Stage.name + '-summary.json')
    $env:ALLOW_DEMO_LOAD='yes'; $env:BASE_URL=$BaseUrl
    $env:RUN_ID=$script:Report.runId + '-' + $Stage.name
    $env:VUS=[string]$Stage.vus; $env:DURATION=[string]$Stage.durationSeconds + 's'
    $env:WALLET_PAIRS=[string]$WalletPairs; $env:SUMMARY=$summaryPath
    $env:TARGET_RPS=[string]$Stage.targetRps; $env:MAX_VUS=[string]$Stage.vus
    $env:PREALLOCATED_VUS=[string][math]::Min(10,$Stage.vus)
    # Do not inherit an earlier manual THINK_TIME setting and silently change workload.
    Remove-Item Env:THINK_TIME -ErrorAction SilentlyContinue
    $listener = New-Object Net.Sockets.TcpListener ([Net.IPAddress]::Loopback),0
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    $Stage.status='RUNNING'; Save-Report
    $arguments=@('run','--address',('127.0.0.1:' + $port),'--no-color','--quiet')
    if ($Stage.targetRps -gt 0) {
        Write-Host ('Starting target: ' + $Stage.targetRps + ' RPS / ' + $Stage.durationSeconds + 's, worker cap=' + $Stage.vus)
        $arguments+=@('benchmarks/target-rate.js')
    } else {
        Write-Host ('Starting ' + $Stage.kind + ': ' + $Stage.vus + ' VUs / ' + $Stage.durationSeconds + 's')
        $arguments+=@('--vus',([string]$Stage.vus),'--duration',([string]$Stage.durationSeconds + 's'),'benchmarks/capacity.js')
    }
    $child = Start-Process -FilePath $script:K6Exe -WorkingDirectory $script:Project -ArgumentList $arguments -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $script:Output ($Stage.name + '-stdout.txt')) -RedirectStandardError (Join-Path $script:Output ($Stage.name + '-stderr.txt'))
    $script:OwnedK6=$child; $script:OwnedPort=$port
    $null=$child.Handle
    $timer=[Diagnostics.Stopwatch]::StartNew(); $pressure=0; $nextQueue=0
    try {
        while (-not $child.HasExited) {
            $sample=Add-Sample $Stage 'test'
            if ($sample.usedMemoryPercent -ge $MaxUsedMemoryPercent) { $pressure++ } else { $pressure=0 }
            $reason=Get-MemoryDecision $sample $pressure
            if ($reason) { $Stage.stopReason=$reason; Stop-OwnedK6 $child $port; break }
            if ($timer.Elapsed.TotalSeconds -gt (180 + $Stage.durationSeconds + 40)) {
                $Stage.stopReason='GENERATOR_TIMEOUT'; Stop-OwnedK6 $child $port; break
            }
            if ($timer.Elapsed.TotalSeconds -ge $nextQueue) {
                $sql=Get-Content -Raw -LiteralPath (Join-Path $script:Project 'benchmarks/capacity-queues.sql')
                $queue=Read-DatabaseJson 'offlinepay_load' $sql
                $Stage.queues.Add($queue); $nextQueue=$timer.Elapsed.TotalSeconds + 10
                if ($queue.failedEvents -gt 0) { $Stage.stopReason='OUTBOX_FAILED'; Stop-OwnedK6 $child $port; break }
            }
            Start-Sleep -Seconds 2
            $child.Refresh()
        }
        $child.WaitForExit(); $Stage.exitCode=$child.ExitCode
    } finally { Stop-OwnedK6 $child $port; $script:OwnedK6=$null; $child.Dispose() }
    if (Test-Path -LiteralPath $summaryPath) {
        $summary=Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
        if ($null -ne $summary.PSObject.Properties['setup_data']) { throw 'UNSAFE_SUMMARY: unexpected setup data. Do not share raw output.' }
        $Stage.businessRps=Metric-Value $summary 'business_requests' 'rate'
        $Stage.acceptedTransfers=Metric-Value $summary 'transfers_accepted' 'count'
        $Stage.transferP95Ms=Metric-Value $summary 'transfer_ms' 'p(95)'
        $Stage.readP95Ms=Metric-Value $summary 'wallet_read_ms' 'p(95)'
        $Stage.apiPassed=Test-ApiSummary $summary $Stage.exitCode $Stage.stopReason
        if ($Stage.targetRps -gt 0) {
            $Stage.droppedIterations=Metric-Value $summary 'dropped_iterations' 'count'
            $Stage.businessWindowRps=$summary.offlinepayMetadata.completedBusinessRpsExcludingSetup
            $Stage.apiPassed=$Stage.apiPassed -and (Test-TargetSummary $summary $Stage.targetRps $Stage.durationSeconds)
        }
    }
    $queue=Read-DatabaseJson 'offlinepay_load' (Get-Content -Raw -LiteralPath (Join-Path $script:Project 'benchmarks/capacity-queues.sql'))
    $Stage.queues.Add($queue); $Stage.queueGrowthDuringTest=$queue.unpublished
    $after=Wait-ForDrain $Stage
    if ($Stage.apiPassed -and (($after.core.transfers - $Stage.before.core.transfers) -ne $Stage.acceptedTransfers -or
        ($after.core.topups - $Stage.before.core.topups) -ne $WalletPairs)) {
        throw 'RUN_ACCOUNTING_MISMATCH: accepted responses do not match isolated database deltas. Check for another load generator.'
    }
    if ($Stage.apiPassed) { $Stage.status='PASS_API_AND_RECONCILIATION' }
    elseif ($Stage.stopReason) { $Stage.status=$Stage.stopReason }
    elseif ($Stage.targetRps -gt 0) { $Stage.status='TARGET_RATE_NOT_VERIFIED' }
    else { $Stage.status='K6_OR_SLO_FAILURE' }
    Save-Report
    Write-Host ($Stage.status + ': ' + $Stage.vus + ' VUs, mixed req/s=' + $Stage.businessRps + ', transfer p95 ms=' + $Stage.transferP95Ms)
}

if ($FunctionsOnly) { return }
$script:Output=Join-Path $script:Project ('benchmarks/results/auto-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
$null=New-Item -ItemType Directory -Force -Path $script:Output
$script:Report=[ordered]@{
    runnerRevision='target-rate-1'
    runId=('auto-' + [guid]::NewGuid().ToString('N').Substring(0,16)); stopReason='STARTING'; confirmationStatus='NOT_RUN'
    bestShortStageRps=$null; bestShortStageVus=$null; confirmedRps=$null; maximumCapacityEstablished=$false
    hostInfo=$null; k6Version=$null; postgresInfo=$null
    executablePaths=$null; launchDiagnostic=$null; errorDetails=$null
    settings=[ordered]@{stages=$Stages; stageSeconds=$StageSeconds; confirmSeconds=$ConfirmSeconds; walletPairs=$WalletPairs
        targetRps=$TargetRps; maxVUs=$MaxVUs
        baseUrl=$BaseUrl; maxUsedMemoryPercent=$MaxUsedMemoryPercent; minFreeMemoryMiB=$MinFreeMemoryMiB
        minStartFreeMemoryMiB=$MinStartFreeMemoryMiB; drainTimeoutSeconds=$DrainTimeoutSeconds
        workload='Approximately 70% reads / 30% fresh INR1 transfers'; generatorSharesHost=$true}
    stages=(New-Object 'System.Collections.Generic.List[object]')
    preflight=$null
}
$saved=@{}; $lock=$null
foreach ($name in @('ALLOW_DEMO_LOAD','BASE_URL','RUN_ID','VUS','DURATION','WALLET_PAIRS','SUMMARY','THINK_TIME','TARGET_RPS','MAX_VUS','PREALLOCATED_VUS')) {
    $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
try {
    Write-Host 'Capacity runner: target-rate-1'
    if ($env:OS -ne 'Windows_NT') { throw 'This entry point requires Windows CIM. Pure policy checks can run with -FunctionsOnly.' }
    if ($Stages.Count -eq 0 -or @($Stages | Where-Object { $_ -lt 1 -or $_ -gt 200 }).Count -gt 0 -or
        (($Stages -join ',') -ne (($Stages | Sort-Object -Unique) -join ','))) { throw 'Stages must be unique increasing integers in 1..200.' }
    $lock=[IO.File]::Open((Join-Path $script:Project 'benchmarks/results/.capacity.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $script:K6Exe=Resolve-ApplicationPath 'k6.exe'
    $script:DockerExe=Resolve-ApplicationPath 'docker.exe'
    $script:Report.executablePaths=[ordered]@{k6=$script:K6Exe;docker=$script:DockerExe;project=$script:Project}
    Write-Host ('Docker executable: ' + $script:DockerExe)
    Write-Host ('k6 executable: ' + $script:K6Exe)
    $script:Report.k6Version=((& $script:K6Exe version) | Out-String).Trim()
    $os=Get-CimInstance Win32_OperatingSystem
    $cpus=@(Get-CimInstance Win32_Processor)
    $script:Report.hostInfo=[ordered]@{os=$os.Caption;osVersion=$os.Version;totalVisibleMemoryKiB=$os.TotalVisibleMemorySize
        cpuModels=@($cpus | Select-Object -ExpandProperty Name);logicalProcessors=($cpus | Measure-Object NumberOfLogicalProcessors -Sum).Sum}
    $script:Report.postgresInfo=Read-DatabaseJson 'offlinepay_load' "SELECT json_build_object('serverVersion',current_setting('server_version'),'database',current_database());"
    $probe=New-Stage 'preflight' 0 0
    $script:Report.preflight=$probe
    $null=Wait-ForDrain $probe
    Wait-ForHeadroom $probe
    Assert-IsolatedRoutes
    $probe.status='PASS_PREFLIGHT'
    if ($TargetRps -gt 0) {
        $target=New-Stage 'target' $MaxVUs $StageSeconds
        $target.name='target-' + $TargetRps + 'rps'; $target.targetRps=$TargetRps
        Invoke-Stage $target
        $script:Report.stopReason=$target.status
        $script:Report.confirmationStatus='NOT_REQUESTED_FOR_SINGLE_TARGET'
    } else {
    $best=$null; $previous=$null; $plateaus=0; $script:Report.stopReason='SEARCHING'
    foreach ($vus in $Stages) {
        $stage=New-Stage 'search' $vus $StageSeconds
        Invoke-Stage $stage
        if (-not $stage.apiPassed) { $script:Report.stopReason=$stage.status; break }
        if ($null -eq $best -or $stage.businessRps -gt $best.businessRps) {
            $best=$stage; $script:Report.bestShortStageRps=$stage.businessRps; $script:Report.bestShortStageVus=$stage.vus
        }
        if ($null -ne $previous) {
            $gain=100 * ($stage.businessRps / $previous.businessRps - 1)
            if ($gain -lt 5 -and $stage.transferP95Ms -ge $previous.transferP95Ms) { $plateaus++ } else { $plateaus=0 }
            if ($plateaus -ge 2) { $script:Report.stopReason='THROUGHPUT_PLATEAU_OBSERVED'; break }
        }
        $previous=$stage
    }
    if ($script:Report.stopReason -eq 'SEARCHING') { $script:Report.stopReason='SEARCH_CEILING_REACHED' }
    if ($null -ne $best -and $script:Report.stopReason -notmatch 'HOST_|GENERATOR_|OUTBOX_|RECONCILIATION|ACCOUNTING') {
        $confirmation=New-Stage 'confirmation' $best.vus $ConfirmSeconds
        Invoke-Stage $confirmation
        $script:Report.confirmationStatus=$confirmation.status
        if ($confirmation.apiPassed) { $script:Report.confirmedRps=$confirmation.businessRps }
    }
    }
} catch {
    # Preserve the native launch error. Do not include HTTP bodies, headers or SQL input.
    $script:Report.stopReason=($_.Exception.Message -split ':')[0]
    $script:Report.launchDiagnostic=$script:LastLaunchDiagnostic
    $script:Report.errorDetails=$_.Exception.Message
    if ($null -ne $script:Report.preflight -and $script:Report.preflight.status -eq 'STARTING') {
        $script:Report.preflight.status=$script:Report.stopReason
    }
    if ($script:Report.stages.Count -gt 0) {
        $last=$script:Report.stages[$script:Report.stages.Count-1]
        if (@('STARTING','RUNNING') -contains $last.status) { $last.status=$script:Report.stopReason }
        if ($last.kind -eq 'confirmation') { $script:Report.confirmationStatus=$last.status }
    }
    Write-Host ('Search stopped: ' + $script:Report.stopReason)
    Write-Host ('Error detail: ' + $script:Report.errorDetails)
} finally {
    if ($null -ne $script:OwnedK6) { Stop-OwnedK6 $script:OwnedK6 $script:OwnedPort }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    if ($null -ne $lock) { $lock.Dispose() }
    if (@('STARTING','SEARCHING') -contains $script:Report.stopReason) { $script:Report.stopReason='INTERRUPTED_OR_PREFLIGHT_FAILED' }
    foreach ($stage in $script:Report.stages) {
        if (@('STARTING','RUNNING') -contains $stage.status) { $stage.status='INTERRUPTED'; $script:Report.stopReason='INTERRUPTED' }
    }
    Save-Report
    # Reviewed allowlisted summaries/samples only. Raw stdout/stderr are deliberately excluded.
    try {
        $shareFiles=@(Get-ChildItem -LiteralPath $script:Output -File | Where-Object {
            $_.Name -eq 'capacity-report.json' -or $_.Name -eq 'capacity-report.md' -or
            $_.Name -like '*-summary.json' -or $_.Name -like '*-resources.csv'
        } | Select-Object -ExpandProperty FullName)
        Compress-Archive -LiteralPath $shareFiles -DestinationPath (Join-Path $script:Output 'capacity-results.zip') -Force
    } catch { Write-Host 'Report files are saved, but the result ZIP could not be created.' }
}
if ($TargetRps -eq 0) { Write-Host ('Done. Highest observed passing short-stage rate: ' + $script:Report.bestShortStageRps + ' mixed req/s.') }
else { Write-Host ('Target attempt finished: requested ' + $TargetRps + ' RPS; status=' + $script:Report.stopReason) }
Write-Host ('Confirmation: ' + $script:Report.confirmationStatus + '; confirmed mixed req/s=' + $script:Report.confirmedRps)
Write-Host ('Result files: ' + $script:Output)
Write-Host 'Share capacity-results.zip for analysis; it excludes raw process output and fixture credentials.'
Write-Host 'This report does not claim an unconditional maximum or 10,000 concurrent requests. Applications remain running.'
