#!/usr/bin/env python3
import argparse,json,os,time,urllib.request,urllib.error
from pathlib import Path
from run import stats

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--url',default='http://127.0.0.1:18087/api/v1/retrieval');ap.add_argument('--out',type=Path,required=True);ap.add_argument('--rounds',type=int,default=10);a=ap.parse_args()
    a.out.mkdir(parents=True,exist_ok=False)
    qs=json.loads((Path(__file__).parent/'fixtures/queries.json').read_text());rows=[]
    token=os.environ['AME_BENCH_TOKEN'];opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def call(body,auth=token):
        request=urllib.request.Request(a.url,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+auth,'Content-Type':'application/json'})
        t=time.perf_counter()
        try:
            with opener.open(request,timeout=30) as res:status=res.status;raw=res.read()
        except urllib.error.HTTPError as e:status=e.code;raw=e.read()
        elapsed=(time.perf_counter()-t)*1000
        return status,json.loads(raw),elapsed
    base={'dataset_ids':['ame-yingmu'],'page_size':5,'similarity_threshold':0,'question':qs[0]['question']}
    # Prevent silent removal of the user's MaaS rerank requirement.
    checks=[]
    for label,body,auth,want in [('auth',base,'bad',401),('rerank',{**base,'rerank_id':'enabled'},token,400),('dataset',{**base,'dataset_ids':['wrong']},token,400),('threshold',{**base,'similarity_threshold':.2},token,400)]:
        status,response,_=call(body,auth);assert status==want,(label,status);checks.append({'check':label,'status':status,'response':response})
    for q in qs:
        status,_,_=call({**base,'question':q['question']});assert status==200
    for repeat in range(a.rounds):
        for q in qs:
            status,res,elapsed=call({**base,'question':q['question']});assert status==200 and res['code']==0
            hits=res['data']['chunks'];row={'round':repeat,**q,'http_status':status,'e2e_ms':elapsed,'response':res,'top1_correct':bool(hits) and hits[0]['document_keyword']==q['expected_document'],'hit5':any(h['document_keyword']==q['expected_document'] for h in hits)};rows.append(row)
            with (a.out/'samples.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
    summary={'url':a.url,'warmup_requests':len(qs),'concurrency':1,'connection':'new TCP each request; HTTP loopback','e2e':stats([r['e2e_ms'] for r in rows]),'ame_recall':stats([r['response']['meta']['ame_recall_ms'] for r in rows]),'unique_questions':len(qs),'top1_correct':sum(r['top1_correct'] for r in rows if r['round']==0),'hit5':sum(r['hit5'] for r in rows if r['round']==0),'checks':checks}
    (a.out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(summary,ensure_ascii=False))
if __name__=='__main__':main()
