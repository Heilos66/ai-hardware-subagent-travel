#!/usr/bin/env python3
"""Exploratory paraphrase/OOD probe, separate from the original 20-question set."""
import argparse,json,os
from pathlib import Path
from run import Worker
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--binary',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
f=Path(__file__).parent/'fixtures';qs=json.loads((f/'probes.json').read_text());summary={}
os.environ['AME_CHEAP_MODEL']=str((a.source/'android/app/src/main/assets/cheap_model.json').resolve())
modes=[('graph-dict','graph',a.source/'lexicon/lexicon_src/segmentation/merged.txt')]
if (a.source/'mem').exists():modes += [('graph-no-dict','graph','-'),('lmem','lmem','-')]
else:modes += [('graph-cheap','graph-cheap',a.source/'lexicon/lexicon_src/segmentation/merged.txt')]
for name,mode,d in modes:
 with (a.out/(name+'.log')).open('w') as log:
  w=Worker(a.binary.resolve(),f/'corpus.json',d,mode,a.out/'dict.cache',log)
  try:rs=[{**q,**w.query(q['question'])} for q in qs]
  finally:w.close()
 (a.out/(name+'.json')).write_text(json.dumps(rs,ensure_ascii=False,indent=2))
 known=[r for r in rs if r['expected_document']]
 summary[name]={'known_questions':len(known),'top1_document_hits':sum(bool(r['hits']) and r['hits'][0]['document']==r['expected_document'] for r in known),'top5_document_hits':sum(any(h['document']==r['expected_document'] for h in r['hits']) for r in known),'out_of_scope_returned_counts':{r['id']:len(r['hits']) for r in rs if not r['expected_document']}}
(a.out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(summary,ensure_ascii=False))
