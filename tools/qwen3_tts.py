#!/usr/bin/env python3
"""ThreadGens Qwen3-TTS client and persistent-server bootstrap helper."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_URL = "http://127.0.0.1:8765"


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _runtime_dir() -> Path:
    path = _repo_root() / "output" / "runtime"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _base_url() -> str:
    value = os.environ.get("THREADGENS_QWEN3_URL", DEFAULT_URL).strip()
    return value.rstrip("/") or DEFAULT_URL


def _health(timeout: float = 1.5) -> dict | None:
    try:
        with urllib.request.urlopen(_base_url() + "/health", timeout=timeout) as response:
            if response.status != 200:
                return None
            value = json.loads(response.read().decode("utf-8"))
            return value if value.get("ok") else None
    except Exception:
        return None


@contextlib.contextmanager
def _startup_lock():
    """Serialize server startup without blocking-lock reentrancy failures on Windows."""
    lock_path = _runtime_dir() / "qwen3_tts_server.start.lock"
    handle = open(lock_path, "a+b")
    locked = False
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)

        if os.name == "nt":
            import msvcrt

            deadline = time.monotonic() + 1200.0
            while True:
                try:
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    locked = True
                    break
                except OSError:
                    if _health(timeout=1.0) is not None:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Timed out waiting for the Qwen3-TTS startup lock.")
                    time.sleep(0.10)
            yield
            if locked:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            locked = True
            try:
                yield
            finally:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
                locked = False
    finally:
        handle.close()


def _local_server_target() -> tuple[str, int] | None:
    parsed = urllib.parse.urlparse(_base_url())
    if parsed.scheme not in {"http", "https"}:
        return None
    host = (parsed.hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        return None
    if parsed.scheme != "http":
        return None
    return parsed.hostname or "127.0.0.1", parsed.port or 80


def _start_server() -> None:
    target = _local_server_target()
    if target is None:
        raise RuntimeError(
            f"Qwen3-TTS service is unavailable at {_base_url()} and automatic startup is only supported for local HTTP URLs."
        )

    server_script = _repo_root() / "tools" / "qwen3_tts_server.py"
    if not server_script.is_file():
        raise RuntimeError(f"Qwen3-TTS server helper not found: {server_script}")

    host, port = target
    log_path = _runtime_dir() / "qwen3_tts_server.log"
    creationflags = 0
    startupinfo = None
    if os.name == "nt":
        creationflags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
        startupinfo = subprocess.STARTUPINFO()
        startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        startupinfo.wShowWindow = subprocess.SW_HIDE

    with open(log_path, "ab", buffering=0) as log:
        subprocess.Popen(
            [sys.executable, str(server_script), "--host", host, "--port", str(port)],
            cwd=str(_repo_root()),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=log,
            close_fds=os.name != "nt",
            creationflags=creationflags,
            startupinfo=startupinfo,
        )


def _ensure_server() -> dict:
    ready = _health()
    if ready is not None:
        return ready

    with _startup_lock():
        ready = _health()
        if ready is not None:
            return ready

        _start_server()
        timeout_seconds = int(os.environ.get("THREADGENS_QWEN3_STARTUP_TIMEOUT", "1200"))
        deadline = time.monotonic() + max(30, timeout_seconds)
        while time.monotonic() < deadline:
            ready = _health(timeout=2.0)
            if ready is not None:
                return ready
            time.sleep(2.0)

    log_path = _runtime_dir() / "qwen3_tts_server.log"
    raise RuntimeError(
        "Qwen3-TTS service did not become ready before the startup timeout. "
        f"Check {log_path} for the model-load error."
    )


def _configure_server(
    workers: int | None,
    max_batch: int | None,
    batch_window_ms: int | None,
    max_queue: int | None,
) -> dict:
    ready = _ensure_server()
    if "scheduler_version" not in ready:
        raise RuntimeError(
            "The running Qwen3-TTS service predates the worker-aware batch scheduler. "
            "Stop the process listening on port 8765 once, then rerun ThreadGens."
        )

    payload: dict[str, int] = {}
    if workers is not None:
        payload["workers"] = max(1, int(workers))
    if max_batch is not None:
        payload["max_batch"] = max(1, int(max_batch))
    if batch_window_ms is not None:
        payload["batch_window_ms"] = max(0, int(batch_window_ms))
    if max_queue is not None:
        payload["max_queue"] = max(1, int(max_queue))
    if not payload:
        return ready

    request = urllib.request.Request(
        _base_url() + "/configure",
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10.0) as response:
            value = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Qwen3-TTS configuration failed with HTTP {exc.code}: {body}") from exc
    if not value.get("ok"):
        raise RuntimeError(f"Qwen3-TTS configuration failed: {value}")
    return {**ready, **value}


def _synthesize(args: argparse.Namespace) -> None:
    text_path = Path(args.text_file)
    output_path = Path(args.output)
    text = text_path.read_text(encoding="utf-8").strip()
    if not text:
        raise RuntimeError(f"TTS narration is empty: {text_path}")

    ready = _ensure_server()
    if _truthy(os.environ.get("THREADGENS_QWEN3_VERBOSE")):
        print(
            f"[qwen3-tts] server ready: {ready.get('model')} on {ready.get('device')} ({ready.get('dtype')})",
            flush=True,
        )

    worker_id = (
        args.worker_id
        or os.environ.get("THREADGENS_WORKER_ID")
        or f"python-{os.getpid()}"
    )
    payload = {
        "text": text,
        "speaker": args.voice,
        "language": args.lang,
        "speed": args.speed,
        "sentence_pause_ms": args.sentence_pause_ms,
        "delivery": args.delivery,
        "worker_id": worker_id,
        "request_id": f"{worker_id}-{output_path.stem}",
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        _base_url() + "/synthesize",
        data=data,
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    request_timeout = int(os.environ.get("THREADGENS_QWEN3_REQUEST_TIMEOUT", "1800"))
    try:
        with urllib.request.urlopen(request, timeout=max(30, request_timeout)) as response:
            audio = response.read()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Qwen3-TTS synthesis failed with HTTP {exc.code}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach Qwen3-TTS service at {_base_url()}: {exc}") from exc

    if len(audio) < 44 or audio[:4] != b"RIFF":
        raise RuntimeError("Qwen3-TTS service returned an invalid WAV payload.")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(audio)


def _self_test() -> int:
    assert _base_url().startswith("http")
    target = _local_server_target()
    if _base_url() == DEFAULT_URL:
        assert target == ("127.0.0.1", 8765)
    print("Qwen3-TTS client self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="ThreadGens Qwen3-TTS client")
    parser.add_argument("--text-file", help="UTF-8 narration text file")
    parser.add_argument("--output", help="Output WAV path")
    parser.add_argument("--voice", default="Ryan", help="Qwen3-TTS CustomVoice speaker name")
    parser.add_argument("--lang", default="English", help="Qwen3-TTS language name/code")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed, 0.60-1.60")
    parser.add_argument("--sentence-pause-ms", type=int, default=180)
    parser.add_argument("--delivery", default="natural", choices=["natural", "calm", "energetic", "dramatic"])
    parser.add_argument("--worker-id", default="")
    parser.add_argument("--ensure-server", action="store_true", help="Start/validate the persistent service and exit")
    parser.add_argument("--workers", type=int)
    parser.add_argument("--max-batch", type=int)
    parser.add_argument("--batch-window-ms", type=int)
    parser.add_argument("--max-queue", type=int)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return _self_test()

    if args.ensure_server:
        ready = _configure_server(
            workers=args.workers,
            max_batch=args.max_batch,
            batch_window_ms=args.batch_window_ms,
            max_queue=args.max_queue,
        )
        print(json.dumps(ready, sort_keys=True), flush=True)
        return 0

    if not args.text_file or not args.output:
        parser.error("--text-file and --output are required unless --self-test or --ensure-server is used")
    if not 0.60 <= args.speed <= 1.60:
        parser.error("--speed must be between 0.60 and 1.60")
    if not 0 <= args.sentence_pause_ms <= 2000:
        parser.error("--sentence-pause-ms must be between 0 and 2000")

    _synthesize(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Qwen3-TTS error: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
