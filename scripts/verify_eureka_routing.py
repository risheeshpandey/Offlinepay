"""Maintainer transport check: real Eureka + Gateway + notification/Feign JARs.

Uses random loopback ports, an isolated H2 database and a small fake core API.
No Docker, user database, .env, payment state or signing keys are read or changed.
Build first: mvn -f services/pom.xml -DskipTests package
Run: python scripts/verify_eureka_routing.py --h2-jar /absolute/path/h2.jar
This is supplementary verification, not a replacement for manual Postman tests.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[1]
HTTP = build_opener(ProxyHandler({}))
USER = '11111111-1111-4111-8111-111111111111'
observed = []


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def request(url, method='GET', body=None, headers=None):
    req = Request(url, data=json.dumps(body).encode() if body is not None else None, method=method,
                  headers={'Accept': 'application/json', **(headers or {})})
    try:
        with HTTP.open(req, timeout=4) as r:
            return r.status, r.read().decode()
    except HTTPError as e:
        return e.code, e.read().decode()
    except (URLError, TimeoutError, ConnectionError):
        return 0, ''


def wait_for(check, label, processes, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f'Application exited while waiting for {label}; inspect test logs')
        if check():
            print('PASS:', label, flush=True)
            return
        time.sleep(0.5)
    raise RuntimeError(f'Timed out: {label}')


class Core(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        observed.append(self.path)
        if self.path == '/api/v1/internal/session':
            valid = self.headers.get('Authorization') == 'Bearer discovery-test-access-token'
            body, status = ({'userId': USER}, 200) if valid else ({'code': 'UNAUTHORIZED'}, 401)
        else:
            body, status = {'service': 'discovered-core', 'port': self.server.server_port}, 200
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--h2-jar', type=Path, default=Path.home() / '.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar')
    parser.add_argument('--java', default='java')
    parser.add_argument('--logs', type=Path)
    args = parser.parse_args()
    if not args.h2_jar.is_file():
        parser.error('An existing H2 test JAR is required; it is not packaged in production artifacts')
    logs = args.logs or Path(tempfile.mkdtemp(prefix='offlinepay-eureka-'))
    logs.mkdir(parents=True, exist_ok=True)
    registry, notices, gateway = port(), port(), port()
    base = f'http://127.0.0.1:{registry}'
    processes, handles = [], []
    core = ThreadingHTTPServer(('127.0.0.1', 0), Core)
    threading.Thread(target=core.serve_forever, daemon=True).start()
    # Deliberately random: no localhost9031/9032 dependency can make this test pass.
    def start(name, options, loader=False):
        jar = ROOT / f'services/{name}/target/offlinepay-{name}-0.1.0-SNAPSHOT.jar'
        if not jar.exists():
            raise RuntimeError(f'Build the service first: {jar}')
        output = (logs / (name + '.log')).open('w')
        handles.append(output)
        command = [args.java, '-Xms64m', '-Xmx256m', '-Duser.timezone=Asia/Kolkata']
        if loader:
            command += ['-Dloader.path=' + str(args.h2_jar.resolve()), '-cp', str(jar),
                        'org.springframework.boot.loader.launch.PropertiesLauncher']
        else:
            command += ['-jar', str(jar)]
        env = dict(os.environ)
        for key in ['CORE_URL', 'NOTIFICATIONS_URL', 'DISCOVERY_ENABLED', 'SPRING_PROFILES_ACTIVE',
                    'EUREKA_URL', 'WORKERS_ENABLED', 'NOTIFICATION_CONSUMERS_ENABLED']:
            env.pop(key, None)
        command += options + ['--logging.file.name=' + str(logs / (name + '-application.log'))]
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        processes.append(process)

    try:
        start('discovery', [f'--server.port={registry}', f'--eureka.client.service-url.defaultZone={base}/eureka/', '--eureka.instance.hostname=127.0.0.1'])
        wait_for(lambda: request(base+'/actuator/health')[0] == 200, 'Eureka health', processes)
        assert request(base+'/')[0] == 200, 'Eureka dashboard must respond'
        shared = [f'--eureka.client.service-url.defaultZone={base}/eureka/', '--eureka.client.registry-fetch-interval-seconds=1']
        start('notifications', shared + [f'--server.port={notices}',
              '--spring.datasource.url=jdbc:h2:mem:discovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
              '--spring.datasource.username=sa', '--spring.datasource.password=',
              '--spring.datasource.hikari.connection-init-sql=', '--spring.flyway.enabled=false',
              '--spring.jpa.hibernate.ddl-auto=create-drop', '--spring.kafka.listener.auto-startup=false',
              '--offlinepay.workers-enabled=false', '--offlinepay.internal-token=test-only-internal-token-32-characters'], loader=True)
        wait_for(lambda: request(f'http://127.0.0.1:{notices}/actuator/health')[0] == 200, 'Notification health', processes)
        start('gateway', shared+[f'--server.port={gateway}'])
        public = f'http://127.0.0.1:{gateway}'
        wait_for(lambda: request(public+'/actuator/health')[0] == 200, 'Gateway health', processes)
        assert request(public+'/api/v1/wallet')[0] == 503, 'Missing core must not fall back to a fixed host'
        assert request(public+'/api/v1/internal/session')[0] == 404
        instance = {'instance': {'instanceId': 'discovery-test-core', 'hostName': '127.0.0.1',
                    'app': 'OFFLINEPAY-CORE', 'ipAddr': '127.0.0.1', 'status': 'UP',
                    'port': {'$': core.server_port, '@enabled': 'true'},
                    'securePort': {'$': 443, '@enabled': 'false'},
                    'vipAddress': 'offlinepay-core', 'secureVipAddress': 'offlinepay-core',
                    'dataCenterInfo': {'@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo', 'name': 'MyOwn'}}}
        assert request(base+'/eureka/apps/OFFLINEPAY-CORE', 'POST', instance,
                       {'Content-Type': 'application/json'})[0] == 204
        wait_for(lambda: request(public+'/api/v1/wallet')[0] == 200,
                 'Gateway resolves registered core at a random port', processes)
        headers = {'Authorization': 'Bearer discovery-test-access-token'}
        wait_for(lambda: request(public+'/api/v1/notifications', headers=headers) == (200, '[]'),
                 'Gateway discovers notifications; real Feign discovers core and authenticates', processes)
        assert '/api/v1/internal/session' in observed
        assert request(public+'/api/v1/notifications', headers={'Authorization': 'Bearer invalid'})[0] == 401
        assert request(base+'/eureka/apps/OFFLINEPAY-GATEWAY')[0] == 200
        assert request(base+'/eureka/apps/OFFLINEPAY-NOTIFICATIONS')[0] == 200
        assert request(base+'/eureka/apps/OFFLINEPAY-CORE/discovery-test-core', 'DELETE')[0] == 200
        wait_for(lambda: request(public+'/api/v1/wallet')[0] == 503,
                 'Gateway reports503 after core deregistration and cache refresh', processes)
        wait_for(lambda: request(public+'/api/v1/notifications', headers=headers)[0] == 503,
                 'Feign identity lookup fails closed after core deregistration', processes)
        print('PASS: registry registration, routing, Feign identity, authorization and removal checks', flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
        core.shutdown(); core.server_close()
        for handle in handles:
            handle.close()
        print('Verification logs:', logs, flush=True)


if __name__ == '__main__':
    main()
