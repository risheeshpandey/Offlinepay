"""Exercise the real k6 CI wrapper against a fake loopback API.

Tests wiring/error classification/privacy only. Its timings are NEVER evidence
of OfflinePay capacity; this fixture has no PostgreSQL, Redis or Kafka.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path.endswith('/csrf'):
            self.reply(200, {'headerName': 'X-XSRF-TOKEN', 'token': 'FAKE_CSRF_SENTINEL'})
        else:
            self.business()

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path.endswith('/signup'):
            self.reply(201, {'walletId': '00000000-0000-0000-0000-000000000001'})
        elif self.path.endswith('/login'):
            self.reply(200, {'accessToken': 'FAKE_ACCESS_TOKEN_SENTINEL'})
        elif self.path.endswith('/topups'):
            self.reply(200, {'status': 'SUCCESS'})
        else:
            self.business()

    def business(self):
        time.sleep(0.005)
        self.server.requests += 1
        if self.server.fail and self.server.requests % 10 == 0:
            self.reply(503, {'code': 'RATE_LIMIT_UNAVAILABLE', 'message': 'FAKE_BODY_SECRET_SENTINEL'})
        else:
            self.reply(200, {'status': 'SUCCESS'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--k6', default='k6')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    server.fail, server.requests = False, 0
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix='ci-wrapper-check-') as folder:
            import os
            for fail in (False, True):
                server.fail, server.requests = fail, 0
                summary = Path(folder)/'summary.json'
                env = dict(os.environ, ALLOW_DEMO_LOAD='yes', BASE_URL=f'http://127.0.0.1:{server.server_port}',
                           VUS='1', DURATION='2s', RUN_ID='ci-wiring-fixture', SUMMARY=str(summary))
                env.pop('THINK_TIME', None)
                result = subprocess.run([args.k6, 'run', str(ROOT/'benchmarks/ci-mixed.js')], cwd=ROOT,
                                        env=env, capture_output=True, text=True, timeout=35)
                report = json.loads(summary.read_text())
                assert result.returncode == (99 if fail else 0), result.returncode
                combined = summary.read_text() + result.stdout + result.stderr
                assert 'SENTINEL' not in combined and 'setup_data' not in combined
                counters = report['metrics']
                assert counters['transfers_accepted']['values']['count'] > 0
                assert report['offlinepayMetadata']['businessWindowSeconds'] >= 1.9
                if fail:
                    assert counters['error_rate_limit_unavailable']['values']['count'] == counters['response_503']['values']['count'] > 0
                print('Real-k6 wrapper check passed: '+('503 classification and redaction' if fail else 'healthy loop and redaction'))
    finally:
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
