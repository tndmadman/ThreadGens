#!/usr/bin/env python3
"""Persistent local Qwen3-TTS service for ThreadGens.

The service binds to localhost by default, loads the Qwen CustomVoice model
once, serializes GPU inference, and returns clean PCM WAV audio.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import threading
import traceback
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
_GENERATION_LOCK = threading.Lock()


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
    """Keep normal narration in one coherent generation.

    Only unusually long inputs are grouped into paragraph-sized chunks. We do
    not generate one clip per sentence because restarting the voice on every
    sentence produces robotic cadence and inconsistent room tone.
    """
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


def _synthesize(payload: dict[str, Any]) -> tuple[bytes, int]:
    text = _normalize_text(str(payload.get("text") or ""))
    if not text:
        raise ValueError("text is required")

    speaker = _speaker_name(str(payload.get("speaker") or "Ryan"))
    language = _language_name(str(payload.get("language") or "English"))
    delivery = str(payload.get("delivery") or "natural")
    speed = min(1.60, max(0.60, float(payload.get("speed") or 1.0)))
    pause_ms = min(800, max(0, int(payload.get("sentence_pause_ms") or 0)))
    instruct = str(payload.get("instruct") or "").strip() or _delivery_instruction(delivery, speed)

    chunks = _chunk_text(text)
    if not chunks:
        raise ValueError("text is empty after normalization")

    with _GENERATION_LOCK:
        if len(chunks) == 1:
            wavs, sample_rate = _MODEL.generate_custom_voice(
                text=chunks[0],
                language=language,
                speaker=speaker,
                instruct=instruct,
            )
        else:
            wavs, sample_rate = _MODEL.generate_custom_voice(
                text=chunks,
                language=[language] * len(chunks),
                speaker=[speaker] * len(chunks),
                instruct=[instruct] * len(chunks),
            )

        audio_chunks = [
            _edge_fade(_to_audio_array(wav), int(sample_rate))
            for wav in wavs
        ]
        if not audio_chunks:
            raise RuntimeError("Qwen3-TTS returned no audio.")

        if len(audio_chunks) > 1:
            # This pause is only between long safety chunks, never between every
            # sentence. Natural sentence timing is left to Qwen.
            gap_ms = max(80, pause_ms)
            silence = _NP.zeros(
                int(sample_rate * gap_ms / 1000.0),
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
        _SF.write(output, audio, int(sample_rate), format="WAV", subtype="PCM_16")
        return output.getvalue(), int(sample_rate)


class Handler(BaseHTTPRequestHandler):
    server_version = "ThreadGensQwen3TTS/1.1"

    def _send_json(self, status: int, value: dict[str, Any]) -> None:
        data = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/health":
            self._send_json(
                200,
                {
                    "ok": True,
                    "model": _MODEL_ID,
                    "device": _DEVICE,
                    "dtype": _DTYPE_NAME,
                    "audio_pipeline": "coherent-generation-no-time-stretch",
                },
            )
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") != "/synthesize":
            self._send_json(404, {"ok": False, "error": "not found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_REQUEST_BYTES:
                raise ValueError("invalid request size")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            audio, sample_rate = _synthesize(payload)
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("X-ThreadGens-Sample-Rate", str(sample_rate))
            self.send_header("Content-Length", str(len(audio)))
            self.end_headers()
            self.wfile.write(audio)
        except Exception as exc:
            traceback.print_exc()
            self._send_json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args: Any) -> None:
        if _truthy(os.environ.get("THREADGENS_QWEN3_VERBOSE")):
            super().log_message(fmt, *args)


def main() -> int:
    parser = argparse.ArgumentParser(description="Persistent ThreadGens Qwen3-TTS service")
    parser.add_argument("--host", default=os.environ.get("THREADGENS_QWEN3_HOST", DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=int(os.environ.get("THREADGENS_QWEN3_PORT", DEFAULT_PORT)))
    parser.add_argument("--check-only", action="store_true", help="Load the model, validate CUDA, then exit")
    args = parser.parse_args()

    _load_model()
    if args.check_only:
        print("[qwen3-tts] model/CUDA validation passed", flush=True)
        return 0

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[qwen3-tts] listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
