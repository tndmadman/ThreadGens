#!/usr/bin/env python3
import importlib.util
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


def main():
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

    print("Kokoro torch-audio normalization regression passed.")


if __name__ == "__main__":
    main()
