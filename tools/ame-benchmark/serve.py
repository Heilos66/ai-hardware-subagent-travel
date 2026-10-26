#!/usr/bin/env python3
"""Loopback-only experimental RAGFlow-compatible AME retrieval adapter."""
import argparse,hmac,json,os,threading,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from run import Worker

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--binary',type=Path,required=True)
    ap.add_argument('--source',type=Path,required=True)
    ap.add_argument('--port',type=int,default=18087)
    ap.add_argument('--state',type=Path,default=Path('target/ame-adapter'))
    ap.add_argument('--mode',choices=['graph','graph-cheap'],default=os.environ.get('AME_RETRIEVAL_MODE','graph'))
    a=ap.parse_args();token=os.environ.get('AME_BENCH_TOKEN','')
    if not token:ap.error('set AME_BENCH_TOKEN; never commit it')
    a.state.mkdir(parents=True,exist_ok=True)
    log=(a.state/'worker.log').open('a')
    worker=Worker(a.binary.resolve(),Path(__file__).resolve().parent/'fixtures/corpus.json',a.source/'lexicon/lexicon_src/segmentation/merged.txt',a.mode,a.state/'dict.cache',log)
    lock=threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def send_json(self,status,obj):
            data=json.dumps(obj,ensure_ascii=False).encode()
            self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        def do_GET(self):
            self.send_json(200 if self.path=='/health' else 404,{'ready':self.path=='/health','backend':'ame-'+a.mode+'-local','css_online':False,'maas_rerank':False})
        def do_POST(self):
            start=time.perf_counter()
            if self.path!='/api/v1/retrieval':return self.send_json(404,{'code':404})
            if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):return self.send_json(401,{'code':401})
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=16384:raise ValueError('invalid content length')
                req=json.loads(self.rfile.read(length));query=req.get('question');limit=req.get('page_size',5)
                if not isinstance(query,str) or not query.strip() or len(query)>1000:raise ValueError('invalid question')
                if type(limit) is not int or not 1<=limit<=52:raise ValueError('invalid page_size')
                if req.get('dataset_ids')!=['ame-yingmu']:raise ValueError('dataset_ids must be [ame-yingmu]')
                if req.get('rerank_id'):raise ValueError('MaaS rerank unsupported here; set RAGFLOW_USE_RERANK=false explicitly')
                if req.get('similarity_threshold',0)!=0:raise ValueError('AME score is not bge similarity; set similarity_threshold=0')
                with lock:r=worker.query(query,limit)
                chunks=[{'id':h['id'],'content':h['content'],'document_keyword':h['document'],'similarity':h['score']} for h in r['hits']]
                self.send_json(200,{'code':0,'data':{'chunks':chunks},'meta':{'backend':'ame-'+a.mode+'-local','css_online':False,'maas_rerank':False,'ame_recall_ms':r['recall_ms'],'adapter_ms':(time.perf_counter()-start)*1000}})
            except (ValueError,TypeError,AttributeError) as e:self.send_json(400,{'code':400,'message':str(e)})
            except RuntimeError:self.send_json(502,{'code':502,'message':'AME worker failed'})
    server=None
    try:
        server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler)
        print(json.dumps({'url':f'http://127.0.0.1:{a.port}/api/v1/retrieval','startup':worker.ready}),flush=True)
        server.serve_forever()
    finally:
        if server:server.server_close()
        worker.close();log.close()
if __name__=='__main__':main()
