# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Local IO CLI contract checks; no external service or credentials required."""

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_io_spec as client


class IOClientTests(unittest.TestCase):
    """IO client contract checks; no external service or credentials required."""
    def test_check_reads_request_without_sending_or_printing_it(self):
        body = {"inputs": {"model_config": [{"auth_token": "test-secret-only"}]}}
        with tempfile.TemporaryDirectory() as tmp:
            spec = Path(tmp) / "io.md"
            spec.write_text("curl example --data '" + json.dumps(body) + "'", encoding="utf-8")
            output = io.StringIO()
            with patch.object(client, "invoke") as invoke, contextlib.redirect_stdout(output):
                self.assertEqual(client.main(["--spec", str(spec), "--check"]), 0)
                invoke.assert_not_called()
            self.assertNotIn("test-secret-only", output.getvalue())

    def test_capabilities_does_not_read_io_file(self):
        with patch.object(client, "read_request") as read, patch.object(client, "invoke") as call:
            call.return_value = 0
            self.assertEqual(client.main(["--capabilities", "--port", "8081"]), 0)
            read.assert_not_called()
            call.assert_called_once_with(
                {"inputs": {"operation": "query_capabilities"}}, 8081, True,
            )

    def test_invalid_port_does_not_send_request(self):
        for port in ("0", "65536"):
            with self.subTest(port=port), contextlib.ExitStack() as stack:
                call = stack.enter_context(patch.object(client, "invoke"))
                stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
                with self.assertRaises(SystemExit) as error:
                    client.main(["--port", port, "--capabilities"])
                self.assertEqual(error.exception.code, 2)
                call.assert_not_called()

    def test_sse_completion_and_business_failure(self):
        for code, ended, expected in (("0", True, 0), ("400", True, 1), ("0", False, 1)):
            with self.subTest(code=code, ended=ended):
                result = {"code": code, "message": "test"}
                frame = {
                    "event": "workflow_finished",
                    "data": {
                        "status": {"code": 1},
                        "outputs": {"responseContent": json.dumps(result)},
                    },
                }
                wire = "data: " + json.dumps(frame) + "\n\n"
                if ended:
                    wire += 'data: {"event":"end"}\n\n'
                with contextlib.redirect_stdout(io.StringIO()):
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(client.consume_sse(io.BytesIO(wire.encode())), expected)

    def test_workflow_status_must_indicate_success(self):
        for status in ({"code": -1}, {}, {"code": "1"}):
            with self.subTest(status=status):
                frame = {
                    "event": "workflow_finished",
                    "data": {
                        "status": status,
                        "outputs": {"responseContent": '{"code":"0","message":"test"}'},
                    },
                }
                wire = 'data: ' + json.dumps(frame) + '\n\ndata: {"event":"end"}\n\n'
                expected = 0 if status.get("code") == "1" else 1
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(client.consume_sse(io.BytesIO(wire.encode())), expected)

    def test_oversized_sse_is_rejected_before_printing(self):
        response = [b":" * (16 * 1024 * 1024 + 1)]
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(client.consume_sse(response), 1)
        self.assertEqual(output.getvalue(), "")
        self.assertIn("SSE 响应过大", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
