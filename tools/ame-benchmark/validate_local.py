#!/usr/bin/env python3
"""Exercise real AME 3.0 HTTP adapters and the existing Java client, without MaaS."""
import argparse,json,os,secrets,signal,subprocess,time,urllib.request
from pathlib import Path

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--source',type=Path,required=True)
    ap.add_argument('--binary',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--port',type=int,default=18089)
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=False)
    root=Path(__file__).resolve().parents[2];harness=Path(__file__).resolve().parent
    env=os.environ.copy();env['AME_BENCH_TOKEN']=secrets.token_urlsafe(32)
    env['AME_CHEAP_MODEL']=str((a.source/'android/app/src/main/assets/cheap_model.json').resolve())
    url=f'http://127.0.0.1:{a.port}'
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for mode in ['graph','graph-cheap']:
        with (a.out/(mode+'-server.log')).open('w') as log:
            server=subprocess.Popen(['python3',str(harness/'serve.py'),'--source',str(a.source.resolve()),'--binary',str(a.binary.resolve()),'--state',str(a.out/mode),'--mode',mode,'--port',str(a.port)],env=env,stdout=log,stderr=log,start_new_session=True)
            try:
                deadline=time.monotonic()+120
                while True:
                    if server.poll() is not None:raise RuntimeError('adapter exited before ready')
                    try:
                        with opener.open(url+'/health',timeout=1) as r:health=json.load(r)
                        assert health['ready'] and health['backend']=='ame-'+mode+'-local'
                        break
                    except OSError:
                        if time.monotonic()>deadline:raise
                        time.sleep(.2)
                with (a.out/(mode+'-http.log')).open('w') as output:
                    subprocess.run(['python3',str(harness/'http_bench.py'),'--url',url+'/api/v1/retrieval','--out',str(a.out/(mode+'-http'))],env=env,stdout=output,stderr=subprocess.STDOUT,check=True)
                if mode=='graph':
                    env['AME_TEST_URL']=url+'/api/v1/retrieval'
                    with (a.out/'java-test.log').open('w') as output:
                        subprocess.run([str(root/'mvnw'),'-q','-Dtest=AmeRetrievalIntegrationTest,RagFlowConfigurationTest','test'],cwd=root,env=env,stdout=output,stderr=subprocess.STDOUT,check=True)
                    (a.out/'java-client-samples.json').write_bytes((root/'target/ame-validation/java-client-samples.json').read_bytes())
                    for name in ['AmeRetrievalIntegrationTest','RagFlowConfigurationTest']:
                        p=root/'target/surefire-reports'/('com.heilos.travel.'+name+'.txt')
                        (a.out/(name+'.txt')).write_bytes(p.read_bytes())
                print(mode+' HTTP and integration checks passed',flush=True)
            finally:
                if server.poll() is None:
                    os.killpg(server.pid,signal.SIGTERM)
                server.wait(timeout=15)
    # Removed lmem and missing cheap model must fail explicitly, before import.
    checks=[]
    for mode,model in [('lmem',env['AME_CHEAP_MODEL']),('graph-cheap','/nonexistent/cheap.json')]:
        e=env.copy();e['AME_CHEAP_MODEL']=model
        r=subprocess.run([str(a.binary.resolve()),str(harness/'fixtures/corpus.json'),'-',mode,str(a.out/'invalid.cache')],env=e,text=True,capture_output=True,timeout=10)
        assert r.returncode!=0 and '"ready":true' not in r.stdout
        checks.append({'mode':mode,'exit_code':r.returncode,'stderr':r.stderr.strip()})
    (a.out/'startup-failures.json').write_text(json.dumps(checks,indent=2)+'\n')
if __name__=='__main__':main()
