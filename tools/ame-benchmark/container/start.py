#!/usr/bin/env python3
"""Run AME before Java readiness; fail the container if either child fails."""
import json,os,secrets,signal,subprocess,sys,time,urllib.request
children=[]
def stop(*_):
    for p in reversed(children):
        if p.poll() is None:p.terminate()
    for p in reversed(children):
        try:p.wait(timeout=10)
        except subprocess.TimeoutExpired:p.kill();p.wait()
for sig in [signal.SIGTERM,signal.SIGINT]:
    signal.signal(sig,lambda *_:(stop(),sys.exit(0)))
env=os.environ.copy()
try:
    if env.get('AME_ENABLED','true').lower()=='true':
        token=secrets.token_urlsafe(32)
        env.update(AME_BENCH_TOKEN=token,RAGFLOW_ENABLED='true',RAGFLOW_URL='http://127.0.0.1:18087/api/v1/retrieval',RAGFLOW_DATASET_ID='ame-yingmu',RAGFLOW_API_TOKEN=token,RAGFLOW_API_TOKEN_FILE='',RAGFLOW_USE_RERANK='false',RAGFLOW_RERANK_ID='',RAGFLOW_SIMILARITY_THRESHOLD='0',RAGFLOW_MIN_SIMILARITY='0',RAGFLOW_RETRIES='0')
        children.append(subprocess.Popen(['python3','/app/ame/serve.py','--source','/app/ame/source','--binary','/app/ame/ame_kb_worker','--state','/tmp/ame-runtime'],env=env))
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        ready=False
        for _ in range(240):
            if children[0].poll() is not None:raise RuntimeError('AME exited during startup')
            try:
                with opener.open('http://127.0.0.1:18087/health',timeout=1) as r:ready=json.load(r).get('ready') is True
            except (OSError,ValueError):pass
            if ready:break
            time.sleep(.5)
        if not ready:raise RuntimeError('AME startup timed out')
        print('AME local retrieval enabled; online CSS and MaaS rerank disabled',flush=True)
    children.append(subprocess.Popen(['java','-jar','/app/app.jar'],env=env))
    while all(p.poll() is None for p in children):time.sleep(.5)
    code=next(p.returncode for p in children if p.poll() is not None)
    raise SystemExit(code if code else 1)
finally:stop()
