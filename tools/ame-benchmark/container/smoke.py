"""Verify the release container using only local mock services."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time

IMAGE = 'travel-agent:ame-20261026-01'
RECEIPT = Path('target/ame-container-verification.json')
seen = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        seen.append((self.path, body))
        if self.path == '/retrieval':
            assert self.headers['Authorization'] == 'Bearer smoke-ragflow-token'
            payload = json.dumps({'code': 0, 'data': {'chunks': [
                {'content': 'smoke knowledge', 'similarity': 0.9}]}}).encode()
            content_type = 'application/json'
        else:
            assert self.path == '/model'
            assert self.headers['Authorization'] == 'Bearer smoke-model-token'
            assert body['stream'] is True
            payload = ('data: ' + json.dumps({'choices': [{'index': 0, 'delta': {
                'content': 'smoke answer'}}]}) + '\n\ndata: [DONE]\n\n').encode()
            content_type = 'text/event-stream'
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

def invoke(port, path, body=None):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=15)
    try:
        conn.request('GET' if body is None else 'POST', path,
                     body=None if body is None else json.dumps(body).encode(),
                     headers={} if body is None else {'Content-Type': 'application/json'})
        response = conn.getresponse()
        assert response.status == 200, response.status
        return response.read().decode()
    finally:
        conn.close()

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://host.docker.internal:{server.server_port}'
try:
    for enabled in (False, True):
        container = None
        seen.clear()
        try:
            env = ['-e', 'MODEL_TIMEOUT=10', '-e', 'AME_ENABLED=' + str(enabled).lower()]
            if enabled:
                # Omit the switch to verify the default still performs retrieval.
                env += ['-e', f'RAGFLOW_URL={base}/retrieval',
                        '-e', 'RAGFLOW_DATASET_ID=smoke-kb',
                        '-e', 'RAGFLOW_API_TOKEN=smoke-ragflow-token']
            else:
                # No retrieval URL/ID/token, and a file that cannot be read.
                env += ['-e', 'RAGFLOW_ENABLED=false', '-e', 'RAGFLOW_API_TOKEN_FILE=/missing/token']
            container = docker('run', '--rm', '-d', '-p', '127.0.0.1::8080', *env, IMAGE)
            port = int(docker('port', container, '8080/tcp').rsplit(':', 1)[1])
            deadline = time.monotonic() + 120
            while True:
                try:
                    assert json.loads(invoke(port, '/ping'))['status'] == 'Healthy'
                    break
                except (OSError, http.client.HTTPException):
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.2)
            assert docker('exec', container, 'id', '-u') == '10001'
            raw = invoke(port, '/invocations', {'inputs': {
                'operation': 'chat_completions', 'query': '长信宫灯收藏在哪个博物馆？', 'model_config': [{
                    'type': 'text', 'provider': 'openai-compatible',
                    'endpoint': f'{base}/model', 'model_name': 'smoke-model',
                    'auth_token': 'smoke-model-token', 'temperature': 0.1}]}})
            frames = [json.loads(line[6:]) for line in raw.splitlines() if line.startswith('data: ')]
            finished = next(f['data'] for f in frames if f['event'] == 'workflow_finished')
            result = json.loads(finished['outputs']['responseContent'])
            assert result['code'] == '0' and result['message'] == 'smoke answer', result
            assert frames[-1]['event'] == 'end'
            assert [path for path, _ in seen] == ['/model']
            system = seen[-1][1]['messages'][0]['content']
            assert '不要提及知识库' in system
            assert all(s not in system for s in ('中文不超过50字', 'English under 50 words', '日本語は50字以内'))
            prompt = seen[-1][1]['messages'][1]['content']
            assert ('河北博物院' in prompt) == enabled
            if not enabled:
                assert '当前没有可用的内部资料' in prompt
            deadline = time.monotonic() + 3
            while True:
                output = subprocess.run(['docker', 'logs', container], capture_output=True, text=True, check=True)
                logs = output.stdout + output.stderr
                if 'stage=total ' in logs:
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError('Missing total log')
                time.sleep(0.05)
            mode = 'ragflow' if enabled else 'disabled'
            Path(f'target/ame-container-{mode}.log').write_text(logs)
            rows = [(eid, event, json.loads(data)) for eid, event, data in re.findall(
                r'workflow_diagnostic execution_id=(\S+) event=(\S+) truncated=false data=(.*)', logs)]
            startup = next(data for _, event, data in rows if event == 'ragflow_config')
            assert startup['enabled'] is enabled
            matching = [(event, data) for eid, event, data in rows if eid == finished['execution_id']]
            assert [event for event, _ in matching] == [
                'ragflow_result' if enabled else 'ragflow_skipped', 'model_request']
            if not enabled:
                assert matching[0][1]['attempts'] == 0
            assert f'search_mode={mode}' in logs and 'workflow_ttft ' in logs
            assert 'smoke-model-token' not in logs and 'smoke-ragflow-token' not in logs
            print(f'PASS {mode}: health, UID 10001, expected retrieval calls, SSE, prompt, diagnostics and TTFT', flush=True)
        finally:
            if container:
                subprocess.run(['docker', 'rm', '-f', container], check=True, stdout=subprocess.DEVNULL)
    receipt = {}
    info = json.loads(docker('image', 'inspect', IMAGE))[0]
    assert info['Os'] == 'linux' and info['Architecture'] == 'arm64'
    receipt.update(container_smoke_verified=True, retrieval_switch_verified=True,
                   container_user='10001', local_image_id=info['Id'], response_length_prompt_verified=True)
    RECEIPT.write_text(json.dumps(receipt, indent=2)+'\n')
finally:
    server.shutdown()
    server.server_close()
