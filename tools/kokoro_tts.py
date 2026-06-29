#!/usr/bin/env python3
import argparse
import sys
import time
from pathlib import Path


def log(message):
    print(f"[kokoro] {message}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="ThreadGens Kokoro TTS helper")
    parser.add_argument("--text-file", required=True, help="UTF-8 text file to read")
    parser.add_argument("--output", required=True, help="Output WAV path")
    parser.add_argument("--voice", default="af_heart", help="Kokoro voice name, for example af_heart")
    parser.add_argument("--lang", default="a", help="Kokoro language code. Default 'a' = American English")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed")
    args = parser.parse_args()

    started = time.time()
    text_path = Path(args.text_file)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    log(f"reading text: {text_path}")
    text = text_path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit("No text to speak.")
    log(f"text length: {len(text)} chars")

    log("importing Kokoro dependencies...")
    try:
        from kokoro import KPipeline
        import numpy as np
        import soundfile as sf
        try:
            import torch
            log(f"torch cuda available: {torch.cuda.is_available()}")
        except Exception as torch_exc:
            log(f"torch cuda check skipped: {torch_exc}")
    except Exception as exc:
        raise SystemExit(
            "Kokoro dependencies are missing. Run setup_windows.bat again so it installs into .venv-kokoro.\n"
            f"Import error: {exc}"
        )

    log(f"loading Kokoro pipeline lang={args.lang} voice={args.voice} speed={args.speed}")
    pipeline = KPipeline(lang_code=args.lang)

    log("starting speech generation...")
    generator = pipeline(text, voice=args.voice, speed=args.speed)

    chunks = []
    for index, (_, _, audio) in enumerate(generator, start=1):
        chunks.append(audio)
        log(f"generated audio chunk {index}")

    if not chunks:
        raise SystemExit("Kokoro produced no audio.")

    log(f"combining {len(chunks)} chunk(s)...")
    audio = np.concatenate(chunks)

    log(f"writing WAV: {output_path}")
    sf.write(str(output_path), audio, 24000)
    elapsed = time.time() - started
    log(f"done in {elapsed:.1f}s")


if __name__ == "__main__":
    main()
