param([string]$BaseUrl = 'http://localhost:9030')
$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:csrfToken = $null
function Call-Api($Method, $Path, $Body, $Token, $Key) {
    $headers = @{}
    if ($Method -eq 'POST' -and $Path.StartsWith('/api/v1/auth/')) { $headers['X-XSRF-TOKEN'] = $script:csrfToken }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $args = @{ Method=$Method; Uri="$BaseUrl$Path"; Headers=$headers; WebSession=$session }
    if ($null -ne $Body) { $args['ContentType']='application/json'; $args['Body']=($Body | ConvertTo-Json -Compress) }
    return Invoke-RestMethod @args
}
function New-DemoUser($Name) {
    $mobile = '9' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()
    $password = 'Demo-' + [guid]::NewGuid().ToString()
    $signup = Call-Api POST '/api/v1/auth/signup' @{name=$Name;mobileNumber=$mobile;password=$password} $null $null
    $login = Call-Api POST '/api/v1/auth/login' @{mobileNumber=$mobile;password=$password} $null $null
    return @{ Wallet=$signup.walletId; Token=$login.accessToken }
}
function Expect-Status($Code, [scriptblock]$Action) {
    try { & $Action | Out-Null } catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq $Code) { return }
        throw
    }
    throw "Expected HTTP $Code"
}
$csrf = Call-Api GET '/api/v1/auth/csrf' $null $null $null
$script:csrfToken = $csrf.token
Call-Api GET '/actuator/health' $null $null $null | Out-Null
$alice = New-DemoUser 'Alice Demo'
$bob = New-DemoUser 'Bob Demo'
Call-Api POST '/api/v1/demo/topups' @{amount=1000} $alice.Token ([guid]::NewGuid().ToString()) | Out-Null
$key = [guid]::NewGuid().ToString()
$body = @{receiverWalletId=$bob.Wallet;amount=250}
$payment = Call-Api POST '/api/v1/payments/transfer' $body $alice.Token $key
$retry = Call-Api POST '/api/v1/payments/transfer' $body $alice.Token $key
if ($payment.transactionId -ne $retry.transactionId) { throw 'Replay created a second payment' }
Expect-Status 409 { Call-Api POST '/api/v1/payments/transfer' @{receiverWalletId=$bob.Wallet;amount=500} $alice.Token $key }
Expect-Status 422 { Call-Api POST '/api/v1/payments/transfer' @{receiverWalletId=$bob.Wallet;amount=800} $alice.Token ([guid]::NewGuid().ToString()) }
$aliceBalance = Call-Api GET '/api/v1/wallets/me' $null $alice.Token $null
$bobBalance = Call-Api GET '/api/v1/wallets/me' $null $bob.Token $null
if ($aliceBalance.availableBalance -ne 750 -or $bobBalance.availableBalance -ne 250) { throw 'Unexpected balances' }
Write-Host "PASS: Alice INR 750, Bob INR 250. Transaction $($payment.transactionId)"
Write-Host 'PASS: duplicate replay, changed-payload rejection and insufficient-funds rejection.'
