import json
import os
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import responses_bridge


class BridgeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ["HANSUL_AI_BRIDGE_TOKEN"] = "a" * 48
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), responses_bridge.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/v1/responses"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def post(self, data, token="a" * 48):
        request = Request(self.url, data=json.dumps(data).encode(), method="POST", headers={
            "Content-Type": "application/json", "Authorization": "Bearer " + token,
        })
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def valid_body(self):
        return {
            "model": "gpt-6-sol", "store": False, "instructions": "Return one value",
            "input": [{"role": "user", "content": "source"}],
            "text": {"format": {"type": "json_schema", "name": "test", "strict": True,
                                "schema": {"type": "object", "properties": {"ok": {"type": "boolean"}},
                                           "required": ["ok"], "additionalProperties": False}}},
        }

    def test_bearer_token_required(self):
        status, _ = self.post(self.valid_body(), token="wrong")
        self.assertEqual(401, status)

    def test_only_supported_structured_subset_accepted(self):
        body = self.valid_body()
        body["store"] = True
        status, _ = self.post(body)
        self.assertEqual(400, status)

    def test_success_uses_responses_output_envelope(self):
        def fake_codex(args, **_kwargs):
            Path(args[args.index("--output-last-message") + 1]).write_text('{"ok":true}', encoding="utf-8")
            return type("Result", (), {"returncode": 0})()

        with patch.object(responses_bridge.subprocess, "run", side_effect=fake_codex):
            status, body = self.post(self.valid_body())
        self.assertEqual(200, status)
        self.assertEqual("completed", body["status"])
        self.assertEqual({"ok": True}, json.loads(body["output"][0]["content"][0]["text"]))


if __name__ == "__main__":
    unittest.main()
