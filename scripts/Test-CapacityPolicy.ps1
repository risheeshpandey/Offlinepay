# No HTTP/database requests and no fixture mutations. Runs without Pester.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Find-LocalCapacity.ps1') -FunctionsOnly
$checks=0
function Assert-Policy([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw ('FAIL: ' + $Label) }
    $script:checks++; Write-Host ('PASS: ' + $Label)
}
$snapshot='{"core":{"negativeWallets":0,"unbalancedOperations":0,"duplicateKeys":0,"walletLedgerMismatches":0,"walletPaise":10000,"issuedPaise":10000,"outboxEvents":2,"operations":2,"failedEvents":0,"unpublished":0,"expectedNotices":3},"notifications":{"consumedEvents":2,"actualNotices":3,"duplicateNotices":0,"openDeadLetters":0}}' | ConvertFrom-Json
Assert-Policy (Test-Drained $snapshot) 'Matching funds, journal, events and notices reconcile'
$snapshot.core.unpublished=1
Assert-Policy ((Test-Invariants $snapshot) -and -not (Test-Drained $snapshot)) 'Pending delivery is not treated as reconciled'
$snapshot.core.unpublished=0; $snapshot.notifications.actualNotices=2
Assert-Policy (-not (Test-Drained $snapshot)) 'Missing notices prevent drain success'
$snapshot.notifications.actualNotices=3; $snapshot.core.walletPaise=9999
Assert-Policy (-not (Test-Invariants $snapshot)) 'Money mismatch stops the search'
$snapshot.core.walletPaise=10000; $snapshot.core.outboxEvents=1
Assert-Policy (-not (Test-Invariants $snapshot)) 'Missing outbox event stops the search'
$snapshot.core.outboxEvents=2; $snapshot.core.walletLedgerMismatches=1
Assert-Policy (-not (Test-Invariants $snapshot)) 'Wallet/journal mismatch stops even when grand totals match'
$snapshot.core.walletLedgerMismatches=0
$snapshot.core.outboxEvents=2; $snapshot.notifications.duplicateNotices=1
Assert-Policy (-not (Test-Invariants $snapshot)) 'Duplicate notice stops the search'
$snapshot.notifications.duplicateNotices=0; $snapshot.notifications.openDeadLetters=1
Assert-Policy (-not (Test-Invariants $snapshot)) 'Dead letter stops the search'
Assert-Policy ((Get-MemoryDecision ([pscustomobject]@{freeMemoryMiB=400;usedMemoryPercent=90}) 0) -eq 'HOST_MEMORY_LIMIT') 'Hard memory floor stops immediately'
Assert-Policy ((Get-MemoryDecision ([pscustomobject]@{freeMemoryMiB=600;usedMemoryPercent=93}) 4) -eq '') 'A brief soft-pressure sample is not enough'
Assert-Policy ((Get-MemoryDecision ([pscustomobject]@{freeMemoryMiB=600;usedMemoryPercent=93}) 5) -eq 'HOST_MEMORY_LIMIT') 'Five pressure samples stop the generator'
$summary='{"metrics":{"business_requests":{"values":{"count":10}},"transfers_accepted":{"values":{"count":3}},"business_failures":{"thresholds":{"rate<0.01":{"ok":true}}},"http_req_failed":{"thresholds":{"rate<0.01":{"ok":true}}},"wallet_read_ms":{"thresholds":{"p(95)<1000":{"ok":true}}},"transfer_ms":{"thresholds":{"p(95)<2000":{"ok":true}}}}}' | ConvertFrom-Json
Assert-Policy (Test-ApiSummary $summary 0 '') 'Complete successful workload is eligible'
Assert-Policy (-not (Test-ApiSummary $summary 99 '')) 'Nonzero k6 exit cannot pass'
Assert-Policy (-not (Test-ApiSummary $summary 0 'HOST_MEMORY_LIMIT')) 'REST-stopped run cannot pass even if exit is zero'
$summary.metrics.transfer_ms.thresholds.'p(95)<2000'.ok=$false
Assert-Policy (-not (Test-ApiSummary $summary 0 '')) 'Failed threshold cannot pass'
$summary.metrics.PSObject.Properties.Remove('transfer_ms')
Assert-Policy (-not (Test-ApiSummary $summary 0 '')) 'Missing threshold cannot pass'
$target='{"metrics":{"dropped_iterations":{"values":{"count":0},"thresholds":{"count==0":{"ok":true}}},"iterations":{"values":{"count":30000}}}}' | ConvertFrom-Json
Assert-Policy (Test-TargetSummary $target 1000 30) 'Complete target-rate schedule with zero drops can qualify'
$target.metrics.dropped_iterations.values.count=1
Assert-Policy (-not (Test-TargetSummary $target 1000 30)) 'Dropped arrival iterations cannot qualify as target capacity'
$target.metrics.dropped_iterations.values.count=0; $target.metrics.iterations.values.count=29999
Assert-Policy (-not (Test-TargetSummary $target 1000 30)) 'Incomplete schedule cannot qualify even with zero drops'
$target.metrics.iterations.values.count=30000; $target.metrics.dropped_iterations.thresholds.'count==0'.ok=$false
Assert-Policy (-not (Test-TargetSummary $target 1000 30)) 'Failed dropped-iteration threshold cannot pass'
$target.metrics.PSObject.Properties.Remove('dropped_iterations')
Assert-Policy (-not (Test-TargetSummary $target 1000 30)) 'Missing dropped-iteration measurement cannot pass'
Write-Host ($checks.ToString() + ' capacity policy checks passed; no services started.')
