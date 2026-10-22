"""Isolated M6 functional smoke, NOT a PostgreSQL capacity benchmark.
Build the JARs first. Requires H2 test JAR, Redis executable and optional k6 executable.
Uses random loopback ports, disposable H2 data, freshly generated signing keys and a
local Zipkin-format collector. Kafka delivery is covered separately, not here.
No user .env, database, wallet, keys or Docker volumes are accessed.
"""
import argparse
import base64
import gzip
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import build_opener, ProxyHandler, HTTPCookieProcessor, Request
from urllib.error import HTTPError
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from verify_eureka_routing import port, wait_for

ROOT = Path(__file__).resolve().parents[1]
spans = []
HTTP = build_opener(ProxyHandler({}), HTTPCookieProcessor(CookieJar()))


def request(url, method='GET', body=None, headers=None):
    req = Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                  headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with HTTP.open(req, timeout=10) as r: return r.status, r.read().decode()
    except HTTPError as e: return e.code, e.read().decode()
    except (OSError, TimeoutError): return 0, ''


class Collector(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_POST(self):
        payload = self.rfile.read(int(self.headers['Content-Length']))
        if self.headers.get('Content-Encoding') == 'gzip': payload = gzip.decompress(payload)
        spans.extend(json.loads(payload))
        self.send_response(202); self.end_headers()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--h2-jar', type=Path, default=Path.home()/'.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar')
    parser.add_argument('--redis', required=True)
    parser.add_argument('--k6')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    processes, handles = [], []
    ports = {name: port() for name in ['discovery','core','notifications','gateway','redis','m-discovery','m-core','m-notifications','m-gateway']}
    collector = ThreadingHTTPServer(('127.0.0.1', 0), Collector)
    threading.Thread(target=collector.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='offlinepay-m6-') as directory:
        private = Ed25519PrivateKey.generate()
        keys = Path(directory)
        (keys/'private-key.txt').write_text(base64.b64encode(private.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())).decode())
        (keys/'private-key.txt').chmod(0o600)
        (keys/'public-key.txt').write_text(base64.b64encode(private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode())
        env = dict(os.environ)
        for k in ['CORE_URL','NOTIFICATIONS_URL','DISCOVERY_ENABLED','JAVA_TOOL_OPTIONS','SPRING_APPLICATION_JSON']:
            env.pop(k, None)
        env.update(JWT_SECRET_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                   INTERNAL_SERVICE_TOKEN=secrets.token_urlsafe(40), REDIS_PORT=str(ports['redis']),
                   EUREKA_URL=f"http://127.0.0.1:{ports['discovery']}/eureka/",
                   ZIPKIN_ENDPOINT=f'http://127.0.0.1:{collector.server_port}/api/v2/spans',
                   TRACE_SAMPLE_PROBABILITY='1.0', AUTH_RATE_LIMIT='10000', API_RATE_LIMIT='100000',
                   OFFLINE_SIGNING_PRIVATE_KEY_PATH=str(keys/'private-key.txt'),
                   OFFLINE_SIGNING_PUBLIC_KEY_PATH=str(keys/'public-key.txt'))
        def launch(name, command):
            handle = (args.output/(name+'-console.log')).open('w');handles.append(handle)
            p = subprocess.Popen(command, cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT)
            processes.append(p)
        def start(name, extra=(), h2=False):
            jar = ROOT/('target/offlinepay-0.1.0-SNAPSHOT.jar' if name=='core' else f'services/{name}/target/offlinepay-{name}-0.1.0-SNAPSHOT.jar')
            command=['java','-Xms64m','-Xmx384m' if name=='core' else '-Xmx256m','-Duser.timezone=Asia/Kolkata']
            if h2: command += ['-Dloader.path='+str(args.h2_jar.resolve()),'-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher']
            else: command += ['-jar',str(jar)]
            command += [f'--server.port={ports[name]}',f'--management.server.port={ports["m-"+name]}',
                        '--spring.profiles.active='+('demo,split,observe' if name=='core' else 'observe'),
                        '--logging.file.name='+str(args.output/(name+'.json.log')),
                        '--eureka.client.registry-fetch-interval-seconds=1', *extra]
            launch(name,command)
            wait_for(lambda: request(f'http://127.0.0.1:{ports["m-"+name]}/actuator/health/liveness')[0]==200,
                     name+' liveness',processes,90)
        try:
            launch('redis',[args.redis,'--bind','127.0.0.1','--port',str(ports['redis']),'--save','','--appendonly','no'])
            start('discovery')
            h2=['--spring.datasource.url=jdbc:h2:mem:m6;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000',
                '--spring.datasource.username=sa','--spring.datasource.password=',
                '--spring.datasource.hikari.connection-init-sql=','--spring.flyway.enabled=false',
                '--offlinepay.workers-enabled=false','--spring.kafka.listener.auto-startup=false']
            start('core',h2+['--spring.sql.init.mode=always', '--spring.sql.init.schema-locations=file:'+str(ROOT/'src/test/resources/schema-test.sql'),
                            '--offlinepay.messaging.enabled=false'],True)
            start('notifications',h2+['--spring.jpa.hibernate.ddl-auto=create-drop'],True)
            start('gateway')
            base=f'http://127.0.0.1:{ports["gateway"]}'
            wait_for(lambda: request(base+'/api/v1/auth/csrf')[0]==200,'Gateway discovers core',processes,40)
            def user(name,number):
                payload={'name':name,'mobileNumber':number,'password':'SmokePassword123!'}
                status,body=request(base+'/api/v1/auth/csrf');assert status==200
                csrf=json.loads(body);headers={csrf['headerName']:csrf['token']}
                status,body=request(base+'/api/v1/auth/signup','POST',payload,headers);assert status==201,(status,body)
                signup=json.loads(body)
                csrf=json.loads(request(base+'/api/v1/auth/csrf')[1])
                status,body=request(base+'/api/v1/auth/login','POST',payload,{csrf['headerName']:csrf['token']});assert status==200
                return signup,json.loads(body)['accessToken']
            alice,token=user('Smoke Alice','8551111111');bob,_=user('Smoke Bob','8551111112')
            headers={'Authorization':'Bearer '+token,'Idempotency-Key':'m6-smoke-topup'}
            assert request(base+'/api/v1/demo/topups','POST',{'amount':100},headers)[0]==200
            headers['Idempotency-Key']='m6-smoke-transfer'
            body={'receiverWalletId':bob['walletId'],'amount':1}
            result=request(base+'/api/v1/payments/transfer','POST',body,headers);assert result[0]==200
            assert request(base+'/api/v1/payments/transfer','POST',body,headers)==result
            headers['Idempotency-Key']='m6-smoke-rejected'
            assert request(base+'/api/v1/payments/transfer','POST',{**body,'amount':101},headers)[0]==422
            probe_id='m6-trace-check-001'
            headers['X-Request-ID']=probe_id
            wait_for(lambda: request(base+'/api/v1/notifications',headers=headers)==(200,'[]'),
                     'Gateway -> notifications -> Feign -> core',processes,40)
            for name in ['core','notifications','gateway','discovery']:
                assert request(f'http://127.0.0.1:{ports["m-"+name]}/actuator/health/readiness')[0]==200
                status,metrics=request(f'http://127.0.0.1:{ports["m-"+name]}/actuator/prometheus')
                assert status==200 and 'jvm_memory_used_bytes' in metrics
                assert 'http_server_requests_seconds_bucket' in metrics
                (args.output/(name+'.prom')).write_text(metrics)
                assert request(f'http://127.0.0.1:{ports["m-"+name]}/actuator/env')[0]!=200
            metrics=(args.output/'core.prom').read_text()
            transfer_lines=[s for s in metrics.splitlines() if s.startswith('offlinepay_payment_committed_total') and 'kind="TRANSFER"' in s]
            assert len(transfer_lines)==1 and float(transfer_lines[0].split()[-1])==1,transfer_lines
            assert request(base+'/actuator/prometheus')[0]==404
            assert request(f'http://127.0.0.1:{ports["core"]}/actuator/prometheus')[0]!=200
            wait_for(lambda: any(s.get('localEndpoint',{}).get('serviceName')=='offlinepay-notifications' for s in spans),
                     'Zipkin-format spans exported',processes,20)
            matching=[]
            for line in (args.output/'core.json.log').read_text().splitlines():
                record=json.loads(line)
                if record.get('requestId')==probe_id:
                    assert record['@timestamp'].endswith('+05:30')
                    if record.get('traceId'):matching.append(record['traceId'])
            assert matching,'Core request log must contain propagated requestId and traceId'
            trace=matching[-1]
            wait_for(lambda: {'offlinepay-gateway','offlinepay-notifications','offlinepay-core'}.issubset(
                {s.get('localEndpoint',{}).get('serviceName') for s in spans if s['traceId']==trace}),
                'One trace ID across Gateway, notifications and core',processes,20)
            (args.output/'trace-evidence.json').write_text(json.dumps([s for s in spans if s['traceId']==trace],indent=2))
            if args.k6:
                load_env={**env,'ALLOW_DEMO_LOAD':'yes','BASE_URL':base,'RUN_ID':'m6-functional','VUS':'1','DURATION':'10s',
                          'THINK_TIME':'0.15','SUMMARY':str(args.output/'functional-k6.json')}
                subprocess.run([args.k6,'run','benchmarks/mixed.js'],cwd=ROOT,env=load_env,check=True)
                exported=json.loads((args.output/'functional-k6.json').read_text())
                assert set(exported)=={'root_group','state','metrics','offlinepayMetadata'}, 'Unexpected report fields'
                replay_env={**load_env,'RUN_ID':'m6-functional-replay'}
                subprocess.run([args.k6,'run','benchmarks/replay.js'],cwd=ROOT,env=replay_env,check=True)
            # Dependency failure changes readiness, not liveness (avoid restart storms).
            processes[0].terminate(); processes[0].wait(timeout=10)
            wait_for(lambda: request(f'http://127.0.0.1:{ports["m-core"]}/actuator/health/readiness')[0]==503,
                     'Redis outage makes core unready',processes[1:],10)
            assert request(f'http://127.0.0.1:{ports["m-core"]}/actuator/health/liveness')[0]==200
            for path in args.output.glob('*.json.log'):
                text=path.read_text()
                assert token not in text and 'SmokePassword123!' not in text and env['JWT_SECRET_BASE64'] not in text
            (args.output/'verification.json').write_text(json.dumps({'status':'PASS','scope':'Functional only: H2, real Redis, four JARs, Eureka, Zipkin-format collector',
                  'postgresCapacity':'NOT MEASURED','kafkaDelivery':'Not exercised by this smoke',
                  'traceServices':['offlinepay-gateway','offlinepay-notifications','offlinepay-core'],
                  'committedTransferCounter':1,'idempotentReplayCounterIncrease':0,'rejectedTransferCounterIncrease':0},indent=2))
            print('PASS: M6 metrics, cross-service trace, JSON/IST logs, commit-only counter and isolated k6 smoke',flush=True)
        finally:
            for p in reversed(processes):p.terminate()
            for p in reversed(processes):
                try:p.wait(timeout=15)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            for h in handles:h.close()
            collector.shutdown();collector.server_close()

if __name__=='__main__':main()
