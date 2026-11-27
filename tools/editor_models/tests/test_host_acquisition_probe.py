"""ONE-SHOT diagnostic for #1077; remove before making PR ready.

Never load checkpoint contents. The existing hosted Python workflow discovers
this temporary file. No model bytes in the checkout, cache, or artifacts.
"""
import datetime
import hashlib
import json
import os
import signal
import tempfile
import unittest
import urllib.error
import urllib.parse
import urllib.request
import zipfile

URL = "https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_tiny.pt"
HOST = "dl.fbaipublicfiles.com"
MIN_BYTES = 100 * 1024 * 1024
MAX_BYTES = 200 * 1024 * 1024
CHUNK = 1024 * 1024


class ProbeError(Exception):
    pass


def checked_url(url):
    parts = urllib.parse.urlsplit(url)
    if (parts.scheme != "https" or parts.hostname != HOST
            or parts.netloc != HOST or parts.username or parts.password
            or parts.fragment or parts.query):
        raise ProbeError("URL outside exact HTTPS original-host allowlist")
    return parts


class SameHostRedirect(urllib.request.HTTPRedirectHandler):
    max_redirections = 3
    max_repeats = 1

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def alarm_handler(signum, frame):
    raise ProbeError("180-second wall-clock deadline exceeded")


class OriginalHostProbe(unittest.TestCase):
    def test_one_full_checkpoint(self):
        if os.environ.get("GITHUB_ACTIONS") != "true":
            self.skipTest("one-shot hosted diagnostic only")
        runner_temp = os.environ.get("RUNNER_TEMP")
        self.assertTrue(runner_temp and os.path.isdir(runner_temp),
                        "runner temporary directory required")
        checked_url(URL)
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), SameHostRedirect())
        request = urllib.request.Request(URL, headers={
            "Accept-Encoding": "identity", "User-Agent": "Atlas-checkpoint-probe/1"
        })
        digest = hashlib.sha256()
        size = 0
        stage = "connect"
        old_handler = signal.signal(signal.SIGALRM, alarm_handler)
        signal.alarm(180)
        try:
            with tempfile.TemporaryDirectory(dir=runner_temp) as directory:
                filename = os.path.join(directory, "checkpoint.pt")
                with opener.open(request, timeout=20) as response:
                    stage = "headers"
                    final = checked_url(response.geturl())
                    if response.status != 200 or response.headers.get("Content-Range"):
                        raise ProbeError("not a complete HTTP 200 response")
                    if response.headers.get("Content-Encoding", "identity") != "identity":
                        raise ProbeError("unexpected content encoding")
                    if response.headers.get_content_type() not in {
                        "application/octet-stream", "application/zip",
                        "application/x-zip-compressed", "binary/octet-stream"
                    }:
                        raise ProbeError("unexpected binary content type")
                    lengths = response.headers.get_all("Content-Length", [])
                    if len(lengths) != 1 or not lengths[0].isdigit():
                        raise ProbeError("missing or ambiguous content length")
                    expected = int(lengths[0])
                    if not MIN_BYTES <= expected <= MAX_BYTES:
                        raise ProbeError("content length outside checkpoint bounds")
                    stage = "stream"
                    with open(filename, "wb") as target:
                        while True:
                            chunk = response.read(CHUNK)
                            if not chunk:
                                break
                            if size == 0 and not chunk.startswith(b"PK\x03\x04"):
                                raise ProbeError("not a PyTorch ZIP checkpoint envelope")
                            size += len(chunk)
                            if size > expected or size > MAX_BYTES:
                                raise ProbeError("body exceeds declared or maximum length")
                            target.write(chunk)
                            digest.update(chunk)
                    if size != expected:
                        raise ProbeError("truncated response")
                stage = "envelope"
                # Check ZIP end-of-central-directory only; no extraction,
                # pickle, torch.load, execution or inference.
                if not zipfile.is_zipfile(filename):
                    raise ProbeError("incomplete ZIP envelope")
                receipt = {
                    "candidate": "sam2_tiny",
                    "release": "072824/sam2_hiera_tiny.pt",
                    "original_url": URL,
                    "final_url": urllib.parse.urlunsplit(
                        (final.scheme, final.netloc, final.path, "", "")),
                    "retrieved_at_utc": datetime.datetime.now(
                        datetime.timezone.utc).isoformat(),
                    "bytes": size,
                    "sha256": digest.hexdigest(),
                }
                stage = "cleanup"
            print("ACQUISITION_PROBE_PASS " + json.dumps(receipt, sort_keys=True),
                  flush=True)
        except urllib.error.HTTPError as error:
            self.fail(f"ACQUISITION_PROBE_FAIL stage={stage} HTTP {error.code}")
        except ProbeError as error:
            self.fail(f"ACQUISITION_PROBE_FAIL stage={stage} {error}")
        except Exception as error:
            # Never include exception text: it may contain a URL or query.
            reason = getattr(error, "reason", None)
            self.fail(f"ACQUISITION_PROBE_FAIL stage={stage} "
                      f"{type(error).__name__} reason_type={type(reason).__name__} "
                      f"errno={getattr(reason, 'errno', getattr(error, 'errno', None))}")
        finally:
            signal.alarm(0)
            signal.signal(signal.SIGALRM, old_handler)
