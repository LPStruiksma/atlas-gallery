"""Temporary one-shot hosted diagnostic #1079; never merge this file."""
import datetime
import hashlib
import json
import os
import re
import signal
import tempfile
import unittest
import urllib.error
import urllib.parse
import urllib.request
import zipfile

URL = "https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_tiny.pt"
HOST = "dl.fbaipublicfiles.com"
PATH = "/segment_anything_2/072824/sam2_hiera_tiny.pt"
MIN_BYTES = 100 * 1024 * 1024
MAX_BYTES = 200 * 1024 * 1024
CHUNK = 1024 * 1024
PREFIX = 512
BINARY_MIME = {"application/octet-stream", "binary/octet-stream",
               "application/zip", "application/x-zip-compressed",
               "application/x-pytorch"}


class ProbeError(Exception):
    pass


def checked_url(url):
    p = urllib.parse.urlsplit(url)
    if (p.scheme, p.netloc, p.path, p.query, p.fragment) != (
            "https", HOST, PATH, "", ""):
        raise ProbeError("URL outside pinned HTTPS host/path")
    return URL


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # No second HTTP request even if the redirect is to the same host.
        raise ProbeError("redirect refused (one request only)")


def mime_token(headers):
    values = headers.get_all("Content-Type", [])
    if len(values) != 1:
        return "UNKNOWN"
    token = values[0].split(";", 1)[0].strip().lower()
    if len(token) > 96 or not re.fullmatch(
            r"[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*", token):
        return "UNKNOWN"
    return token


def declared_length(headers):
    values = headers.get_all("Content-Length", [])
    if len(values) != 1 or not re.fullmatch(r"[0-9]{1,12}", values[0]):
        return None
    return int(values[0])


def prefix_kind(data):
    if data.startswith(b"PK\x03\x04"):
        return "ZIP_LOCAL_FILE_MAGIC"
    if not data:
        return "EMPTY"
    stripped = data.removeprefix(b"\xef\xbb\xbf").lstrip()
    if stripped.startswith(b"<"):
        return "HTML_OR_XML_LIKE"
    if stripped.startswith((b"{", b"[")):
        return "JSON_LIKE"
    if all(b in (9, 10, 13) or 32 <= b < 127 for b in data):
        return "TEXT_LIKE"
    return "UNKNOWN_BINARY"


def emit(tag, fields):
    print(tag + " " + json.dumps(fields, sort_keys=True), flush=True)


def alarm_handler(signum, frame):
    raise ProbeError("180-second wall deadline")


class OneShotMimeProbe(unittest.TestCase):
    def test_one_response(self):
        # Only the initial opened event of this specific draft may use network.
        # Synchronize, ready_for_review, workflow_dispatch and reruns skip.
        if (os.environ.get("GITHUB_ACTIONS") != "true"
                or os.environ.get("GITHUB_EVENT_NAME") != "pull_request"
                or os.environ.get("GITHUB_RUN_ATTEMPT") != "1"):
            self.skipTest("initial hosted draft event only")
        with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event:
            payload = json.load(event)
        pr = payload.get("pull_request", {})
        if (payload.get("action") != "opened"
                or pr.get("head", {}).get("ref") != "chatgpt/1079-mime-probe"
                or not pr.get("draft")):
            self.skipTest("initial hosted draft event only")
        runner_temp = os.environ.get("RUNNER_TEMP")
        self.assertTrue(runner_temp and os.path.isdir(runner_temp),
                        "runner temporary directory required")
        checked_url(URL)
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect())
        request = urllib.request.Request(URL, headers={
            "Accept-Encoding": "identity", "User-Agent": "Atlas-checkpoint-probe/1"
        })
        stage = "connect"
        old_handler = signal.signal(signal.SIGALRM, alarm_handler)
        signal.alarm(180)
        try:
            with tempfile.TemporaryDirectory(dir=runner_temp) as directory:
                filename = os.path.join(directory, "checkpoint.pt")
                with opener.open(request, timeout=20) as response:
                    stage = "headers"
                    final_url = checked_url(response.geturl())
                    mime = mime_token(response.headers)
                    expected = declared_length(response.headers)
                    evidence = {"status": response.status, "host": HOST,
                                "path": PATH, "mime": mime,
                                "declared_length": expected,
                                "prefix": "NOT_READ"}
                    emit("MIME_PROBE_HEADERS", evidence)
                    if response.status != 200 or response.headers.get_all("Content-Range"):
                        raise ProbeError("not complete HTTP 200")
                    if response.headers.get_all("Content-Encoding", ["identity"]) != ["identity"]:
                        raise ProbeError("nonidentity or ambiguous encoding")
                    if response.headers.get_all("Transfer-Encoding"):
                        raise ProbeError("transfer encoding refused")
                    stage = "prefix"
                    prefix = response.read(PREFIX)
                    kind = prefix_kind(prefix)
                    emit("MIME_PROBE_CLASSIFICATION", {**evidence, "prefix": kind,
                                                        "prefix_bytes": len(prefix)})
                    if expected is None or not MIN_BYTES <= expected <= MAX_BYTES:
                        raise ProbeError("missing, ambiguous or implausible length")
                    if mime not in BINARY_MIME:
                        raise ProbeError("MIME not in explicit binary allowlist")
                    if kind != "ZIP_LOCAL_FILE_MAGIC":
                        raise ProbeError("unexpected checkpoint prefix")
                    stage = "stream"
                    digest = hashlib.sha256()
                    size = len(prefix)
                    with open(filename, "wb") as target:
                        target.write(prefix)
                        digest.update(prefix)
                        while True:
                            chunk = response.read(CHUNK)
                            if not chunk:
                                break
                            size += len(chunk)
                            if size > expected or size > MAX_BYTES:
                                raise ProbeError("body exceeds declared or maximum length")
                            target.write(chunk)
                            digest.update(chunk)
                    if size != expected:
                        raise ProbeError("truncated response")
                stage = "envelope"
                # ZIP container check only, no member extraction or deserialization.
                if not zipfile.is_zipfile(filename):
                    raise ProbeError("incomplete ZIP envelope")
                receipt = {"candidate": "sam2_tiny", "original_url": URL,
                           "final_url": final_url, "mime": mime,
                           "prefix": kind, "declared_length": expected,
                           "bytes": size, "sha256": digest.hexdigest(),
                           "retrieved_at_utc": datetime.datetime.now(
                               datetime.timezone.utc).isoformat()}
                stage = "cleanup"
            emit("MIME_PROBE_PASS", receipt)
        except urllib.error.HTTPError as error:
            emit("MIME_PROBE_HEADERS", {"status": error.code, "host": HOST,
                                       "path": PATH, "mime": mime_token(error.headers),
                                       "declared_length": declared_length(error.headers),
                                       "prefix": "NOT_READ"})
            error.close()
            self.fail(f"MIME_PROBE_FAIL stage={stage} HTTP {error.code}")
        except ProbeError as error:
            self.fail(f"MIME_PROBE_FAIL stage={stage} {error}")
        except Exception as error:
            self.fail(f"MIME_PROBE_FAIL stage={stage} {type(error).__name__}")
        finally:
            signal.alarm(0)
            signal.signal(signal.SIGALRM, old_handler)
