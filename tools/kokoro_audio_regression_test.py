#!/usr/bin/env python3
import importlib.util
import os
import tempfile
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "kokoro_tts.py"
SPEC = importlib.util.spec_from_file_location("threadgens_kokoro_tts", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeTorchTensor:
    """Minimal torch-like object that reproduces the dtype boundary."""

    dtype = "torch.float32"

    def __init__(self, values):
        self._values = np.asarray(values, dtype=np.float32)

    def detach(self):
        return self

    def cpu(self):
        return self

    def numpy(self):
        return self._values


def verify_torch_conversion():
    chunk = FakeTorchTensor([0.1, -0.2, 0.3, -0.4])
    converted = MODULE.audio_chunk_to_numpy(chunk, np)

    assert isinstance(converted, np.ndarray)
    assert converted.dtype == np.float32
    assert converted.ndim == 1
    assert converted.shape == (4,)

    silence = np.zeros(5, dtype=np.float32)
    mixed = np.concatenate([converted, silence, converted]).astype(np.float32, copy=False)
    assert mixed.dtype == np.float32
    assert mixed.shape == (13,)


def verify_token_word_boundary_alignment():
    # Real Kokoro/Misaki output can split contractions and punctuation into
    # several MTokens. Punctuation MTokens can also have no acoustic timestamp.
    # The old lexical matcher dropped those tokens and randomly rejected valid
    # narration. The whitespace field is the authoritative original-word edge.
    tokens = [
        ("Don", "", 0.10, 0.20),
        ("'", "", None, None),
        ("t", " ", 0.20, 0.31),
        ("panic", " ", 0.36, 0.62),
        ("—", " ", None, None),
        ("Employee", " ", 0.80, 1.03),
        ("1", " ", 1.05, 1.16),
        ("isn", "", 1.22, 1.33),
        ("'", "", None, None),
        ("t", " ", 1.33, 1.45),
        ("late", "", 1.52, 1.72),
        (".", "", None, None),
    ]
    text = "Don't panic — Employee 1 isn't late."
    aligned = MODULE.align_model_tokens_to_input_words(text, tokens)

    assert [item[0] for item in aligned] == text.split()
    assert len(aligned) == 7
    assert aligned[0][1:] == (0.10, 0.31)
    assert aligned[1][1:] == (0.36, 0.62)
    # A standalone unspoken dash stays in the visible-word count and reveals
    # with the preceding exact spoken word rather than invalidating the clip.
    assert aligned[2][1:] == aligned[1][1:]
    assert aligned[3][1:] == (0.80, 1.03)
    assert aligned[5][1:] == (1.22, 1.45)
    assert aligned[6][1:] == (1.52, 1.72)


def verify_positional_mapping_survives_token_normalization():
    # If G2P normalizes display spelling but preserves the whitespace boundary,
    # the acoustic timestamp still belongs to the same source word by position.
    tokens = [
        ("seventeen", " ", 0.10, 0.44),
        ("minutes", " ", 0.48, 0.82),
        ("later", "", 0.86, 1.10),
    ]
    aligned = MODULE.align_model_tokens_to_input_words("17 minutes later", tokens)
    assert [item[0] for item in aligned] == ["17", "minutes", "later"]
    assert aligned[0][1:] == (0.10, 0.44)


def verify_exact_timing_requirement():
    previous = os.environ.get("THREADGENS_REQUIRE_EXACT_KOKORO_TIMING")
    os.environ["THREADGENS_REQUIRE_EXACT_KOKORO_TIMING"] = "1"
    try:
        with tempfile.TemporaryDirectory(prefix="threadgens-kokoro-timing-") as root:
            output = Path(root) / "voice.wav"
            output.write_bytes(b"placeholder")

            failed = False
            try:
                MODULE.write_timing_sidecar(output, "hello world", [], False)
            except RuntimeError as exc:
                failed = "model-derived timing" in str(exc)
            assert failed, "production must reject missing Kokoro model timings"
            assert not output.exists(), "rejected exact-timing WAV must be removed"

            output.write_bytes(b"placeholder")
            tokens = [("hello", 0.10, 0.30), ("world", 0.32, 0.55)]
            assert MODULE.write_timing_sidecar(output, "hello world", tokens, False)
            timing = MODULE.timing_path_for(output)
            lines = timing.read_text(encoding="utf-8").splitlines()
            assert lines[0] == MODULE.TIMING_HEADER
            assert len(lines) == 3
    finally:
        if previous is None:
            os.environ.pop("THREADGENS_REQUIRE_EXACT_KOKORO_TIMING", None)
        else:
            os.environ["THREADGENS_REQUIRE_EXACT_KOKORO_TIMING"] = previous


def main():
    verify_torch_conversion()
    verify_token_word_boundary_alignment()
    verify_positional_mapping_survives_token_normalization()
    verify_exact_timing_requirement()
    print("Kokoro audio and model-word timing regressions passed.")


if __name__ == "__main__":
    main()
