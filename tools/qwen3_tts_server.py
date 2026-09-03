#!/usr/bin/env python3
"""Persistent local Qwen3-TTS service for ThreadGens.

The service binds to localhost by default, loads one Qwen CustomVoice model,
micro-batches narration requests from independent ThreadGens workers, and
returns clean PCM WAV audio without time stretching.
"""

from __future__ import annotations

import argparse
import collections
import gc
import io
import json
import os
import re
import threading
import time
import traceback
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765
DEFAULT_MODEL = "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice"
MAX_REQUEST_BYTES = 2 * 1024 * 1024
MAX_CHUNK_CHARS = 700

_MODEL = None
_TORCH = None
_NP = None
_SF = None
_MODEL_ID = ""
_DEVICE = ""
_DTYPE_NAME = ""
_SCHEDULER = None


def _env_int(name: str, fallback: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.environ.get(name, str(fallback)))
    except (TypeError, ValueError):
        value = fallback
    return min(maximum, max(minimum, value))


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _speaker_name(value: str | None) -> str:
    raw = (value or "Ryan").strip()
    aliases = {
        "ryan": "Ryan",
        "aiden": "Aiden",
        "vivian": "Vivian",
        "serena": "Serena",
        "uncle_fu": "Uncle_Fu",
        "uncle-fu": "Uncle_Fu",
        "dylan": "Dylan",
        "eric": "Eric",
        "ono_anna": "Ono_Anna",
        "ono-anna": "Ono_Anna",
        "sohee": "Sohee",
    }
    return aliases.get(raw.lower(), raw)


def _language_name(value: str | None) -> str:
    raw = (value or "English").strip()
    aliases = {
        "a": "English",
        "en": "English",
        "en-us": "English",
        "en_us": "English",
        "english": "English",
        "auto": "Auto",
        "zh": "Chinese",
        "chinese": "Chinese",
        "ja": "Japanese",
        "japanese": "Japanese",
        "ko": "Korean",
        "korean": "Korean",
        "de": "German",
        "german": "German",
        "fr": "French",
        "french": "French",
        "ru": "Russian",
        "russian": "Russian",
        "pt": "Portuguese",
        "portuguese": "Portuguese",
        "es": "Spanish",
        "spanish": "Spanish",
        "it": "Italian",
        "italian": "Italian",
    }
    return aliases.get(raw.lower(), raw)


def _delivery_instruction(delivery: str, speed: float) -> str:
    preset = (delivery or "natural").strip().lower()
    style = {
        "calm": "calm, relaxed, clear, and restrained",
        "energetic": "energetic and engaging, while staying conversational",
        "dramatic": "expressive with deliberate emphasis, without sounding theatrical",
        "natural": "natural and conversational, like a real person speaking directly to one listener",
    }.get(preset, "natural and conversational")

    pace = ""
    if speed >= 1.08:
        pace = " Use a brisk but comfortable pace; do not rush or clip words."
    elif speed <= 0.94:
        pace = " Use a relaxed but continuous pace; do not drag words."

    return (
        f"Speak in a {style} manner. "
        "Use a clean, dry, close-microphone studio sound with no echo, reverb, "
        "room ambience, doubled voice, or special effect."
        f"{pace}"
    )


def _normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


def _chunk_text(text: str, max_chars: int = MAX_CHUNK_CHARS) -> list[str]:
    """Keep normal narration coherent and split only unusually long inputs."""
    clean = _normalize_text(text)
    if not clean:
        return []
    if len(clean) <= max_chars:
        return [clean]

    sentences = [
        part.strip()
        for part in re.split(r"(?<=[.!?])\s+", clean)
        if part.strip()
    ]
    if not sentences:
        return [clean]

    chunks: list[str] = []
    current = ""
    for sentence in sentences:
        if not current:
            current = sentence
            continue
        candidate = f"{current} {sentence}"
        if len(candidate) <= max_chars:
            current = candidate
        else:
            chunks.append(current)
            current = sentence
    if current:
        chunks.append(current)
    return chunks


def _to_audio_array(value: Any):
    array = _NP.asarray(value)
    array = _NP.squeeze(array)
    if array.ndim != 1:
        array = array.reshape(-1)
    if array.size == 0:
        raise RuntimeError("Qwen3-TTS returned an empty audio buffer.")
    return array.astype(_NP.float32, copy=False)


def _edge_fade(audio, sample_rate: int, milliseconds: float = 6.0):
    """Apply only a tiny click-prevention fade; do not alter voice timing."""
    count = min(int(sample_rate * milliseconds / 1000.0), len(audio) // 2)
    if count <= 1:
        return audio
    result = audio.copy()
    ramp = _NP.linspace(0.0, 1.0, count, dtype=_NP.float32)
    result[:count] *= ramp
    result[-count:] *= ramp[::-1]
    return result


def _load_model() -> None:
    global _MODEL, _TORCH, _NP, _SF, _MODEL_ID, _DEVICE, _DTYPE_NAME

    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

    import numpy as np
    import soundfile as sf
    import torch
    from qwen_tts import Qwen3TTSModel

    model_id = os.environ.get("THREADGENS_QWEN3_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    allow_cpu = _truthy(os.environ.get("THREADGENS_QWEN3_ALLOW_CPU"))
    if torch.cuda.is_available():
        device = os.environ.get("THREADGENS_QWEN3_DEVICE", "cuda:0").strip() or "cuda:0"
        dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    elif allow_cpu:
        device = "cpu"
        dtype = torch.float32
    else:
        raise RuntimeError(
            "Qwen3-TTS requires CUDA for ThreadGens production. PyTorch cannot see an NVIDIA GPU. "
            "Run setup_qwen3_tts_windows.ps1 and verify the CUDA PyTorch install."
        )

    attn = os.environ.get("THREADGENS_QWEN3_ATTN", "sdpa").strip() or "sdpa"
    print(
        f"[qwen3-tts] loading {model_id} on {device} with {dtype} / attention={attn}",
        flush=True,
    )
    torch.set_grad_enabled(False)
    model = Qwen3TTSModel.from_pretrained(
        model_id,
        device_map=device,
        dtype=dtype,
        attn_implementation=attn,
    )

    _MODEL = model
    _TORCH = torch
    _NP = np
    _SF = sf
    _MODEL_ID = model_id
    _DEVICE = device
    _DTYPE_NAME = str(dtype).replace("torch.", "")
    print(f"[qwen3-tts] ready: {_MODEL_ID} on {_DEVICE}", flush=True)


@dataclass
class SynthesisRequest:
    request_id: str
    worker_id: str
    speaker: str
    language: str
    instruct: str
    pause_ms: int
    chunks: list[str]
    created_at: float = field(default_factory=time.monotonic)
    event: threading.Event = field(default_factory=threading.Event)
    chunk_audio: list[Any | None] = field(init=False)
    sample_rate: int | None = None
    error: str | None = None
    completed_at: float | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)

    def __post_init__(self) -> None:
        self.chunk_audio = [None] * len(self.chunks)

    def finish_chunk(self, index: int, audio, sample_rate: int) -> None:
        with self.lock:
            if self.event.is_set():
                return
            if self.sample_rate is None:
                self.sample_rate = int(sample_rate)
            elif self.sample_rate != int(sample_rate):
                self.error = (
                    f"Qwen3-TTS returned mixed sample rates for one narration: "
                    f"{self.sample_rate} and {sample_rate}"
                )
                self.completed_at = time.monotonic()
                self.event.set()
                return
            self.chunk_audio[index] = _edge_fade(audio, int(sample_rate))
            if all(chunk is not None for chunk in self.chunk_audio):
                self.completed_at = time.monotonic()
                self.event.set()

    def fail(self, exc: BaseException | str) -> None:
        with self.lock:
            if self.event.is_set():
                return
            self.error = str(exc)
            self.completed_at = time.monotonic()
            self.event.set()

    def wav_bytes(self) -> tuple[bytes, int]:
        if self.error:
            raise RuntimeError(self.error)
        if not self.chunk_audio or any(chunk is None for chunk in self.chunk_audio):
            raise RuntimeError("Qwen3-TTS request completed without all audio chunks.")
        if self.sample_rate is None:
            raise RuntimeError("Qwen3-TTS request completed without a sample rate.")

        audio_chunks = [chunk for chunk in self.chunk_audio if chunk is not None]
        if len(audio_chunks) > 1:
            gap_ms = max(80, self.pause_ms)
            silence = _NP.zeros(
                int(self.sample_rate * gap_ms / 1000.0),
                dtype=_NP.float32,
            )
            joined: list[Any] = []
            for index, chunk in enumerate(audio_chunks):
                if index:
                    joined.append(silence)
                joined.append(chunk)
            audio = _NP.concatenate(joined)
        else:
            audio = audio_chunks[0]

        output = io.BytesIO()
        _SF.write(output, audio, int(self.sample_rate), format="WAV", subtype="PCM_16")
        return output.getvalue(), int(self.sample_rate)


@dataclass
class ChunkJob:
    request: SynthesisRequest
    chunk_index: int
    text: str
    enqueued_at: float = field(default_factory=time.monotonic)


class BatchScheduler:
    """One model owner, worker-aware queues, and adaptive GPU micro-batching."""

    def __init__(
        self,
        workers: int,
        max_batch: int,
        batch_window_ms: int,
        max_queue: int,
    ) -> None:
        self._cv = threading.Condition()
        self._queues: collections.OrderedDict[str, collections.deque[ChunkJob]] = collections.OrderedDict()
        self._pending = 0
        self._stopping = False
        self._workers = max(1, workers)
        self._max_batch = max(1, max_batch)
        self._safe_batch = self._max_batch
        self._batch_window_ms = max(0, batch_window_ms)
        self._max_queue = max(self._max_batch, max_queue)
        self._active_batch = 0
        self._completed_requests = 0
        self._completed_chunks = 0
        self._failed_requests = 0
        self._batch_calls = 0
        self._oom_backoffs = 0
        self._queue_wait_total = 0.0
        self._inference_total = 0.0
        self._scalar_calls = 0
        self._batched_calls = 0
        self._thread = threading.Thread(
            target=self._run,
            name="ThreadGensQwenBatchScheduler",
            daemon=True,
        )
        self._thread.start()

    def configure(
        self,
        workers: int | None = None,
        max_batch: int | None = None,
        batch_window_ms: int | None = None,
        max_queue: int | None = None,
    ) -> dict[str, Any]:
        with self._cv:
            if workers is not None:
                self._workers = min(32, max(1, int(workers)))
            if max_batch is not None:
                new_max = min(16, max(1, int(max_batch)))
                self._max_batch = new_max
                if self._oom_backoffs == 0:
                    self._safe_batch = new_max
                else:
                    self._safe_batch = min(self._safe_batch, new_max)
                if self._safe_batch < 1:
                    self._safe_batch = 1
            if batch_window_ms is not None:
                self._batch_window_ms = min(2000, max(0, int(batch_window_ms)))
            if max_queue is not None:
                self._max_queue = max(self._max_batch, min(256, max(1, int(max_queue))))
            self._cv.notify_all()
            return self._status_locked()

    def submit(self, payload: dict[str, Any]) -> SynthesisRequest:
        text = _normalize_text(str(payload.get("text") or ""))
        if not text:
            raise ValueError("text is required")

        speaker = _speaker_name(str(payload.get("speaker") or "Ryan"))
        language = _language_name(str(payload.get("language") or "English"))
        delivery = str(payload.get("delivery") or "natural")
        speed = min(1.60, max(0.60, float(payload.get("speed") or 1.0)))
        pause_ms = min(800, max(0, int(payload.get("sentence_pause_ms") or 0)))
        instruct = str(payload.get("instruct") or "").strip() or _delivery_instruction(delivery, speed)
        worker_id = _normalize_text(str(payload.get("worker_id") or "worker"))[:120] or "worker"
        request_id = _normalize_text(str(payload.get("request_id") or ""))[:160]
        if not request_id:
            request_id = uuid.uuid4().hex

        chunks = _chunk_text(text)
        if not chunks:
            raise ValueError("text is empty after normalization")
        request = SynthesisRequest(
            request_id=request_id,
            worker_id=worker_id,
            speaker=speaker,
            language=language,
            instruct=instruct,
            pause_ms=pause_ms,
            chunks=chunks,
        )
        jobs = [
            ChunkJob(request=request, chunk_index=index, text=chunk)
            for index, chunk in enumerate(chunks)
        ]

        with self._cv:
            while not self._stopping and self._pending + len(jobs) > self._max_queue:
                self._cv.wait(timeout=0.25)
            if self._stopping:
                raise RuntimeError("Qwen3-TTS scheduler is stopping.")
            queue = self._queues.setdefault(worker_id, collections.deque())
            queue.extend(jobs)
            self._pending += len(jobs)
            self._cv.notify_all()
        return request

    def stop(self) -> None:
        with self._cv:
            self._stopping = True
            for queue in self._queues.values():
                for job in queue:
                    job.request.fail("Qwen3-TTS server stopped before synthesis completed.")
            self._queues.clear()
            self._pending = 0
            self._cv.notify_all()
        self._thread.join(timeout=5.0)

    def status(self) -> dict[str, Any]:
        with self._cv:
            return self._status_locked()

    def _status_locked(self) -> dict[str, Any]:
        avg_queue_ms = (
            (self._queue_wait_total / self._completed_chunks) * 1000.0
            if self._completed_chunks
            else 0.0
        )
        avg_inference_ms = (
            (self._inference_total / self._batch_calls) * 1000.0
            if self._batch_calls
            else 0.0
        )
        return {
            "scheduler_version": 2,
            "workers": self._workers,
            "queue_depth": self._pending,
            "max_queue": self._max_queue,
            "max_batch": self._max_batch,
            "safe_batch": self._safe_batch,
            "active_batch": self._active_batch,
            "batch_window_ms": self._batch_window_ms,
            "completed_requests": self._completed_requests,
            "completed_chunks": self._completed_chunks,
            "failed_requests": self._failed_requests,
            "batch_calls": self._batch_calls,
            "scalar_calls": self._scalar_calls,
            "batched_calls": self._batched_calls,
            "oom_backoffs": self._oom_backoffs,
            "avg_queue_ms": round(avg_queue_ms, 1),
            "avg_inference_ms": round(avg_inference_ms, 1),
        }

    def _run(self) -> None:
        while True:
            batch = self._take_batch()
            if batch is None:
                return
            if not batch:
                continue
            self._process_with_isolation(batch)

    def _take_batch(self) -> list[ChunkJob] | None:
        with self._cv:
            while not self._stopping and self._pending == 0:
                self._cv.wait()
            if self._stopping:
                return None

            target = max(1, min(self._max_batch, self._safe_batch))
            deadline = time.monotonic() + (self._batch_window_ms / 1000.0)
            while not self._stopping and self._pending < target:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self._cv.wait(timeout=remaining)
            if self._stopping:
                return None

            selected: list[ChunkJob] = []
            while len(selected) < target and self._pending > 0:
                made_progress = False
                for worker_id in list(self._queues.keys()):
                    queue = self._queues.get(worker_id)
                    if queue is None:
                        continue
                    while queue and queue[0].request.event.is_set():
                        queue.popleft()
                        self._pending -= 1
                    if not queue:
                        self._queues.pop(worker_id, None)
                        continue
                    selected.append(queue.popleft())
                    self._pending -= 1
                    made_progress = True
                    if not queue:
                        self._queues.pop(worker_id, None)
                    if len(selected) >= target:
                        break
                if not made_progress:
                    break

            now = time.monotonic()
            for job in selected:
                self._queue_wait_total += max(0.0, now - job.enqueued_at)
            self._active_batch = len(selected)
            self._cv.notify_all()
            return selected

    def _process_with_isolation(self, jobs: list[ChunkJob]) -> None:
        live = [job for job in jobs if not job.request.event.is_set()]
        if not live:
            self._finish_batch_metrics(0.0, 0)
            return

        started = time.monotonic()
        try:
            self._infer(live)
            self._finish_batch_metrics(time.monotonic() - started, len(live))
            return
        except Exception as exc:
            elapsed = time.monotonic() - started
            if self._is_oom(exc):
                self._record_oom(len(live))
                self._release_cuda_cache()
                if len(live) == 1:
                    live[0].request.fail(f"CUDA out of memory during Qwen3-TTS synthesis: {exc}")
                    self._record_failed_request()
                    self._finish_batch_metrics(elapsed, 0)
                    return
                split_size = max(1, len(live) // 2)
                with self._cv:
                    self._safe_batch = min(self._safe_batch, split_size)
                print(
                    f"[qwen3-tts] CUDA OOM at batch={len(live)}; "
                    f"retrying smaller groups, safe_batch={self._safe_batch}",
                    flush=True,
                )
                self._finish_batch_metrics(elapsed, 0)
                for start in range(0, len(live), split_size):
                    self._process_with_isolation(live[start:start + split_size])
                return

            if len(live) == 1:
                live[0].request.fail(exc)
                self._record_failed_request()
                self._finish_batch_metrics(elapsed, 0)
                print(
                    f"[qwen3-tts] isolated synthesis failure request={live[0].request.request_id}: {exc}",
                    flush=True,
                )
                return

            midpoint = len(live) // 2
            print(
                f"[qwen3-tts] batch={len(live)} failed; isolating as "
                f"{midpoint}+{len(live) - midpoint}: {exc}",
                flush=True,
            )
            self._finish_batch_metrics(elapsed, 0)
            self._process_with_isolation(live[:midpoint])
            self._process_with_isolation(live[midpoint:])

    def _infer(self, jobs: list[ChunkJob]) -> None:
        if len(jobs) == 1:
            # Preserve Qwen's known-good scalar API when only one worker is ready.
            # The previous scheduler always wrapped scalar inputs in lists, which
            # changed the model execution path even though no real batching existed.
            job = jobs[0]
            print(
                f"[qwen3-tts] GPU scalar worker={job.request.worker_id}",
                flush=True,
            )
            wavs, sample_rate = _MODEL.generate_custom_voice(
                text=job.text,
                language=job.request.language,
                speaker=job.request.speaker,
                instruct=job.request.instruct,
            )
            with self._cv:
                self._scalar_calls += 1
        else:
            texts = [job.text for job in jobs]
            languages = [job.request.language for job in jobs]
            speakers = [job.request.speaker for job in jobs]
            instructs = [job.request.instruct for job in jobs]
            print(
                f"[qwen3-tts] GPU batch={len(jobs)} workers="
                f"{','.join(dict.fromkeys(job.request.worker_id for job in jobs))}",
                flush=True,
            )
            wavs, sample_rate = _MODEL.generate_custom_voice(
                text=texts,
                language=languages,
                speaker=speakers,
                instruct=instructs,
            )
            with self._cv:
                self._batched_calls += 1

        if len(wavs) != len(jobs):
            raise RuntimeError(
                f"Qwen3-TTS returned {len(wavs)} waveforms for {len(jobs)} request(s)."
            )

        newly_completed: set[str] = set()
        for job, wav in zip(jobs, wavs):
            if job.request.event.is_set():
                continue
            job.request.finish_chunk(
                job.chunk_index,
                _to_audio_array(wav),
                int(sample_rate),
            )
            if job.request.event.is_set() and not job.request.error:
                newly_completed.add(job.request.request_id)

        with self._cv:
            self._completed_requests += len(newly_completed)

    def _finish_batch_metrics(self, elapsed: float, completed_chunks: int) -> None:
        with self._cv:
            self._batch_calls += 1
            self._inference_total += max(0.0, elapsed)
            self._completed_chunks += max(0, completed_chunks)
            self._active_batch = 0
            self._cv.notify_all()

    def _record_failed_request(self) -> None:
        with self._cv:
            self._failed_requests += 1

    def _record_oom(self, attempted_batch: int) -> None:
        with self._cv:
            self._oom_backoffs += 1
            self._safe_batch = min(self._safe_batch, max(1, attempted_batch // 2))

    @staticmethod
    def _is_oom(exc: BaseException) -> bool:
        if _TORCH is not None:
            try:
                if isinstance(exc, _TORCH.cuda.OutOfMemoryError):
                    return True
            except Exception:
                pass
        message = str(exc).lower()
        return "out of memory" in message and ("cuda" in message or "gpu" in message)

    @staticmethod
    def _release_cuda_cache() -> None:
        gc.collect()
        if _TORCH is None:
            return
        try:
            if _TORCH.cuda.is_available():
                _TORCH.cuda.empty_cache()
        except Exception:
            pass


class Handler(BaseHTTPRequestHandler):
    server_version = "ThreadGensQwen3TTS/2.1"

    def _send_json(self, status: int, value: dict[str, Any]) -> None:
        data = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_REQUEST_BYTES:
            raise ValueError("invalid request size")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("request body must be a JSON object")
        return value

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/health":
            scheduler = _SCHEDULER.status() if _SCHEDULER is not None else {}
            self._send_json(
                200,
                {
                    "ok": True,
                    "model": _MODEL_ID,
                    "device": _DEVICE,
                    "dtype": _DTYPE_NAME,
                    "audio_pipeline": "scalar-when-alone-worker-microbatch-no-time-stretch",
                    **scheduler,
                },
            )
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.rstrip("/")
        try:
            payload = self._read_json()
            if path == "/configure":
                if _SCHEDULER is None:
                    raise RuntimeError("Qwen3-TTS scheduler is not ready.")
                status = _SCHEDULER.configure(
                    workers=payload.get("workers"),
                    max_batch=payload.get("max_batch"),
                    batch_window_ms=payload.get("batch_window_ms"),
                    max_queue=payload.get("max_queue"),
                )
                self._send_json(200, {"ok": True, **status})
                return

            if path != "/synthesize":
                self._send_json(404, {"ok": False, "error": "not found"})
                return
            if _SCHEDULER is None:
                raise RuntimeError("Qwen3-TTS scheduler is not ready.")

            request = _SCHEDULER.submit(payload)
            request_timeout = _env_int(
                "THREADGENS_QWEN3_REQUEST_TIMEOUT",
                1800,
                30,
                7200,
            )
            if not request.event.wait(timeout=request_timeout):
                request.fail(
                    f"Qwen3-TTS request timed out after {request_timeout} seconds "
                    f"while waiting for the GPU scheduler."
                )
                raise TimeoutError(request.error)

            audio, sample_rate = request.wav_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("X-ThreadGens-Sample-Rate", str(sample_rate))
            self.send_header("X-ThreadGens-Request-Id", request.request_id)
            self.send_header("Content-Length", str(len(audio)))
            self.end_headers()
            self.wfile.write(audio)
        except TimeoutError as exc:
            self._send_json(504, {"ok": False, "error": str(exc)})
        except Exception as exc:
            traceback.print_exc()
            self._send_json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args: Any) -> None:
        if _truthy(os.environ.get("THREADGENS_QWEN3_VERBOSE")):
            super().log_message(fmt, *args)


def _initial_scheduler() -> BatchScheduler:
    workers = _env_int("THREADGENS_QWEN3_WORKERS", 1, 1, 32)
    max_batch = _env_int("THREADGENS_QWEN3_MAX_BATCH", workers, 1, 16)
    batch_window_ms = _env_int("THREADGENS_QWEN3_BATCH_WINDOW_MS", 75, 0, 2000)
    max_queue = _env_int(
        "THREADGENS_QWEN3_MAX_QUEUE",
        max(4, workers * 2),
        1,
        256,
    )
    return BatchScheduler(
        workers=workers,
        max_batch=max_batch,
        batch_window_ms=batch_window_ms,
        max_queue=max_queue,
    )


def main() -> int:
    global _SCHEDULER

    parser = argparse.ArgumentParser(description="Persistent ThreadGens Qwen3-TTS service")
    parser.add_argument("--host", default=os.environ.get("THREADGENS_QWEN3_HOST", DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=int(os.environ.get("THREADGENS_QWEN3_PORT", DEFAULT_PORT)))
    parser.add_argument("--check-only", action="store_true", help="Load the model, validate CUDA, then exit")
    args = parser.parse_args()

    _load_model()
    if args.check_only:
        print("[qwen3-tts] model/CUDA validation passed", flush=True)
        return 0

    _SCHEDULER = _initial_scheduler()
    print(
        "[qwen3-tts] scheduler ready: "
        f"workers={_SCHEDULER.status()['workers']} "
        f"max_batch={_SCHEDULER.status()['max_batch']} "
        f"window={_SCHEDULER.status()['batch_window_ms']}ms "
        f"max_queue={_SCHEDULER.status()['max_queue']}",
        flush=True,
    )

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[qwen3-tts] listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if _SCHEDULER is not None:
            _SCHEDULER.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
