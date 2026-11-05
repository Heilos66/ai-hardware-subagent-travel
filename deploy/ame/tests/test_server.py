import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import MAX_BODY_BYTES, Server, Worker, WorkerFailure, validate

TOKEN = 'test-token-' * 4


class FakeWorker:
    available = True

    def healthy(self):
        return self.available

    def query(self, question, limit, deadline):
        return {'hits': [{'id': '1', 'content': question, 'document': '灯', 'score': -0.5}],
                'recall_ms': 1.5, 'queue_ms': 0.1}


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.worker = FakeWorker()
        self.server = Server(('127.0.0.1', 0), self.worker, TOKEN, 'ame-travel')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, body=None, token=TOKEN, path='/api/v1/retrieval', method='POST'):
        client = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            client.request(method, path, json.dumps(body) if body is not None else None,
                           {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
            response = client.getresponse()
            return response.status, json.loads(response.read())
        finally:
            client.close()

    def body(self, **changes):
        return {'dataset_ids': ['ame-travel'], 'question': '长信宫灯', **changes}

    def test_contract_and_negative_graph_score(self):
        status, body = self.request(self.body())
        self.assertEqual(200, status)
        self.assertEqual(-0.5, body['data']['chunks'][0]['similarity'])
        self.assertEqual(0.1, body['meta']['queue_ms'])

    def test_ten_thousand_unicode_characters(self):
        status, body = self.request(self.body(question='𠮷' * 10000))
        self.assertEqual(200, status)
        self.assertEqual(10000, len(body['data']['chunks'][0]['content']))
        self.assertEqual(400, self.request(self.body(question='灯' * 10001))[0])

    def test_authentication(self):
        self.assertEqual(401, self.request(self.body(), token='wrong')[0])

    def test_dataset_and_ragflow_options(self):
        for change in [{'dataset_ids': ['wrong']}, {'rerank_id': 'remote'},
                       {'knn_top_k': 64}, {'similarity_threshold': 0.2}, {'page_size': True},
                       {'question': ''}, {'page_size': 51}, {'question': '\ud800'}]:
            with self.subTest(change=change):
                self.assertEqual(400, self.request(self.body(**change))[0])

    def test_health_detects_dead_worker(self):
        self.assertEqual(200, self.request(method='GET', path='/health')[0])
        self.worker.available = False
        self.assertEqual(503, self.request(method='GET', path='/health')[0])

    def test_queue_is_bounded(self):
        for _ in range(8):
            self.server.queue.acquire()
        try:
            self.assertEqual(503, self.request(self.body())[0])
        finally:
            for _ in range(8):
                self.server.queue.release()

    def test_oversized_body(self):
        self.assertEqual(413, self.request(self.body(question='a' * MAX_BODY_BYTES))[0])

    def test_worker_error_does_not_leak_details(self):
        def fail(*_args):
            raise WorkerFailure('private error detail')
        self.worker.query = fail
        status, body = self.request(self.body())
        self.assertEqual(502, status)
        self.assertNotIn('private', json.dumps(body))


class WorkerTest(unittest.TestCase):
    def test_timeout_kills_worker_and_prevents_response_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'worker'
            binary.write_text('#!/usr/bin/env python3\nimport time\n'
                              'print(\'{"ready":true,"chunks":1}\', flush=True)\ntime.sleep(30)\n')
            binary.chmod(0o700)
            worker = Worker(binary, '-', '-', '-', startup_timeout=2)
            with self.assertRaises(WorkerFailure):
                worker.query('test', 3, time.monotonic() + 0.1)
            self.assertFalse(worker.healthy())
            worker.close()


if __name__ == '__main__':
    unittest.main()
