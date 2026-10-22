# Dot-source from the project root: . .\scripts\Import-LocalEnv.ps1
# Explicit allowlist; no output, evaluation, requests, builds or machine-wide changes.
$allowedNames = @('POSTGRES_PASSWORD','JWT_SECRET_BASE64','NOTIFICATION_DB_PASSWORD','INTERNAL_SERVICE_TOKEN',
    'NOTIFICATION_WEBHOOK_URL','NOTIFICATION_WEBHOOK_TOKEN','EUREKA_URL')
Get-Content -LiteralPath .\.env | ForEach-Object {
    if ($_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=(.*)$' -and $allowedNames -contains $Matches[1]) {
        $variableName = $Matches[1]
        $variableValue = $Matches[2].Trim()
        if ($variableValue.Length -ge 2 -and (($variableValue.StartsWith('"') -and $variableValue.EndsWith('"')) -or
                ($variableValue.StartsWith("'") -and $variableValue.EndsWith("'")))) {
            $variableValue = $variableValue.Substring(1, $variableValue.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
    }
}
$env:DB_PASSWORD = $env:POSTGRES_PASSWORD
