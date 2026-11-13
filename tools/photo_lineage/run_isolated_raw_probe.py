"""Host-only timeout and status envelope for opt-in pinned RAW research."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time

_SHA = re.compile(r"[0-9a-f]{64}\Z")
_FRAME = re.compile(r"payload_N[0-9]{1,3}\.dng\Z")


def _capture_bounded(command: list[str], *, wall_seconds: float) -> tuple[int, bytes]:
    """Read at most 4097 child bytes, killing a noisy or stuck worker.

    subprocess.run(..., PIPE) checks length only after buffering everything.
    Read through a readiness selector instead, with one shared wall deadline.
    """
    proc = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            close_fds=True)
    output = bytearray()
    deadline = time.monotonic() + wall_seconds
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(timeout=remaining):
                    raise ValueError("RAW probe wall-time budget exceeded")
                block = os.read(proc.stdout.fileno(), min(4097 - len(output), 4096))
                if not block:
                    break
                output.extend(block)
                if len(output) > 4096:
                    raise ValueError("RAW worker stdout quota exceeded")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError("RAW probe wall-time budget exceeded")
        try:
            return proc.wait(timeout=remaining), bytes(output)
        except subprocess.TimeoutExpired:
            raise ValueError("RAW probe wall-time budget exceeded") from None
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        if proc.stdout is not None:
            proc.stdout.close()



def run(*, source: Path, sha256: str, memory_mib: int = 2048,
        cpu_seconds: int = 30, wall_seconds: int = 45) -> dict:
    if (sys.platform != "linux" or os.environ.get("ANDROID_ROOT") or
            os.environ.get("TERMUX_VERSION") or Path("/system/build.prop").exists()):
        raise ValueError("Linux host only; Android/Termux refused")
    if (type(memory_mib) is not int or not 512 <= memory_mib <= 4096 or
            type(cpu_seconds) is not int or not 3 <= cpu_seconds <= 90 or
            type(wall_seconds) is not int or not 5 <= wall_seconds <= 120 or
            type(sha256) is not str or not _SHA.fullmatch(sha256) or
            not isinstance(source, Path) or not _FRAME.fullmatch(source.name)):
        raise ValueError("Invalid RAW probe arguments")
    worker = Path(__file__).with_name("isolated_raw_probe.py").resolve()
    cmd = [sys.executable, "-I", "-B", str(worker), "--source", str(source),
           "--sha256", sha256, "--memory-mib", str(memory_mib),
           "--cpu-seconds", str(cpu_seconds)]
    returncode, output = _capture_bounded(cmd, wall_seconds=wall_seconds)
    if returncode != 0:
        raise ValueError("RAW worker refused or failed")
    try:
        report = json.loads(output.decode("utf-8"))
    except (ValueError, UnicodeError):
        raise ValueError("Malformed RAW worker result") from None
    if (type(report) is not dict or
            report.get("source_sha256_pin_matched_before_and_after") is not True or
            report.get("basic_dng_header_passed") is not True or
            report.get("full_dng_format_validated") is not False or
            report.get("images_exported_or_uploaded") is not False or
            report.get("separate_shutter_identity_verified") is not False or
            type(report.get("grayscale_bytes_generated_in_memory")) is not int or
            not 2304 <= report["grayscale_bytes_generated_in_memory"] <= 1024 * 1024 or
            type(report.get("grayscale_width")) is not int or
            type(report.get("grayscale_height")) is not int or
            report["grayscale_width"] * report["grayscale_height"] != report["grayscale_bytes_generated_in_memory"]):
        raise ValueError("Inconsistent RAW worker report")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--memory-mib", type=int, default=2048)
    parser.add_argument("--cpu-seconds", type=int, default=30)
    parser.add_argument("--wall-seconds", type=int, default=45)
    args = parser.parse_args()
    print(json.dumps(run(source=args.source, sha256=args.sha256,
                         memory_mib=args.memory_mib, cpu_seconds=args.cpu_seconds,
                         wall_seconds=args.wall_seconds), indent=2))


if __name__ == "__main__":
    main()
