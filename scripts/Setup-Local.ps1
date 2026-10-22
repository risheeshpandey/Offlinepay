param([switch]$Force)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if ((Test-Path '.env') -and -not $Force) { throw '.env already exists. Keep it, or use -Force to replace local credentials.' }
function New-RandomBase64 {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$dbPassword = New-RandomBase64
$jwtSecret = New-RandomBase64
@"
POSTGRES_PASSWORD=$dbPassword
JWT_SECRET_BASE64=$jwtSecret
APP_PORT=9030
DB_PORT=5436
"@ | Set-Content -Encoding ascii '.env'
Write-Host 'Local configuration created. Start with: docker compose up --build -d'
