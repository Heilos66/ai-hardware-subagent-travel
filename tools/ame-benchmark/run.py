#!/usr/bin/env python3
"""Offline same-corpus AME test. No remote model calls; no answer cache."""
import argparse,os,hashlib,json,math,platform,random,subprocess,time
from pathlib import Path

def percentile(values,p):return sorted(values)[math.ceil(len(values)*p)-1] if values else None

def stats(v):return {'n':len(v),'p50_ms':percentile(v,.5),'p95_ms':percentile(v,.95),'max_ms':max(v) if v else None}

class Worker:
    def __init__(self,binary,corpus,dict_path,mode,cache,log):
        t=time.perf_counter()
        self.p=subprocess.Popen([str(binary),str(corpus),str(dict_path),mode,str(cache)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
        line=self.p.stdout.readline()
        if not line:raise RuntimeError('worker startup failed; inspect stderr log')
        self.ready=json.loads(line);self.ready['process_startup_ms']=(time.perf_counter()-t)*1000
    def query(self,text,limit=5):
        t=time.perf_counter()
        self.p.stdin.write(json.dumps({'query':text,'limit':limit},ensure_ascii=False)+'\n');self.p.stdin.flush()
        line=self.p.stdout.readline()
        if not line:raise RuntimeError('worker exited')
        result=json.loads(line);result['worker_roundtrip_ms']=(time.perf_counter()-t)*1000
        if 'error' in result:raise RuntimeError(result['error'])
        return result
    def close(self):
        if self.p.poll() is None:self.p.stdin.close();self.p.wait(timeout=10)

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source',type=Path,required=True)
    ap.add_argument('--binary',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--modes',nargs='+',choices=['graph-dict','graph-no-dict','graph-cheap','lmem'])
    ap.add_argument('--rounds',type=int,default=50)
    a=ap.parse_args()
    if a.rounds<1:ap.error('rounds must be positive')
    a.out.mkdir(parents=True,exist_ok=False)
    fixture=Path(__file__).resolve().parent/'fixtures';corpus=fixture/'corpus.json';qs=json.loads((fixture/'queries.json').read_text())
    sha=subprocess.check_output(['git','-C',str(a.source),'rev-parse','HEAD'],text=True).strip()
    manifest={'ame_revision':sha,'platform':platform.platform(),'machine':platform.machine(),'processor':platform.processor(),
      'rounds':a.rounds,'queries_per_round':len(qs),'concurrency':1,'remote_models':False,'css_online':False,
      'graph_settings':{'lex_topk':30,'soft_min_sim':.2,'depth':2,'limit':5},
      'input_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [corpus,fixture/'queries.json']},
      'dictionary_sha256':hashlib.sha256((a.source/'lexicon/lexicon_src/segmentation/merged.txt').read_bytes()).hexdigest()}
    (a.out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    os.environ['AME_CHEAP_MODEL']=str((a.source/'android/app/src/main/assets/cheap_model.json').resolve())
    modes=a.modes or (['graph-dict','graph-no-dict','lmem'] if (a.source/'mem').exists() else ['graph-dict','graph-cheap'])
    manifest['modes']=modes
    model=a.source/'android/app/src/main/assets/cheap_model.json'
    manifest['cheap_model_sha256']=hashlib.sha256(model.read_bytes()).hexdigest() if model.exists() else None
    (a.out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    summaries={}
    for name in modes:
        mode='graph-cheap' if name=='graph-cheap' else ('lmem' if name=='lmem' else 'graph')
        dict_path=a.source/'lexicon/lexicon_src/segmentation/merged.txt' if name in ['graph-dict','graph-cheap'] else Path('-')
        with (a.out/(name+'-stderr.log')).open('w') as log:
            worker=Worker(a.binary.resolve(),corpus,dict_path,mode,a.out/(name+'-dict.cache'),log)
            rows=[];warm=[]
            try:
                for q in qs:warm.append({'query_id':q['id'],**worker.query(q['question'])})
                with (a.out/(name+'-samples.jsonl')).open('w') as f:
                    for repeat in range(a.rounds):
                        order=list(qs);random.Random(20261026+repeat).shuffle(order)
                        for q in order:
                            r=worker.query(q['question']);hits=r['hits']
                            row={'round':repeat,**q,**r,'top1_correct':bool(hits) and hits[0]['document']==q['expected_document'],
                                 'hit5':any(h['document']==q['expected_document'] for h in hits),'nonempty':bool(hits)}
                            rows.append(row);f.write(json.dumps(row,ensure_ascii=False)+'\n')
            finally:worker.close()
        (a.out/(name+'-warmup.json')).write_text(json.dumps(warm,ensure_ascii=False))
        first=[r for r in rows if r['round']==0]
        summaries[name]={'startup':worker.ready,'recall':stats([r['recall_ms'] for r in rows]),'worker_roundtrip':stats([r['worker_roundtrip_ms'] for r in rows]),
            'unique_questions':len(first),'top1_correct':sum(r['top1_correct'] for r in first),'hit5':sum(r['hit5'] for r in first),'nonempty':sum(r['nonempty'] for r in first),
            'top1_misses':[r['id'] for r in first if not r['top1_correct']], 'hit5_misses':[r['id'] for r in first if not r['hit5']]}
        print(name,json.dumps(summaries[name],ensure_ascii=False),flush=True)
    (a.out/'summary.json').write_text(json.dumps(summaries,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
