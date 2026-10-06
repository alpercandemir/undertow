#!/usr/bin/env python3
"""Create pinned Git snapshots and replay responses. No credentials or repository publication."""
import argparse,json,subprocess,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def git(repo,*args):
 return subprocess.check_output(['git','-c','user.name=Undertow Demo','-c','user.email=demo@undertow.invalid','-C',str(repo),*args],text=True).strip()
def prepare(case_id,destination):
 case=ROOT/'evals/cases'/case_id
 label=json.loads((ROOT/'evals/expected'/f'{case_id}.json').read_text())
 if destination.exists(): raise ValueError('Use a new destination to preserve existing artifacts')
 destination.mkdir(parents=True)
 for item in ['config','CODING-SKILL.md']:
  src=ROOT/item; dst=destination/item
  shutil.copytree(src,dst) if src.is_dir() else shutil.copyfile(src,dst)
 (destination/'src').mkdir()
 path=label.get('source_path','src/Subject.java')
 (destination/path).parent.mkdir(parents=True,exist_ok=True)
 suffix=Path(path).suffix
 shutil.copyfile(case/('before'+suffix),destination/path)
 git(destination,'init','-q');git(destination,'add','.')
 git(destination,'commit','-qm','Base contract');base=git(destination,'rev-parse','HEAD')
 shutil.copyfile(case/('after'+suffix),destination/path)
 git(destination,'add','.');git(destination,'commit','-qm',case_id);head=git(destination,'rev-parse','HEAD')
 count=len((destination/path).read_text().split('\n'))
 calls=[{'name':'get_diff','arguments':{}},
  {'name':'read_source','arguments':{'snapshot':'head','path':path,'start_line':1,'end_line':count}},
  {'name':'read_source','arguments':{'snapshot':'base','path':path,'start_line':1,'end_line':len((case/('before'+suffix)).read_text().split('\n'))}},
  {'name':'get_rule','arguments':{'id':label['rule_id']}},
  {'name':'get_business_context','arguments':{}}]
 findings=[]
 if label['unsafe']:
  findings=[dict(id=case_id,title=label['rule_id']+': '+case_id,language='java',severity=label['severity'],category=label['category'],
   confidence='HIGH',confidence_explanation='The designed fixture has an explicit contract and a causal edit. This is authored replay output.',
   evidence_status='INFERRED',guideline_label='BLOCKER' if label['severity']=='CRITICAL' else 'MAJOR',rule_ids=[label['rule_id']],
   location={'commit':head,'path':path,'snapshot':'head','start_line':label.get('finding_line',6),'end_line':label.get('finding_line',6)},trigger=label['trigger'],
   changed_behavior='The changed expression replaces the baseline contract-preserving behavior.',technical_consequence=label['consequence'],
   business_consequence='The declared order/payment invariant may fail.',assumptions=['Collaborators obey the fixture contract; symbol resolution is unavailable.'],
   evidence_refs=['e1','e2','e3','e4','e5'],recommended_change='Restore the contract-preserving baseline behavior.',
   regression_tests=[dict(setup='Controlled gateway/repository and explicit decimal strings.',stimulus=label['trigger'],expected_outcome=label['regression'],level='integration',status='proposed')])]
 if label['rule_id']=='JAVA-DEPENDENCY-001':calls.append({'name':'compare_dependencies','arguments':{}})
 review=dict(findings=findings,summary='Authored offline replay for '+case_id+'; live model quality is not measured.',coverage_notes=['Classpath symbols in this synthetic fixture are unresolved.'])
 replay=[{'calls':calls},{'review':review}]
 (destination/'replay.json').write_text(json.dumps(replay,indent=2)+'\n')
 diff_review=json.loads(json.dumps(review))
 for f in diff_review['findings']:f['evidence_refs']=['e1','e2']
 (destination/'diff-replay.json').write_text(json.dumps([{'review':diff_review}],indent=2)+'\n')
 metadata=dict(repo=str(destination.resolve()),base=base,head=head,replay=str((destination/'replay.json').resolve()),label=label)
 (destination/'demo.json').write_text(json.dumps(metadata,indent=2)+'\n')
 return metadata
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--case',default='retry-key-unsafe');parser.add_argument('--output',type=Path,required=True)
 args=parser.parse_args();print(json.dumps(prepare(args.case,args.output.resolve()),indent=2))
