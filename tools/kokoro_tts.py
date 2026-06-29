#!/usr/bin/env python3
import argparse
import contextlib
import os
import sys
import time
import warnings
from pathlib import Path


def is_verbose(args):
    env_value = os.environ.get("THREADGENS_KOKORO_VERBOSE", os.environ.get("KOKORO_VERBOSE", "0"))
    return args.verbose or env_value.strip().lower() in {"1", "true", "yes", "y", "on"}


def configure_quiet_mode(verbose):
    if verbose:
        return
    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    os.environ.setdefault("PYTHONWARNINGS", "ignore")
    warnings.filterwarnings("ignore")


@contextlib.contextmanager
def quiet_stderr(verbose):
    if verbose:
        yield
        return
    with open(os.devnull, "w", encoding="utf-8") as devnull:
        with contextlib.redirect_stderr(devnull):
            yield


def log(message, verbose):
    if verbose:
        print(f"[kokoro] {message}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="ThreadGens Kokoro TTS helper")
    parser.add_argument("--text-file", required=True, help="UTF-8 text file to read")
    parser.add_argument("--output", required=True, help="Output WAV path")
    parser.add_argument("--voice", default="af_heart", help="Kokoro voice name, for example af_heart")
    parser.add_argument("--lang", default="a", help="Kokoro language code. Default 'a' = American English")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed")
    parser.add_argument("--verbose", action="store_true", help="Print Kokoro progress and dependency warnings")
    args = parser.parse_args()

    verbose = is_verbose(args)
    configure_quiet_mode(verbose)

    started = time.time()
    text_path = Path(args.text_file)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    log(f"reading text: {text_path}", verbose)
    text = text_path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit("No text to speak.")
    log(f"text length: {len(text)} chars", verbose)

    log("importing Kokoro dependencies...", verbose)
    try:
        with quiet_stderr(verbose):
            from kokoro import KPipeline
            import numpy as np
            import soundfile as sf
            try:
                import torch
                log(f"torch cuda available: {torch.cuda.is_available()}", verbose)
            except Exception as torch_exc:
                log(f"torch cuda check skipped: {torch_exc}", verbose)
    except Exception as exc:
        raise SystemExit(
            "Kokoro dependencies are missing. Run setup_windows.bat again so it installs into .venv-kokoro.\n"
            f"Import error: {exc}"
        )

    log(f"loading Kokoro pipeline lang={args.lang} voice={args.voice} speed={args.speed}", verbose)
    with quiet_stderr(verbose):
        pipeline = KPipeline(lang_code=args.lang)

        log("starting speech generation...", verbose)
        generator = pipeline(text, voice=args.voice, speed=args.speed)

        chunks = []
        for index, (_, _, audio) in enumerate(generator, start=1):
            chunks.append(audio)
            log(f"generated audio chunk {index}", verbose)

    if not chunks:
        raise SystemExit("Kokoro produced no audio.")

    log(f"combining {len(chunks)} chunk(s)...", verbose)
    audio = np.concatenate(chunks)

    log(f"writing WAV: {output_path}", verbose)
    sf.write(str(output_path), audio, 24000)
    elapsed = time.time() - started
    log(f"done in {elapsed:.1f}s", verbose)


if __name__ == "__main__":
    main()
