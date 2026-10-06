#!/usr/bin/env python3
"""Record actual offline CLI/report output as an asciicast v2 terminal screencast."""
import argparse,json,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--java',default='java')
a=p.parse_args();root=Path(__file__).resolve().parents[1];events=[];started=time.monotonic()
def emit(text):events.append([round(time.monotonic()-started,3),'o',text.replace('\n','\r\n')])
cmd=[a.java,'-jar',str(root/'target/undertow.jar'),'review','--repo',str(a.repo),'--base','HEAD~1','--head','HEAD','--replay',str(a.repo/'replay.json'),'--output',str(a.report)]
emit('$ java -jar undertow.jar review --repo demo --base HEAD~1 --head HEAD --replay replay.json\n')
result=subprocess.run(cmd,capture_output=True,text=True,check=True)
emit(result.stdout.replace(str(a.report.resolve()), '.undertow/demo-review'))
emit('\nObserved tool dispatch (sanitized trace):\n')
for line in (a.report/'trace.jsonl').read_text().splitlines():
 event=json.loads(line)
 if event['stage']=='tool':emit(f"  {event['name']}: {event['outcome']} evidence={','.join(event['evidence_ids'])}\n")
emit('\nValidated advisory report — authored replay, not live-model accuracy:\n')
emit((a.report/'report.md').read_text())
a.output.parent.mkdir(parents=True,exist_ok=True)
header=dict(version=2,width=120,height=44,timestamp=int(time.time()),title='Undertow: offline payment retry investigation')
a.output.write_text(json.dumps(header)+'\n'+'\n'.join(json.dumps(e) for e in events)+'\n')
