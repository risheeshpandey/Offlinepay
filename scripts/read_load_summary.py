"""Read k6 handleSummary output; never infer maximum capacity from a short run."""
import argparse
import json
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('summary',type=Path)
a=p.parse_args()
data=json.loads(a.summary.read_text(encoding='utf-8-sig'))
meta=data.get('offlinepayMetadata',{})
print('Run:',meta.get('runId','unknown'))
print('VUs:',meta.get('vus','unknown'),'Configured duration:',meta.get('duration','unknown'))
print('Workload:',meta.get('workload','unknown'))
for name in ['business_requests','transfers_accepted','rate_limited','server_errors','http_req_failed','business_failures']:
 if name in data['metrics']:
  values=data['metrics'][name]['values']
  print(name, str(round(values['rate']*100,3))+'%' if name in ['http_req_failed','business_failures'] else values)
for name in ['wallet_read_ms','transfer_ms']:
 if name in data['metrics']: print(name, '(milliseconds)',data['metrics'][name]['values'])
failed=[name+': '+rule for name,metric in data['metrics'].items()
        for rule,result in metric.get('thresholds',{}).items() if not result['ok']]
print('Thresholds:', 'FAIL: '+', '.join(failed) if failed else 'PASS (for the thresholds present in this file)')
print('Not a maximum-capacity claim. Record hardware, Java version, DB, rate-limit and tracing settings; reconcile both databases.')
