"""Compare saved retrieval responses; never treats document match as answer accuracy."""
import gzip,json,hashlib,statistics,math,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/ame-ragflow-comparison-20261026';OUT.mkdir(exist_ok=True)
SOURCES={'ragflow32':ROOT.parent/'ragflow-ecs-20261021/ragflow-paced60-20261021/samples.jsonl','ragflow8':ROOT.parent/'ragflow-ecs-20261021/ragflow-paced60-k8-20261021/samples.jsonl','ame':ROOT/'artifacts/ame-kb-20261026/http-20261026/samples.jsonl.gz'}
allrows={};summaries={};hashes={}
for name,p in SOURCES.items():
 hashes[Path(os.path.relpath(p,ROOT)).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
 with (gzip.open(p,'rt') if p.suffix=='.gz' else p.open()) as f:rows=[json.loads(l) for l in f]
 unique={};variants={}
 for r in rows:
  q=r.get('query_id',r.get('id'));cs=r['response']['data']['chunks'][:5]
  variants.setdefault(q,set()).add(tuple(c['id'] for c in cs))
  if q not in unique:unique[q]={'question':r['question'],'expected':r['expected_document'],'chunks':cs}
 assert len(unique)==20
 allrows[name]=unique
 chars=[sum(len(c['content']) for c in r['chunks']) for r in unique.values()]
 correct=sum(c['document_keyword']==r['expected'] for r in unique.values() for c in r['chunks'])
 total=sum(len(r['chunks']) for r in unique.values())
 times=sorted(r['e2e_ms'] for r in rows)
 summaries[name]={'requests':len(rows),'unique_questions':len(unique),'top1_document_hits':sum(bool(r['chunks']) and r['chunks'][0]['document_keyword']==r['expected'] for r in unique.values()),'top5_document_hits':sum(any(c['document_keyword']==r['expected'] for c in r['chunks']) for r in unique.values()),'nonempty_questions':sum(bool(r['chunks']) for r in unique.values()),'returned_chunks':total,'expected_document_chunks':correct,'document_purity':correct/total,'mean_context_chars':statistics.mean(chars),'max_context_chars':max(chars),'p50_ms':times[math.ceil(.5*len(times))-1],'p95_ms':times[math.ceil(.95*len(times))-1],'max_ms':max(times),'under100_requests':sum(t<100 for t in times),'ranking_variants_per_question':{str(k):len(v) for k,v in variants.items()}}
# Explicit snippets for targeted inspection, not a full relevance gold set.
anchors={0:['河北博物院'],2:['满城'],4:['铜质鎏金','青铜鎏金'],5:['1974'],11:['不意味着每件器物恰好只有三种颜色','不一定；这是彩色低温釉陶'],13:['釉陶'],16:['商代后期']}
comparison=[]
for q in range(20):
 row={'query_id':q,'question':allrows['ame'][q]['question'],'schemes':{}}
 for name,rs in allrows.items():
  r=rs[q];assert r['question']==row['question']
  cs=r['chunks'];hits=[]
  if q in anchors:
   for rank,c in enumerate(cs,1):
    if c['document_keyword']==r['expected'] and any(a in c['content'] for a in anchors[q]):hits.append(rank)
  row['schemes'][name]={'ids':[c['id'] for c in cs],'documents':[c['document_keyword'] for c in cs],'context_chars':sum(len(c['content']) for c in cs),'anchor_first_rank':min(hits) if hits else None,'anchor_checked':q in anchors}
 comparison.append(row)
result={'scope':'Archived retrieval only; different hosts/dates. Document purity is not relevance precision. Anchors are exploratory exact text checks, not answer accuracy.','summary':summaries,'answer_anchor_strings':anchors,'questions':comparison,'source_sha256':hashes}
(OUT/'comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(summaries,ensure_ascii=False,indent=2))
for r in comparison:
 if r['query_id'] in anchors or any(not x['ids'] for x in r['schemes'].values()):print(r['query_id'],r['question'],{k:(v['anchor_first_rank'],len(v['ids'])) for k,v in r['schemes'].items()})
