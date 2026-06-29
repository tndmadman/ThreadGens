#!/usr/bin/env python3
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="ThreadGens Kokoro TTS helper")
    parser.add_argument("--text-file", required=True, help="UTF-8 text file to read")
    parser.add_argument("--output", required=True, help="Output WAV path")
    parser.add_argument("--voice", default="af_heart", help="Kokoro voice name, for example af_heart")
    parser.add_argument("--lang", default="a", help="Kokoro language code. Default 'a' = American English")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed")
    args = parser.parse_args()

    text_path = Path(args.text_file)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    text = text_path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit("No text to speak.")

    try:
        from kokoro import KPipeline
        import numpy as np
        import soundfile as sf
    except Exception as exc:
        raise SystemExit(
            "Kokoro dependencies are missing. Install with: python -m pip install kokoro soundfile numpy\n"
            f"Import error: {exc}"
        )

    pipeline = KPipeline(lang_code=args.lang)
    generator = pipeline(text, voice=args.voice, speed=args.speed)

    chunks = []
    for _, _, audio in generator:
        chunks.append(audio)

    if not chunks:
        raise SystemExit("Kokoro produced no audio.")

    audio = np.concatenate(chunks)
    sf.write(str(output_path), audio, 24000)


if __name__ == "__main__":
    main()
