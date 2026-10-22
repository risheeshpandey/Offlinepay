"""Development smoke: real PowerShell/k6 processes, fake HTTP/SQL/CIM boundaries.

No PostgreSQL/Kafka/Windows capacity claim. Usage: python verify_capacity_runner.py
--pwsh /path/to/pwsh --k6 /path/to/k6. No real credentials, accounts or money.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

root = Path(__file__).resolve().parents[1]
state = {"topups": 0, "transfers": 0, "users": {}, "alice": None}
mutex = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, value, status=200):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The real k6 stop test deliberately cancels an in-flight request.

    def do_GET(self):
        if self.path == '/api/v1/wallets/me' and state.get('slow',False):
            time.sleep(0.04)
        with mutex:
            if self.path in ['/smoke/slow-on','/smoke/slow-off']:
                state['slow']=self.path.endswith('on')
                return self.reply({'ok':True})
            ops = state["topups"] + state["transfers"]
            notices = state["topups"] + 2 * state["transfers"]
            if self.path == "/smoke/core":
                return self.reply(dict(negativeWallets=0, unbalancedOperations=0, duplicateKeys=0, walletLedgerMismatches=0,
                    walletPaise=state["topups"] * 10000000, issuedPaise=state["topups"] * 10000000,
                    outboxEvents=ops, operations=ops, failedEvents=0, unpublished=0,
                    expectedNotices=notices, topups=state["topups"], transfers=state["transfers"]))
            if self.path == "/smoke/notices":
                return self.reply(dict(consumedEvents=ops, actualNotices=notices, duplicateNotices=0, openDeadLetters=0))
            if self.path == "/smoke/fixture":
                return self.reply(state["alice"])
            if self.path == "/smoke/notice":
                return self.reply({"id": "fixture-notice"})
            if self.path.startswith("/api/v1/auth/csrf"):
                return self.reply({"headerName": "X-XSRF-TOKEN", "token": "FAKE_CSRF"})
            if self.path == "/api/v1/wallets/me":
                mobile = self.headers.get("Authorization", "").removeprefix("Bearer FAKE_")
                return self.reply({"walletId": state["users"][mobile]["walletId"]})
            if self.path.startswith("/api/v1/notifications"):
                return self.reply([{"notificationId": "fixture-notice"}, {"notificationId": "other-fixture"}])
            return self.reply({"error": "unexpected fake route"}, 404)

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if self.path == '/api/v1/payments/transfer' and state.get('slow',False):
            time.sleep(0.04)
        with mutex:
            if self.path == "/api/v1/auth/signup":
                user = {"userId": str(uuid4()), "walletId": str(uuid4()), "mobile": payload["mobileNumber"]}
                state["users"][user["mobile"]] = user
                if payload["name"] == "Load Alice":
                    state["alice"] = user
                return self.reply({"walletId": user["walletId"]}, 201)
            if self.path == "/api/v1/auth/login":
                return self.reply({"accessToken": "FAKE_" + payload["mobileNumber"]})
            if self.path == "/api/v1/demo/topups":
                state["topups"] += 1
                return self.reply({"transactionId": str(uuid4())})
            if self.path == "/api/v1/payments/transfer":
                state["transfers"] += 1
                return self.reply({"transactionId": str(uuid4())})
            return self.reply({"error": "unexpected fake route"}, 404)


HARNESS = r"""
$ErrorActionPreference='Stop'
. (Join-Path $env:CAPACITY_SMOKE_PROJECT 'scripts/Find-LocalCapacity.ps1') -FunctionsOnly -BaseUrl $env:CAPACITY_SMOKE_BASE
$script:Project=$env:CAPACITY_SMOKE_PROJECT
$script:Output=$env:CAPACITY_SMOKE_OUTPUT
$script:K6Exe=$env:CAPACITY_SMOKE_K6
$script:ForcePressure=$false; $script:HostCalls=0
function Get-HostSample {
    $script:HostCalls++
    $free=1500; $used=80
    if ($script:ForcePressure -and $script:HostCalls -ge 4) { $free=400; $used=96 }
    return [pscustomobject]@{timeIst=[DateTimeOffset]::Now.ToString('o');freeMemoryMiB=$free;usedMemoryPercent=$used;cpuPercent=10;javaWorkingSetMiB=50}
}
function Read-DatabaseJson([string]$Database,[string]$Sql) {
    $route='/smoke/core'
    if ($Sql -like "*WHERE u.name='Load Alice'*") {$route='/smoke/fixture'}
    elseif ($Sql -like '*WHERE user_id=*ORDER BY*') {$route='/smoke/notice'}
    elseif ($Database -eq 'offlinepay_notifications_load') {$route='/smoke/notices'}
    return (Invoke-RestMethod -Uri ($env:CAPACITY_SMOKE_BASE+$route))
}
$script:Report=[ordered]@{runId='fake-process-smoke';stopReason='SMOKE';confirmationStatus='NOT_RUN';bestShortStageRps=$null;stages=(New-Object 'System.Collections.Generic.List[object]')}
$stage=New-Stage 'smoke-pass' 1 4
Invoke-Stage $stage
if (-not $stage.apiPassed -or -not (Test-Drained $stage.reconciliation)) {throw 'Real k6/PowerShell pass path failed'}
Assert-IsolatedRoutes
Write-Host 'PASS: live process stage, fixture route preflight, summary and reconciliation'
$target=New-Stage 'target' 2 4
$target.targetRps=20; $target.name='target-pass'
Invoke-Stage $target
if (-not $target.apiPassed -or $target.droppedIterations -ne 0 -or $null -eq $target.businessWindowRps) {throw 'Target-rate pass path failed'}
Write-Host 'PASS: real constant-arrival-rate scenario, complete schedule, zero drops and setup-excluding observed rate'
$null=Invoke-RestMethod -Uri ($env:CAPACITY_SMOKE_BASE+'/smoke/slow-on')
$limited=New-Stage 'target' 1 4
$limited.targetRps=200; $limited.name='target-dropped'
Invoke-Stage $limited
if ($limited.apiPassed -or $limited.droppedIterations -le 0 -or $limited.status -ne 'TARGET_RATE_NOT_VERIFIED') {throw 'Dropped target was incorrectly passed'}
$null=Invoke-RestMethod -Uri ($env:CAPACITY_SMOKE_BASE+'/smoke/slow-off')
Write-Host 'PASS: insufficient VUs produce dropped arrivals and cannot establish target capacity'
$script:ForcePressure=$true; $script:HostCalls=0
$stopped=New-Stage 'smoke-stop' 1 20
Invoke-Stage $stopped
if ($stopped.apiPassed -or $stopped.status -ne 'HOST_MEMORY_LIMIT') {throw 'Memory-stopped stage was incorrectly accepted'}
Write-Host 'PASS: live k6 REST memory stop, no false successful stage'
"""

NATIVE_HARNESS = r"""
$ErrorActionPreference='Stop'
. (Join-Path $env:CAPACITY_SMOKE_PROJECT 'scripts/Find-LocalCapacity.ps1') -FunctionsOnly
$script:Project=$env:CAPACITY_NATIVE_PROJECT
$matches=@(Get-Command docker.exe -CommandType Application)
if ($matches.Count -lt 2) {throw 'Duplicate command fixture was not installed'}
$legacy=New-Object Diagnostics.ProcessStartInfo
$legacy.FileName=$matches.Source; $legacy.UseShellExecute=$false
$broken=New-Object Diagnostics.Process; $broken.StartInfo=$legacy
$failed=$false
try {$null=$broken.Start()} catch {$failed=$true} finally {$broken.Dispose()}
if (-not $failed) {throw 'Legacy duplicate-path resolution unexpectedly launched'}
Write-Host 'PASS: reproduced original array-to-filename launch failure'
$script:DockerExe=Resolve-ApplicationPath 'docker.exe'
if ($script:DockerExe -is [array] -or $script:DockerExe -ne $matches[0].Path) {throw 'Resolver did not choose one exact executable'}
Write-Host 'PASS: duplicate matches resolve to one executable Path'
$result=Read-DatabaseJson 'offlinepay_load' "SELECT json_build_object('ok',true);"
if (-not $result.readOnly -or $result.workingDirectory -ne $env:CAPACITY_NATIVE_PROJECT -or $result.database -ne 'offlinepay_load') {throw 'Actual native process/stdin/cwd check failed'}
Write-Host 'PASS: real native launch with spaces, SQL stdin, working directory and JSON output'
$failed=$false
try {$null=Read-DatabaseJson 'offlinepay_load' 'FAIL_FIXTURE'} catch {
    if ($_.Exception.Message -notlike 'DATABASE_CHECK_FAILED:*' -or $_.Exception.Message -like '*FAKE_SECRET*') {throw}
    $failed=$true
}
if (-not $failed) {throw 'Nonzero native query exit was accepted'}
Write-Host 'PASS: nonzero native exit stops without printing stderr credentials'
$script:DockerExe=Join-Path $env:CAPACITY_NATIVE_PROJECT 'missing.exe'
$failed=$false
try {$null=Read-DatabaseJson 'offlinepay_load' 'SELECT 1;'} catch {
    if ($_.Exception.Message -notlike 'NATIVE_EXECUTABLE_MISSING:*') {throw}; $failed=$true
}
if (-not $failed) {throw 'Missing executable was accepted'}
Write-Host 'PASS: missing executable rejected before launch'
$script:DockerExe=$env:CAPACITY_BAD_EXE
$failed=$false
try {$null=Read-DatabaseJson 'offlinepay_load' 'SELECT 1;'} catch {
    if ($_.Exception.Message -notlike 'NATIVE_DOCKER_START_FAILED:*' -or $null -eq $script:LastLaunchDiagnostic.nativeErrorCode -or -not $script:LastLaunchDiagnostic.message) {throw}
    $failed=$true
}
if (-not $failed) {throw 'Invalid binary was accepted'}
Write-Host 'PASS: native launch failure retains OS error code/message and executable path'
"""


def native_smoke(pwsh):
    if os.name == 'nt':
        print('Native shim fixture is Linux-only; real Windows .exe integration remains a separate check.')
        return
    with tempfile.TemporaryDirectory(prefix='capacity native spaces ') as temp:
        project = Path(temp) / 'project with spaces'
        project.mkdir()
        dirs = [Path(temp) / 'first bin', Path(temp) / 'second bin']
        fake = f'#!{sys.executable}\n' + '''import sys,json,os
text=sys.stdin.read()
if 'FAIL_FIXTURE' in text:
 sys.stderr.write('FAKE_SECRET_STDERR_SENTINEL');sys.exit(7)
print(json.dumps({'readOnly':"default_transaction_read_only=on" in text,'workingDirectory':os.getcwd(),'database':sys.argv[sys.argv.index('-d')+1]}))
'''
        for directory in dirs:
            directory.mkdir()
            executable = directory / 'docker.exe'
            executable.write_text(fake)
            executable.chmod(0o755)
        bad = project / 'invalid.exe'
        bad.write_text('This fixture is not an executable binary.')
        bad.chmod(0o755)
        script = Path(temp) / 'native.ps1'
        script.write_text(NATIVE_HARNESS)
        env = dict(os.environ, CAPACITY_SMOKE_PROJECT=str(root), CAPACITY_NATIVE_PROJECT=str(project),
            CAPACITY_BAD_EXE=str(bad), PATH=os.pathsep.join(map(str,dirs)) + os.pathsep + os.environ['PATH'])
        subprocess.run([pwsh, '-NoLogo', '-NoProfile', '-File', str(script)], env=env, check=True, timeout=45)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pwsh", required=True)
    parser.add_argument("--k6", required=True)
    args = parser.parse_args()
    native_smoke(args.pwsh)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix="capacity-smoke-") as temp:
            script = Path(temp) / "smoke.ps1"
            script.write_text(HARNESS)
            env = dict(os.environ, CAPACITY_SMOKE_PROJECT=str(root), CAPACITY_SMOKE_OUTPUT=temp,
                CAPACITY_SMOKE_K6=str(Path(args.k6).resolve()),
                CAPACITY_SMOKE_BASE=f"http://127.0.0.1:{server.server_port}")
            subprocess.run([args.pwsh, "-NoLogo", "-NoProfile", "-File", str(script)],
                env=env, check=True, timeout=90)
            for path in Path(temp).glob("*-summary.json"):
                content = path.read_text(encoding="utf-8-sig")
                parsed = json.loads(content)
                assert "setup_data" not in parsed and "FAKE_" not in content, path
            print("Real process smoke passed with fake HTTP/SQL/CIM; no capacity figures are retained.")
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
