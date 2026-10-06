#!/usr/bin/env python3
"""Compile Java fixture pairs with a controlled classpath; never use this on arbitrary PR source with secrets."""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--javac',default='javac');p.add_argument('--classpath',required=True);p.add_argument('--output',type=Path,default=Path('.undertow/fixture-classes'))
a=p.parse_args();root=Path(__file__).resolve().parents[1];count=0
for case in (root/'evals/expected').glob('*.json'):
 label=json.loads(case.read_text())
 if not label.get('source_path','src/Subject.java').endswith('.java'):continue
 for part in ['before','after']:
  dest=a.output/label['id']/part;dest.mkdir(parents=True,exist_ok=True)
  subprocess.run([a.javac,'--release','21','-cp',a.classpath,'-d',str(dest),str(root/'evals/cases'/label['id']/(part+'.java'))],check=True)
  count+=1
print(f'Compiled {count} fixture sources')
