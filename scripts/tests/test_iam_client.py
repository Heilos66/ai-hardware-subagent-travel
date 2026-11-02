# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Local wire-level tests; requires the official SDK, never real cloud credentials."""

import contextlib
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_agentarts_iam as client


AK, SK = "local-test-ak-only", "local-test-sk-only"
REGION = "cn-southwest-2"
CAPABILITIES = {"inputs": {"operation": "query_capabilities"}}


def frame(event, data=None):
    body = {"event": event}
    if data is not None:
        body["data"] = data
    return f"event: {event}\ndata: {json.dumps(body, ensure_ascii=False)}\n\n".encode()


def finish(result=None, status=1):
    if result is None:
        result = {"capabilities": {"chat_completions": True, "create_response": False, "fetch_response": False}}
    return frame("workflow_finished", {
        "status": {"code": status},
        "outputs": {"responseContent": json.dumps(result, ensure_ascii=False)},
    })


class Gateway(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        server = self.server
        body = self.rfile.read(int(self.headers["Content-Length"]))
        req = server.signer.HttpRequest("POST", f"http://{self.headers['Host']}{self.path}", dict(self.headers), body.decode())
        sig = server.signer.Signer(algorithm="V11-HMAC-SHA256", region_id=REGION)
        sig.Key, sig.Secret = AK, SK
        valid = sig.Verify(req, self.headers["Authorization"])
        server.received.append((self.path, dict(self.headers), body, valid))
        status = server.status or (200 if valid else 401)
        self.send_response(status)
        self.send_header("Content-Type", server.content_type)
        self.send_header("Connection", "close")
        if server.redirect:
            self.send_header("Location", server.redirect)
        self.end_headers()
        if status != 200:
            # Client must not echo error bodies that reflect credentials.
            self.wfile.write(f"echo {AK} {SK} {self.headers['Authorization']}".encode())
            return
        try:
            self.wfile.write(server.first)
            self.wfile.flush()
            if server.release is not None and not server.release.wait(5):
                server.stream_wait_failed = True
            self.wfile.write(server.last)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Some tests deliberately stop reading or time out.


class IAMTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sdk_dir = os.getenv("APIG_SDK_PATH")
        cls.signer = client.load_signer(Path(sdk_dir) if sdk_dir else None)

    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
        self.server.signer = self.signer
        self.server.status = None
        self.server.redirect = None
        self.server.content_type = "text/event-stream; charset=utf-8"
        self.server.first = frame("workflow_started", {})
        self.server.last = finish() + frame("end")
        self.server.release = None
        self.server.stream_wait_failed = False
        self.server.received = []
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/runtimes/travel-test/invocations"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), client.NoRedirect())

    def tearDown(self):
        if self.server.release:
            self.server.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def call(self, body=None, query="?endpoint=Latest", negative=False, output=None):
        body = CAPABILITIES if body is None else body
        request = client.signed_request(self.signer, self.url + query, body, REGION, "local-test-session", AK, SK, negative)
        output, errors = output if output is not None else io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = client.invoke(request, body["inputs"].get("operation"), 3, negative, self.opener)
        return code, output.getvalue() + errors.getvalue()

    def test_official_sdk_verifies_wire_signature_and_latest_query(self):
        code, output = self.call()
        self.assertEqual(code, 0)
        path, headers, body, valid = self.server.received[0]
        self.assertTrue(valid)
        self.assertEqual(path, "/runtimes/travel-test/invocations?endpoint=Latest")
        self.assertEqual(json.loads(body), CAPABILITIES)
        lower = {k.lower(): v for k, v in headers.items()}
        self.assertEqual(lower["x-sdk-content-sha256"], "UNSIGNED-PAYLOAD")
        self.assertEqual(lower["x-hw-agentarts-session-id"], "local-test-session")
        self.assertTrue(lower["authorization"].startswith("V11-HMAC-SHA256 "))
        self.assertNotIn(AK, output)
        self.assertNotIn(SK, output)
        self.assertNotIn("Signature=", output)

    def test_custom_endpoint_query_and_chinese_body_are_preserved(self):
        body = {"inputs": {"operation": "chat_completions", "query": "介绍三星堆"}}
        self.server.last = finish({"code": "0", "message": "青铜面具"}) + frame("end")
        code, _ = self.call(body, "?endpoint=release-1&label=%E4%B8%AD%E6%96%87&x=b&x=a")
        path, _, wire_body, valid = self.server.received[0]
        self.assertEqual(code, 0)
        self.assertTrue(valid)
        self.assertEqual(json.loads(wire_body), body)
        self.assertIn("三星堆".encode(), wire_body)
        self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(path).query), {
            "endpoint": ["release-1"], "label": ["中文"], "x": ["a", "b"],
        })

    def test_changed_endpoint_fails_signature_verification(self):
        request = client.signed_request(self.signer, self.url + "?endpoint=Latest", CAPABILITIES, REGION, "test", AK, SK)
        request.full_url = request.full_url.replace("Latest", "other")
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(client.invoke(request, "query_capabilities", 3, opener=self.opener), 3)
        self.assertFalse(self.server.received[0][3])

    def test_positive_then_corrupted_signature_is_rejected(self):
        self.assertEqual(self.call()[0], 0)
        code, output = self.call(negative=True)
        self.assertEqual(code, 0)
        self.assertIn("反向测试通过", output)
        self.assertFalse(self.server.received[-1][3])
        self.assertNotIn(SK, output)
        self.assertNotIn(AK, output)

    def test_negative_test_fails_when_invalid_signature_is_accepted(self):
        self.server.status = 200
        self.assertEqual(self.call(negative=True)[0], 1)

    def test_gateway_rejection_distinct_from_application_failure(self):
        for status in (401, 403, 404, 500):
            with self.subTest(status=status):
                self.server.status = status
                code, output = self.call()
                self.assertEqual(code, 3 if status in (401, 403) else 1)
                self.assertNotIn(AK, output)
                self.assertNotIn(SK, output)
        self.server.status = None
        for status, result in ((1, {"code": "E5003"}), (-1, {"code": "0"})):
            with self.subTest(status=status, result=result):
                self.server.last = finish(result, status) + frame("end")
                self.assertEqual(self.call({"inputs": {"operation": "chat_completions"}})[0], 1)

    def test_redirect_is_not_followed(self):
        self.server.status = 307
        self.server.redirect = self.url + "/other"
        self.assertEqual(self.call()[0], 1)
        self.assertEqual(len(self.server.received), 1)

    def test_sse_is_visible_before_server_finishes(self):
        release = self.server.release = threading.Event()
        self.server.first = frame("message", {"text": "首片", "reasoning_content": "思考片段"})

        class StreamingOutput(io.StringIO):
            def flush(self):
                if "首片" in self.getvalue():
                    release.set()

        code, output = self.call(output=StreamingOutput())
        self.assertEqual(code, 0)
        self.assertFalse(self.server.stream_wait_failed)
        self.assertTrue(release.is_set())
        self.assertIn("思考片段", output)

    def test_incomplete_or_malformed_sse_fails(self):
        for response in (finish(), frame("end"), b"data: {bad}\n\n", finish() + b'data: {"event":"end"}', finish({}) + frame("end")):
            with self.subTest(response=response):
                self.server.last = response
                self.assertEqual(self.call()[0], 1)

    def test_json_http_200_is_not_mistaken_for_sse_success(self):
        self.server.content_type = "application/json"
        self.assertEqual(self.call()[0], 1)

    def test_multiline_sse_and_heartbeat(self):
        self.server.first = b': keep-alive\r\n\r\ndata: {"event":\r\ndata: "workflow_started"}\r\n\r\n'
        self.assertEqual(self.call()[0], 0)

    def test_read_timeout_fails(self):
        self.server.release = threading.Event()
        request = client.signed_request(self.signer, self.url, CAPABILITIES, REGION, "test", AK, SK)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(client.invoke(request, "query_capabilities", 0.05, opener=self.opener), 1)

    def test_cli_check_reads_io_without_sending_or_printing_credentials(self):
        env = {"HUAWEICLOUD_SDK_AK": AK, "HUAWEICLOUD_SDK_SK": SK, "AGENTARTS_RUNTIME_URL": "https://test.example/runtimes/test/invocations?endpoint=Latest"}
        body = {"inputs": {"query": "你好", "model_config": [{"auth_token": "private-model-token"}]}}
        with tempfile.TemporaryDirectory() as tmp:
            spec = Path(tmp) / "io.md"
            spec.write_text("curl example --data '" + json.dumps(body) + "'", encoding="utf-8")
            output = io.StringIO()
            with patch.dict(os.environ, env, clear=True), patch.object(client, "invoke") as invoke, contextlib.redirect_stdout(output):
                self.assertEqual(client.main(["--spec", str(spec), "--check"]), 0)
                invoke.assert_not_called()
            for secret in (AK, SK, "private-model-token"):
                self.assertNotIn(secret, output.getvalue())

    def test_cli_configuration_errors_do_not_send_requests(self):
        env = {"HUAWEICLOUD_SDK_AK": AK, "HUAWEICLOUD_SDK_SK": SK, "AGENTARTS_RUNTIME_URL": "https://test.example/runtimes/test/invocations"}
        for args, changes in (([], {"HUAWEICLOUD_SDK_SK": ""}), (["--url", "http://example.com/invocations"], {}), (["--timeout", "nan"], {}), (["--expect-auth-rejection", "--json", "anything.json"], {})):
            with self.subTest(args=args, changes=changes), patch.dict(os.environ, env | changes, clear=True), patch.object(client, "invoke") as invoke, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                client.main(args)
            self.assertEqual(error.exception.code, 2)
            invoke.assert_not_called()


if __name__ == "__main__":
    unittest.main()
