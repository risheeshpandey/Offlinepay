"""Disposable Linux/Docker capacity run. Never connects to a user's existing stack.

Uses the agreed fixed-VU mixed workload, then reconciles PostgreSQL and Kafka
delivery. Only an explicit allowlist of reports is public; secrets/raw process
logs stay in a private temporary directory and are removed during cleanup.
"""
import argparse
import base64
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from http.cookiejar import CookieJar
from urllib.request import Request, ProxyHandler, HTTPCookieProcessor, build_opener

ROOT = Path(__file__).resolve().parents[1]
RULES = {
    'http_req_failed': 'rate<0.01', 'business_failures': 'rate<0.01',
    'wallet_read_ms': 'p(95)<1000', 'transfer_ms': 'p(95)<2000',
}


def metric(summary, name, value, default=None):
    return summary.get('metrics', {}).get(name, {}).get('values', {}).get(value, default)


def public_summary(summary):
    # k6 setup_data includes access tokens and must never be published.
    return {key: summary[key] for key in ('root_group', 'state', 'metrics', 'offlinepayMetadata') if key in summary}


def api_result(summary, code, seconds, stopped=None):
    metrics = summary.get('metrics', {})
    thresholds = all(metrics.get(name, {}).get('thresholds', {}).get(rule, {}).get('ok') is True
                     for name, rule in RULES.items())
    metadata = summary.get('offlinepayMetadata', {})
    window = metadata.get('businessWindowSeconds')
    count = metric(summary, 'business_requests', 'count', 0)
    rate = metric(summary, 'business_failures', 'rate', 1)
    http_rate = metric(summary, 'http_req_failed', 'rate')
    read_p95 = metric(summary, 'wallet_read_ms', 'p(95)')
    transfer_p95 = metric(summary, 'transfer_ms', 'p(95)')
    observed = metadata.get('completedBusinessRpsExcludingSetup')
    sane = all(isinstance(n, (int, float)) and math.isfinite(n) and n >= 0
               for n in (rate, http_rate, read_p95, transfer_p95, observed))
    passed = (code == 0 and not stopped and thresholds and count > 0
              and isinstance(window, (int, float)) and window >= seconds - 0.5
              and sane and rate < 0.01 and http_rate < 0.01 and read_p95 < 1000 and transfer_p95 < 2000
              and metric(summary, 'transfers_accepted', 'count', 0) > 0)
    return {
        'apiPassed': bool(passed), 'k6ExitCode': code, 'stopReason': stopped,
        'completedRequests': count, 'failedBusinessRequests': round(count * rate) if sane else None,
        'businessFailureRate': rate, 'httpFailureRate': metric(summary, 'http_req_failed', 'rate'),
        'acceptedTransfers': metric(summary, 'transfers_accepted', 'count', 0),
        'observedBusinessRps': metadata.get('completedBusinessRpsExcludingSetup'),
        'wholeRunBusinessRpsIncludingSetup': metric(summary, 'business_requests', 'rate'),
        'businessWindowSeconds': window,
        'walletReadP95Ms': metric(summary, 'wallet_read_ms', 'p(95)'),
        'transferP95Ms': metric(summary, 'transfer_ms', 'p(95)'),
        'transferP99Ms': metric(summary, 'transfer_ms', 'p(99)'),
        'serverErrors': metric(summary, 'server_errors', 'count', 0),
        'rateLimited': metric(summary, 'rate_limited', 'count', 0),
        'errorCodes': {name: metric(summary, name, 'count', 0) for name in (
            'response_503', 'response_504', 'response_other_5xx', 'transport_failures',
            'error_rate_limit_unavailable', 'error_database_unavailable',
            'error_insufficient_funds', 'error_other')},
    }


def financial_ok(snapshot):
    c, n = snapshot['core'], snapshot['notifications']
    return (all(c.get(k) == 0 for k in ('negativeWallets', 'unbalancedOperations', 'duplicateKeys',
                                      'walletLedgerMismatches', 'failedEvents'))
            and isinstance(c.get('walletPaise'), int) and c['walletPaise'] >= 0
            and c.get('walletPaise') == c.get('issuedPaise')
            and isinstance(c.get('operations'), int) and c['operations'] >= 0
            and c.get('operations') == c.get('outboxEvents')
            and n.get('duplicateNotices') == 0 and n.get('openDeadLetters') == 0)


def delivery_ok(snapshot):
    c, n = snapshot['core'], snapshot['notifications']
    return (financial_ok(snapshot) and c.get('unpublished') == 0
            and all(isinstance(value, int) and value >= 0 for value in
                    (c.get('outboxEvents'), n.get('consumedEvents'), c.get('expectedNotices'), n.get('actualNotices')))
            and c.get('outboxEvents') == n.get('consumedEvents')
            and c.get('expectedNotices') == n.get('actualNotices'))


def best_stage(stages):
    passing = [s for s in stages if s.get('passed') and s.get('kind') == 'search']
    return max(passing, key=lambda s: s['observedBusinessRps']) if passing else None


def highest_concurrency_stage(stages):
    passing = [s for s in stages if s.get('passed') and s.get('kind') == 'search']
    return max(passing, key=lambda s: s['vus']) if passing else None


def json_rows(output):
    try:
        value = json.loads(output)
        return value if isinstance(value, list) else [value]
    except json.JSONDecodeError:
        return [json.loads(line) for line in output.splitlines() if line.strip()]


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


def memory_sample():
    fields = {line.split(':')[0]: int(line.split()[1]) * 1024
              for line in Path('/proc/meminfo').read_text().splitlines()}
    total, available = fields['MemTotal'], fields['MemAvailable']
    limit_path = Path('/sys/fs/cgroup/memory.max')
    current_path = Path('/sys/fs/cgroup/memory.current')
    if limit_path.exists() and current_path.exists():
        limit = limit_path.read_text().strip()
        if limit.isdigit() and int(limit) < total:
            total = int(limit)
            available = min(available, max(0, total - int(current_path.read_text())))
    cpu_stat = Path('/sys/fs/cgroup/cpu.stat')
    return {'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
            'memoryLimitMiB': round(total / 1048576), 'freeMemoryMiB': round(available / 1048576),
            'usedMemoryPercent': round(100 * (1 - available / total), 2),
            'cpuCounters': {line.split()[0]: int(line.split()[1]) for line in cpu_stat.read_text().splitlines()}
                           if cpu_stat.exists() else {}}


class CapacityRun:
    def __init__(self, args, private):
        self.args, self.private = args, Path(private)
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.project = 'offlinepay-capacity-' + secrets.token_hex(6)
        self.topic = 'offlinepay.capacity.payment-committed.v1'
        self.processes, self.handles = [], []
        self.owned_k6 = None
        self.docker_started = False
        self.ports = {}
        while len(set(self.ports.values())) != 11:
            self.ports = {name: free_port() for name in ('db', 'redis', 'kafka', 'discovery', 'core',
                          'notifications', 'gateway', 'm-discovery', 'm-core', 'm-notifications', 'm-gateway')}
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(('SPRING_', 'K6_', 'OFFLINEPAY_'))}
        for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CORE_URL', 'NOTIFICATIONS_URL',
                     'DB_URL', 'DB_PASSWORD', 'DB_USER', 'WORKERS_ENABLED', 'NOTIFICATION_CONSUMERS_ENABLED',
                     'NOTIFICATION_WEBHOOK_URL', 'NOTIFICATION_WEBHOOK_TOKEN', 'MESSAGING_ENABLED',
                     'RATE_LIMIT_ENABLED', 'THINK_TIME', 'EUREKA_URL', 'MANAGEMENT_PORT', 'LOG_FILE'):
            self.env.pop(name, None)
        pg_password, notification_password = secrets.token_hex(24), secrets.token_hex(24)
        self.env.update(CAPACITY_POSTGRES_PASSWORD=pg_password, CAPACITY_DB_PORT=str(self.ports['db']),
                        CAPACITY_REDIS_PORT=str(self.ports['redis']), CAPACITY_KAFKA_PORT=str(self.ports['kafka']),
                        DB_URL=f"jdbc:postgresql://127.0.0.1:{self.ports['db']}/offlinepay_load",
                        DB_USER='offlinepay', DB_PASSWORD=pg_password,
                        NOTIFICATION_DB_URL=f"jdbc:postgresql://127.0.0.1:{self.ports['db']}/offlinepay_notifications_load",
                        NOTIFICATION_DB_USER='offlinepay_notifications', NOTIFICATION_DB_PASSWORD=notification_password,
                        JWT_SECRET_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                        INTERNAL_SERVICE_TOKEN=secrets.token_urlsafe(40),
                        REDIS_HOST='127.0.0.1', REDIS_PORT=str(self.ports['redis']), SPRING_DATA_REDIS_DATABASE='1',
                        KAFKA_BOOTSTRAP_SERVERS=f"127.0.0.1:{self.ports['kafka']}",
                        EUREKA_URL=f"http://127.0.0.1:{self.ports['discovery']}/eureka/",
                        DISCOVERY_ENABLED='true', SERVICE_IP='127.0.0.1', BIND_ADDRESS='127.0.0.1',
                        MANAGEMENT_ADDRESS='127.0.0.1', TRACE_SAMPLE_PROBABILITY='0',
                        AUTH_RATE_LIMIT='10000', API_RATE_LIMIT='100000', RATE_LIMIT_ENABLED='true',
                        WORKERS_ENABLED='true', MESSAGING_ENABLED='true', NOTIFICATION_CONSUMERS_ENABLED='true',
                        NOTIFICATION_WEBHOOK_URL='', NOTIFICATION_WEBHOOK_TOKEN='',
                        ALLOW_DEMO_LOAD='yes', BASE_URL=f"http://127.0.0.1:{self.ports['gateway']}",
                        OFFLINE_SIGNING_PRIVATE_KEY_PATH=str(self.private/'keys/private-key.txt'),
                        OFFLINE_SIGNING_PUBLIC_KEY_PATH=str(self.private/'keys/public-key.txt'))
        # Explicit file avoids accidentally loading a project/user .env.
        self.env_file = self.private/'compose.env'
        self.env_file.write_text('\n'.join(f'{k}={self.env[k]}' for k in (
            'CAPACITY_POSTGRES_PASSWORD', 'CAPACITY_DB_PORT', 'CAPACITY_REDIS_PORT', 'CAPACITY_KAFKA_PORT')) + '\n')
        self.env_file.chmod(0o600)
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(CookieJar()))
        self.report = {'schemaVersion': 1, 'executionAttempted': True, 'stackReady': False,
                       'commit': self.command(['git', 'rev-parse', 'HEAD']).strip(),
                       'hardware': {'platform': platform.platform(), 'architecture': platform.machine(),
                                    'logicalCpus': os.cpu_count(), 'affinityCpus': len(os.sched_getaffinity(0)),
                                    'initialMemory': memory_sample()},
                       'workload': 'Fixed VUs; about 70% wallet reads and 30% INR1 transfers; one hot sender',
                       'rules': RULES, 'rateLimits': {'authPerMinute': 10000, 'apiPerMinute': 100000},
                       'redisTimeoutMs': 500, 'corePoolSize': 10, 'notificationPoolSize': 3,
                       'outboxPollMs': self.args.outbox_poll_ms, 'outboxBatchSize': 20,
                       'heapsMiB': {'core': 512, 'notifications': 256, 'gateway': 256, 'discovery': 192},
                       'coLocatedLoadGenerator': True, 'maximumCapacityClaim': False,
                       'stages': [], 'confirmation': None, 'concurrencyConfirmation': None, 'status': 'STARTING'}

    def command(self, command, input_text=None, timeout=120):
        result = subprocess.run(command, cwd=ROOT, env=self.env, input=input_text,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            # Raw stdout/stderr might contain credentials; they never enter the public report.
            (self.private/'last-command-error.log').write_text(result.stdout + '\n' + result.stderr)
            raise RuntimeError('COMMAND_FAILED: ' + command[0])
        return result.stdout

    def compose(self, *arguments, input_text=None, timeout=120):
        return self.command(['docker', 'compose', '--env-file', str(self.env_file), '-f',
                             str(ROOT/'compose.capacity.yaml'), '-p', self.project, *arguments], input_text, timeout)

    def sql(self, database, statement):
        return self.compose('exec', '-T', 'postgres', 'psql', '-U', 'offlinepay', '-d', database,
                            '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-f', '-', input_text=statement)

    def snapshot(self):
        return {name: json.loads(self.sql(database, (ROOT/'benchmarks'/script).read_text()))
                for name, database, script in (
                    ('core', 'offlinepay_load', 'capacity-core.sql'),
                    ('notifications', 'offlinepay_notifications_load', 'capacity-notifications.sql'))}

    def request(self, path, port=None, body=None, headers=None):
        url = f"http://127.0.0.1:{port or self.ports['gateway']}{path}"
        request = Request(url, data=json.dumps(body).encode() if body is not None else None,
                          headers={'Content-Type': 'application/json', **(headers or {})})
        with self.http.open(request, timeout=15) as response:
            return json.loads(response.read())

    def wait_ready(self, name, seconds=150):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if any(process.poll() is not None for process in self.processes):
                raise RuntimeError('APPLICATION_EXITED_DURING_STARTUP')
            try:
                if self.request('/actuator/health/readiness', self.ports['m-'+name])['status'] == 'UP':
                    return
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError('READINESS_TIMEOUT: ' + name)

    def start(self):
        self.command(['docker', 'info'], timeout=30)
        self.command(['k6', 'version'], timeout=15)
        self.report['javaVersion'] = self.command(['java', '--version']).strip()
        self.report['k6Version'] = self.command(['k6', 'version']).strip()
        model = re.search(r'^model name\s*:\s*(.+)', Path('/proc/cpuinfo').read_text(), re.M)
        self.report['hardware']['cpuModel'] = model[1] if model else platform.processor()
        quota = Path('/sys/fs/cgroup/cpu.max')
        self.report['hardware']['cpuQuota'] = quota.read_text().strip() if quota.exists() else 'unavailable'
        # Signing keys exist only in this disposable runner, never in a public artifact.
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives import serialization
        key = Ed25519PrivateKey.generate()
        keys = self.private/'keys'; keys.mkdir(mode=0o700)
        (keys/'private-key.txt').write_text(base64.b64encode(key.private_bytes(
            serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())).decode())
        (keys/'private-key.txt').chmod(0o600)
        (keys/'public-key.txt').write_text(base64.b64encode(key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode())
        self.docker_started = True
        self.compose('up', '-d', '--wait', '--wait-timeout', '300', timeout=360)
        password = self.env['NOTIFICATION_DB_PASSWORD']
        self.sql('postgres', f"CREATE ROLE offlinepay_notifications LOGIN PASSWORD '{password}';\n"
                            "CREATE DATABASE offlinepay_notifications_load OWNER offlinepay_notifications;\n")
        for topic in (self.topic, self.topic+'.DLT'):
            self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                         'localhost:29092', '--create', '--if-not-exists', '--topic', topic,
                         '--partitions', '3', '--replication-factor', '1')
        for name, heap in (('discovery', 192), ('core', 512), ('notifications', 256), ('gateway', 256)):
            jar = ROOT/('target/offlinepay-0.1.0-SNAPSHOT.jar' if name == 'core' else
                        f'services/{name}/target/offlinepay-{name}-0.1.0-SNAPSHOT.jar')
            if not jar.is_file(): raise RuntimeError('MISSING_JAR: '+name)
            log = (self.private/(name+'-console.log')).open('w'); self.handles.append(log)
            command = ['java', '-Xms64m', f'-Xmx{heap}m', '-Duser.timezone=Asia/Kolkata', '-jar', str(jar),
                       '--spring.profiles.active='+('demo,split,observe' if name == 'core' else 'observe'),
                       f'--server.port={self.ports[name]}', f'--management.server.port={self.ports["m-"+name]}',
                       '--logging.file.name='+str(self.private/(name+'.json.log')),
                       '--eureka.client.service-url.defaultZone='+self.env['EUREKA_URL'],
                       '--offlinepay.messaging.topic='+self.topic]
            if name == 'core':
                command.append(f'--offlinepay.messaging.poll-ms={self.args.outbox_poll_ms}')
            self.processes.append(subprocess.Popen(command, cwd=ROOT, env=self.env, stdout=log, stderr=subprocess.STDOUT))
            self.wait_ready(name)
            print(name+' readiness UP', flush=True)
        self.report['images'] = self.compose('images', '--format', 'json').strip()
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                if self.request('/api/v1/auth/csrf').get('token'): return
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError('GATEWAY_CORE_ROUTE_TIMEOUT')

    def wait_delivery(self):
        start, stable, previous = time.monotonic(), 0, None
        while True:
            snapshot = self.snapshot()
            if not financial_ok(snapshot): raise RuntimeError('FINANCIAL_RECONCILIATION_FAILED')
            stable = stable + 1 if delivery_ok(snapshot) and snapshot == previous else (1 if delivery_ok(snapshot) else 0)
            if stable >= 2: return snapshot, round(time.monotonic()-start, 2)
            if time.monotonic()-start >= self.args.drain_seconds:
                return snapshot, round(time.monotonic()-start, 2)
            previous = snapshot
            time.sleep(5)

    def isolation_probe(self):
        fixture = json.loads(self.sql('offlinepay_load', "SELECT json_build_object('mobile',u.mobile_number,"
            "'userId',u.id,'walletId',w.id) FROM app_user u JOIN wallet w ON w.user_id=u.id "
            "WHERE u.name='Load Alice' ORDER BY u.created_at DESC LIMIT 1;"))
        csrf = self.request('/api/v1/auth/csrf')
        token = self.request('/api/v1/auth/login', body={'mobileNumber': fixture['mobile'],
            'password': 'LoadDemoPassword123!'}, headers={csrf['headerName']: csrf['token']})['accessToken']
        auth = {'Authorization': 'Bearer '+token}
        wallet = self.request('/api/v1/wallets/me', headers=auth)
        notices = self.request('/api/v1/notifications?size=100', headers=auth)
        expected = self.sql('offlinepay_notifications_load', "SELECT id FROM user_notification WHERE user_id='"
                            +fixture['userId']+"' ORDER BY created_at DESC,id DESC LIMIT 1;").strip()
        if wallet['walletId'] != fixture['walletId'] or not expected or not any(n['notificationId'] == expected for n in notices):
            raise RuntimeError('ISOLATION_PREFLIGHT_FAILED')
        self.report['isolationPreflightPassed'] = True

    def resources(self, stage):
        sample = memory_sample()
        sample['stage'] = stage
        sample['javaRssMiB'] = 0
        for process in self.processes:
            path = Path(f'/proc/{process.pid}/status')
            if path.exists():
                match = re.search(r'^VmRSS:\s+(\d+)', path.read_text(), re.M)
                if match: sample['javaRssMiB'] += round(int(match[1])/1024, 1)
        sample['containers'] = json_rows(self.compose('ps', '--format', 'json'))
        # ps health plus numeric/string stats contain no application credentials.
        sample['containerStats'] = json_rows(self.compose('stats', '--no-stream', '--format', '{{json .}}', timeout=20))
        sample['queues'] = json.loads(self.sql('offlinepay_load', (ROOT/'benchmarks/capacity-queues.sql').read_text()))
        with (self.output/'resources.jsonl').open('a') as handle: handle.write(json.dumps(sample)+'\n')
        for name in ('core', 'notifications', 'gateway', 'discovery'):
            try:
                with self.http.open(f"http://127.0.0.1:{self.ports['m-'+name]}/actuator/prometheus", timeout=3) as response:
                    lines = response.read().decode().splitlines()
                selected = [line for line in lines if re.match(
                    r'^(jvm_gc_pause_seconds_(count|sum)|jvm_memory_used_bytes|hikaricp_connections_(active|pending|timeout_total))\b', line)]
                with (self.output/'jvm-metrics.jsonl').open('a') as handle:
                    handle.write(json.dumps({'utc': sample['utc'], 'stage': stage, 'service': name, 'metrics': selected})+'\n')
            except Exception:
                sample['metricsUnavailable'] = True
        return sample

    def run_k6(self, name, vus, seconds, script='ci-mixed.js'):
        before = self.snapshot()
        recovery_end = time.monotonic() + 120
        while True:
            start_memory = memory_sample()
            if start_memory['freeMemoryMiB'] >= 800 and start_memory['usedMemoryPercent'] < 90: break
            if time.monotonic() >= recovery_end: raise RuntimeError('HOST_MEMORY_LIMIT_BEFORE_STAGE')
            time.sleep(5)
        private_summary = self.private/(name+'.json')
        stage_env = dict(self.env, RUN_ID=name+'-'+secrets.token_hex(5), VUS=str(vus),
                         DURATION=f'{seconds}s', SUMMARY=str(private_summary))
        command = ['k6', 'run', '--address', '127.0.0.1:'+str(free_port()), str(ROOT/'benchmarks'/script)]
        stopped, hot_samples = None, 0
        print(f'Start {name}: {vus} VUs, {seconds}s', flush=True)
        with (self.private/(name+'-k6.log')).open('w') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=stage_env, stdout=log, stderr=subprocess.STDOUT)
            self.owned_k6 = process
            started = time.monotonic()
            while process.poll() is None:
                time.sleep(2)
                sample = memory_sample()
                hot_samples = hot_samples + 1 if sample['usedMemoryPercent'] >= 90 else 0
                if sample['freeMemoryMiB'] < 512 or sample['usedMemoryPercent'] >= 95 or hot_samples >= 5:
                    stopped = 'HOST_MEMORY_LIMIT'
                if any(p.poll() is not None for p in self.processes): stopped = 'APPLICATION_EXITED'
                if time.monotonic()-started > seconds+230: stopped = 'STAGE_TIMEOUT'
                if stopped:
                    process.send_signal(2)
                    try: process.wait(timeout=35)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                    break
                if int(time.monotonic()-started) % 10 < 2:
                    self.resources(name)
            code = process.wait()
            self.owned_k6 = None
        raw = json.loads(private_summary.read_text()) if private_summary.exists() else {}
        summary = public_summary(raw)
        (self.output/(name+'.json')).write_text(json.dumps(summary, indent=2))
        if script == 'replay.js':
            if code or stopped: raise RuntimeError('REPLAY_PREFLIGHT_FAILED')
            return
        result = {'name': name, 'kind': 'confirmation' if name.startswith('confirm') else
                  'warmup' if name == 'warmup' else 'search', 'vus': vus, 'requestedSeconds': seconds,
                  **api_result(summary, code, seconds, stopped)}
        result['afterTrafficReconciliation'] = self.snapshot()
        self.report['pendingStage'] = result
        self.save()
        after, drain_seconds = self.wait_delivery()
        result.update(afterReconciliation=after, deliveryDrainSeconds=drain_seconds,
                      deliveryPassed=delivery_ok(after),
                      databaseTransferDelta=after['core']['transfers']-before['core']['transfers'],
                      topupDelta=after['core']['topups']-before['core']['topups'])
        result['committedNotAcknowledgedTransfers'] = result['databaseTransferDelta']-result['acceptedTransfers']
        result['passed'] = (result['apiPassed'] and result['deliveryPassed'] and result['topupDelta'] == 1
                            and result['committedNotAcknowledgedTransfers'] == 0)
        self.report.pop('pendingStage', None)
        print(f"Finish {name}: passed={result['passed']}, observed RPS={result['observedBusinessRps']}, drain={drain_seconds}s", flush=True)
        return result

    def execute(self):
        self.report['phase'] = 'startup'
        self.start()
        self.report['stackReady'] = True
        self.report['phase'] = 'replay_and_isolation'
        self.run_k6('replay', 1, 1, 'replay.js')
        preflight, _ = self.wait_delivery()
        if not delivery_ok(preflight): raise RuntimeError('REPLAY_DELIVERY_TIMEOUT')
        self.isolation_probe()
        self.report['preflight'] = preflight
        self.report['phase'] = 'warmup'
        warmup = self.run_k6('warmup', 5, 20)
        self.report['stages'].append(warmup)
        if not warmup['passed']: raise RuntimeError('WARMUP_FAILED')
        for vus in self.args.levels:
            self.report['phase'] = f'search-{vus}'
            stage = self.run_k6(f'search-{vus}', vus, self.args.seconds)
            self.report['stages'].append(stage)
            self.save()
            if not stage['passed']:
                self.report['searchStopReason'] = stage['stopReason'] or 'STAGE_RULE_OR_RECONCILIATION_FAILED'
                break
        best = best_stage(self.report['stages'])
        highest_concurrency = highest_concurrency_stage(self.report['stages'])
        self.report['highestObservedPassingStage'] = best
        self.report['highestPassingConcurrencyStage'] = highest_concurrency
        if best:
            # A failed stage may leave a backlog. Do not attribute that earlier
            # work to a new confirmation or start new traffic while it persists.
            self.report['phase'] = 'pre_confirmation_drain'
            pre_confirmation, seconds = self.wait_delivery()
            self.report['preConfirmationReconciliation'] = pre_confirmation
            self.report['preConfirmationDrainSeconds'] = seconds
            if not delivery_ok(pre_confirmation):
                raise RuntimeError('PRE_CONFIRMATION_DELIVERY_TIMEOUT')
            self.report['phase'] = 'confirmation'
            confirmation = self.run_k6('confirm-throughput-'+str(best['vus']), best['vus'], self.args.confirm_seconds)
            self.report['confirmation'] = confirmation
            self.save()
            if highest_concurrency['vus'] == best['vus']:
                self.report['concurrencyConfirmation'] = confirmation
            elif confirmation['passed']:
                self.report['phase'] = 'concurrency_confirmation'
                self.report['concurrencyConfirmation'] = self.run_k6(
                    'confirm-concurrency-'+str(highest_concurrency['vus']), highest_concurrency['vus'], self.args.confirm_seconds)
            both = confirmation['passed'] and bool(self.report['concurrencyConfirmation'] and self.report['concurrencyConfirmation']['passed'])
            self.report['status'] = 'CONFIRMED_OBSERVATION' if both else 'CONFIRMATION_FAILED'
        else:
            self.report['status'] = 'NO_PASSING_SEARCH_STAGE'
        self.report['searchCeilingReached'] = bool(self.report['stages'][-1].get('passed') and
                                                   self.report['stages'][-1]['vus'] == self.args.levels[-1])
        return self.report['status'] == 'CONFIRMED_OBSERVATION'

    def diagnostics(self):
        entries = []
        for name in ('core', 'notifications', 'gateway', 'discovery'):
            path = self.private/(name+'-console.log')
            if path.exists():
                text = path.read_text(errors='replace')[-2000000:]
                # Class names only: arbitrary exception messages/stack traces may contain credentials.
                types = sorted(set(re.findall(r'\b(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)\b', text)))
                if types: entries.append({'service': name, 'exceptionTypes': types[:30]})
        (self.output/'diagnostics.json').write_text(json.dumps(entries, indent=2))

    def save(self):
        (self.output/'report.json').write_text(json.dumps(self.report, indent=2))
        lines = ['# OfflinePay capacity observation', '', 'Status: '+self.report['status'], '',
                 'Fixed VUs, one shared sender, approximately 70% reads / 30% fresh transfers.',
                 'Application services, infrastructure and k6 share this runner. No universal maximum is claimed.', '',
                 f"Outbox polling: {self.report.get('outboxPollMs')} ms; batch: 20. Default application polling is 2000 ms.", '',
                 '| Stage | VUs | Seconds | Observed business RPS | API pass | Delivery after drain | Overall pass |',
                 '| --- | ---: | ---: | ---: | --- | --- | --- |']
        confirmations = [self.report['confirmation']] if self.report['confirmation'] else []
        concurrency = self.report['concurrencyConfirmation']
        if concurrency and concurrency is not self.report['confirmation']: confirmations.append(concurrency)
        for stage in self.report['stages'] + confirmations:
            lines.append('| {name} | {vus} | {requestedSeconds} | {observedBusinessRps} | {apiPassed} | {deliveryPassed} | {passed} |'.format(**stage))
        lines += ['', 'Rates exclude setup using the observed business interval and include completion tail.',
                  'API success is separate from notification delivery; drain seconds are in report.json.',
                  'A passing search stage is a short observation. Only a passing confirmation supports a longer-run claim.',
                  'A search ceiling/resource stop does not establish a backend maximum. Failed stages remain in the report.']
        (self.output/'report.md').write_text('\n'.join(lines)+'\n')

    def cleanup(self):
        if self.owned_k6 is not None and self.owned_k6.poll() is None:
            self.owned_k6.send_signal(2)
            try: self.owned_k6.wait(timeout=10)
            except subprocess.TimeoutExpired: self.owned_k6.kill(); self.owned_k6.wait()
        for process in reversed(self.processes):
            if process.poll() is None: process.terminate()
        for process in reversed(self.processes):
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in self.handles: handle.close()
        if self.docker_started:
            # Only this randomly named disposable Compose project can be removed.
            self.compose('down', '--volumes', '--remove-orphans', timeout=90)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'target/capacity-report')
    parser.add_argument('--levels', default='10,25,50,100,200')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--confirm-seconds', type=int, default=180)
    parser.add_argument('--drain-seconds', type=int, default=600)
    parser.add_argument('--outbox-poll-ms', type=int, default=100,
                        help='Recorded CI deployment setting; normal application default is 2000ms')
    parser.add_argument('--allow-local', action='store_true', help='Explicitly allow a disposable Linux/Docker local run')
    args = parser.parse_args()
    args.levels = [int(v) for v in args.levels.split(',')]
    if not (1 <= len(args.levels) <= 8 and args.levels == sorted(set(args.levels))
            and all(1 <= n <= 300 for n in args.levels) and 10 <= args.seconds <= 120
            and 60 <= args.confirm_seconds <= 300 and 30 <= args.drain_seconds <= 900
            and 50 <= args.outbox_poll_ms <= 5000):
        parser.error('Invalid bounded stage settings')
    if platform.system() != 'Linux' or (os.environ.get('GITHUB_ACTIONS') != 'true' and not args.allow_local):
        parser.error('Requires GitHub Actions Linux, or --allow-local on a disposable Linux/Docker host')
    if any(shutil.which(binary) is None for binary in ('docker', 'java', 'k6')):
        parser.error('docker, java and k6 must be installed')
    (ROOT/'target').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='capacity-private-', dir=ROOT/'target') as private:
        runner = CapacityRun(args, private)
        success = False
        try:
            success = runner.execute()
        except KeyboardInterrupt:
            runner.report.update(status='STOPPED', stopReason='RUN_INTERRUPTED')
        except Exception as error:
            # Exception text may contain an HTTP URL but not a body; use only known safe status labels.
            message = str(error)
            safe = message if re.fullmatch(r'[A-Z_]+(?:: [A-Za-z0-9_.-]+)?', message) else type(error).__name__
            runner.report.update(status='STOPPED', stopReason=safe)
            print('Capacity run stopped: '+safe, flush=True)
        finally:
            try: runner.diagnostics()
            except Exception: runner.report['diagnosticsUnavailable'] = True
            try: runner.cleanup()
            except Exception: runner.report['cleanupError'] = True
            runner.save()
        print('Public report: '+str(runner.output/'report.md'), flush=True)
        return 0 if success and not runner.report.get('cleanupError') else 1


if __name__ == '__main__':
    raise SystemExit(main())
