"""Reusable ATLAS v1 protocol conformance cases; synthetic byte fixtures only.

Also collected by pytest: python -m pytest tools/ai_edit_server/tests -q.
"""
import base64
import http.client
import json
import struct
import zlib
import binascii
import threading
import time
import unittest

from tools.ai_edit_server.server import FakeBackend, create_server


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl1tAkAAAAASUVORK5CYII="
)


def multipart(fields=None, files=None):
    boundary = "atlas-synthetic-boundary"
    pieces = []
    for name, value in (fields or {}).items():
        pieces.append(
            ('--' + boundary + '\r\nContent-Disposition: form-data; name="' +
             name + '"\r\n\r\n' + str(value) + '\r\n').encode()
        )
    for name, mime, payload in (files or []):
        pieces.append(
            ('--' + boundary + '\r\nContent-Disposition: form-data; name="' +
             name + '"; filename="synthetic.png"\r\nContent-Type: ' +
             mime + '\r\n\r\n').encode() + payload + b'\r\n'
        )
    pieces.append(('--' + boundary + '--\r\n').encode())
    return b''.join(pieces), 'multipart/form-data; boundary=' + boundary


class ProtocolConformance(unittest.TestCase):
    """Reusable against another local backend by overriding make_server."""

    def make_server(self):
        return create_server("127.0.0.1", 0, token="synthetic-test-secret",
                             backend=FakeBackend(delay_seconds=0.01))

    def setUp(self):
        self.server = self.make_server()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.server.jobs.shutdown()

    def call(self, method, path, body=None, content_type=None, auth=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=4)
        headers = {}
        if auth:
            headers["Authorization"] = "Bearer synthetic-test-secret"
        if content_type:
            headers["Content-Type"] = content_type
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = (response.status, dict(response.getheaders()), response.read())
        connection.close()
        return result

    def submit(self, fields=None, files=None):
        body, mime = multipart(
            fields or {"instruction": "make the synthetic pixel warmer"},
            files if files is not None else [("image", "image/png", PNG)],
        )
        status, headers, raw = self.call("POST", "/v1/edits", body, mime)
        return status, json.loads(raw)

    def wait_for(self, job_id, target, timeout=3):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            code, _, raw = self.call("GET", "/v1/edits/" + job_id)
            self.assertEqual(code, 200)
            payload = json.loads(raw)
            if payload["status"] in target:
                return payload
            time.sleep(0.02)
        self.fail("job did not reach " + str(target))

    def test_auth_is_required_on_every_route(self):
        for path in ("/v1/capabilities", "/v1/edits/not-a-job",
                     "/v1/edits/not-a-job/result"):
            code, _, _ = self.call("GET", path, auth=False)
            self.assertEqual(code, 401)
        code, _, _ = self.call("DELETE", "/v1/edits/not-a-job", auth=False)
        self.assertEqual(code, 401)
        code, _, _ = self.call("POST", "/v1/edits", b"", "multipart/form-data",
                               auth=False)
        self.assertEqual(code, 401)
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        connection.request("GET", "/v1/capabilities", headers={"X-Atlas-Token": "synthetic-test-secret"})
        self.assertEqual(connection.getresponse().status, 200)
        connection.close()

    def test_capabilities_are_typed_and_honest(self):
        status, _, raw = self.call("GET", "/v1/capabilities")
        self.assertEqual(status, 200)
        caps = json.loads(raw)
        self.assertEqual(caps["schema_version"], 1)
        self.assertEqual(caps["protocol_version"], "1.0")
        self.assertTrue(caps["features"]["instruction_edit"])
        self.assertFalse(caps["features"]["mask_edit"])
        self.assertEqual(caps["features"]["reference_images"]["max_count"], 0)
        self.assertEqual(caps["features"]["candidates"]["max_count"], 1)
        self.assertIn("advanced_parameters", caps)
        self.assertEqual(len(caps["models"]), 1)

    def test_submit_poll_binary_result_and_access_control(self):
        status, response = self.submit()
        self.assertEqual(status, 202)
        self.assertNotIn("make the synthetic pixel warmer", json.dumps(response))
        self.assertTrue(response["id"])
        job = self.wait_for(response["id"], {"succeeded"})
        self.assertEqual(job["progress"], 100)
        self.assertEqual(job["status_url"], response["status_url"])
        self.assertEqual(job["result_urls"], ["/v1/edits/" + response["id"] + "/result"])
        code, headers, output = self.call("GET", job["result_urls"][0])
        self.assertEqual(code, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(output, PNG)  # fake backend never alters image bytes
        code, _, _ = self.call("GET", job["result_urls"][0], auth=False)
        self.assertEqual(code, 401)

    def test_fake_refused_is_distinct_from_failed(self):
        _, refused = self.submit({"instruction": "synthetic test", "options": '{"fake_outcome":"refused"}'})
        rejected = self.wait_for(refused["id"], {"refused"})
        self.assertEqual(rejected["refusal"]["code"], "FAKE_REFUSAL")
        self.assertNotIn("error", rejected)
        _, failed = self.submit({"instruction": "synthetic test", "options": '{"fake_outcome":"error"}'})
        errored = self.wait_for(failed["id"], {"error"})
        self.assertEqual(errored["error"]["kind"], "server")
        self.assertNotIn("refusal", errored)
        for item in (rejected, errored):
            self.assertEqual(item["result_urls"], [])

    def test_invalid_or_unsupported_input_does_not_submit(self):
        cases = [
            ({"instruction": " "}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok", "options": "{"}, [("image", "image/png", PNG)], 400),
            ({"instruction": "ok", "options": '{"steps":"many"}'}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok", "options": '{"unknown":1}'}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok"}, [("image", "image/gif", b"GIF89a")], 415),
            ({"instruction": "ok"}, [("image", "image/png", b"not an image")], 415),
            ({"instruction": "ok"}, [("image", "image/png", PNG),
                                     ("mask", "image/png", PNG)], 422),
            ({"instruction": "ok"}, [("image", "image/png", PNG),
                                     ("reference", "image/png", PNG)], 422),
        ]
        for fields, files, expected in cases:
            with self.subTest(fields=fields, files=files):
                body, mime = multipart(fields, files)
                status, _, _ = self.call("POST", "/v1/edits", body, mime)
                self.assertEqual(status, expected)

    def test_structurally_corrupt_images_are_not_accepted(self):
        # These retain valid magic bytes and dimensions, but are not images.
        cases = [
            PNG[:-7],
            PNG[:29] + bytes([PNG[29] ^ 1]) + PNG[30:],
            b"\xff\xd8\xff\xc0\x00\x11\x08\x00\x01\x00\x01" + b"\x00" * 12,
        ]
        for payload in cases:
            with self.subTest(length=len(payload)):
                mime = "image/jpeg" if payload.startswith(b"\xff\xd8") else "image/png"
                status, response = self.submit(files=[("image", mime, payload)])
                self.assertEqual(status, 415)
                self.assertEqual(response["error"]["code"], "invalid_image")

    def test_content_size_and_unknown_job(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        connection.putrequest("POST", "/v1/edits")
        connection.putheader("Authorization", "Bearer synthetic-test-secret")
        connection.putheader("Content-Length", "21000000")
        connection.putheader("Content-Type", "multipart/form-data; boundary=x")
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 413)
        connection.close()
        code, _, _ = self.call("GET", "/v1/edits/does-not-exist")
        self.assertEqual(code, 404)

    def test_delete_cancels_without_publishing_result(self):
        self.server.jobs.backend.delay_seconds = 0.5
        status, response = self.submit()
        self.assertEqual(status, 202)
        code, _, raw = self.call("DELETE", "/v1/edits/" + response["id"])
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(raw)["status"], "cancelled")
        job = self.wait_for(response["id"], {"cancelled"})
        self.assertEqual(job["result_urls"], [])
        code, _, _ = self.call("GET", "/v1/edits/" + response["id"] + "/result")
        self.assertEqual(code, 404)


if __name__ == "__main__":
    unittest.main()
