#!/usr/bin/env python3
import argparse
import base64
import contextlib
import os
import re
import time
import warnings
from pathlib import Path


TIMING_HEADER = "threadgens-kokoro-timing-v1"
SAMPLE_RATE = 24000


def is_verbose(args):
    env_value = os.environ.get("THREADGENS_KOKORO_VERBOSE", os.environ.get("KOKORO_VERBOSE", "0"))
    return args.verbose or env_value.strip().lower() in {"1", "true", "yes", "y", "on"}


def require_exact_timing():
    value = os.environ.get("THREADGENS_REQUIRE_EXACT_KOKORO_TIMING", "0")
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


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


def normalized_word(value):
    return re.sub(r"[^\w]+", "", value or "", flags=re.UNICODE).lower().replace("_", "")


def timing_path_for(output_path):
    return output_path.with_name(output_path.stem + ".timing.tsv")


def delete_if_present(path):
    try:
        path.unlink(missing_ok=True)
    except TypeError:
        if path.exists():
            path.unlink()


def audio_chunk_to_numpy(audio, np):
    """Return one Kokoro chunk as a flat float32 NumPy array."""
    value = audio
    detach = getattr(value, "detach", None)
    if callable(detach):
        value = detach()
    cpu = getattr(value, "cpu", None)
    if callable(cpu):
        value = cpu()
    as_numpy = getattr(value, "numpy", None)
    if callable(as_numpy):
        value = as_numpy()

    array = np.asarray(value)
    if array.size == 0:
        raise ValueError("Kokoro produced an empty audio chunk.")
    array = np.squeeze(array)
    if array.ndim != 1:
        array = array.reshape(-1)
    return array.astype(np.float32, copy=False)


def align_model_tokens_to_input_words(text, timed_tokens):
    source_words = re.findall(r"\S+", text)
    tokens = [token for token in timed_tokens if normalized_word(token[0])]
    if not source_words or not tokens:
        return []

    aligned = []
    token_index = 0
    for source_word in source_words:
        target = normalized_word(source_word)
        if not target:
            continue

        match_start = token_index
        match_end = None
        combined = ""
        for j in range(token_index, min(len(tokens), token_index + 8)):
            combined += normalized_word(tokens[j][0])
            if combined == target:
                match_end = j
                break
            if target.startswith(combined):
                continue
            break

        if match_end is None:
            for j in range(token_index, min(len(tokens), token_index + 4)):
                if normalized_word(tokens[j][0]) == target:
                    match_start = j
                    match_end = j
                    break

        if match_end is None:
            return []

        start = tokens[match_start][1]
        end = tokens[match_end][2]
        if end <= start:
            return []
        aligned.append((source_word, start, end))
        token_index = match_end + 1

    return aligned if len(aligned) == len(source_words) else []


def write_timing_sidecar(output_path, text, timed_tokens, verbose):
    timing_path = timing_path_for(output_path)
    delete_if_present(timing_path)

    aligned = align_model_tokens_to_input_words(text, timed_tokens)
    if not aligned:
        if require_exact_timing():
            delete_if_present(output_path)
            raise RuntimeError(
                "Kokoro did not return an exact model timestamp for every narrated word. "
                "Production requires exact timing, so this WAV was rejected instead of falling back to estimated timing."
            )
        log("exact token timing unavailable; Java will use its measured-duration fallback", verbose)
        return False

    lines = [TIMING_HEADER]
    for word, start, end in aligned:
        encoded = base64.urlsafe_b64encode(word.encode("utf-8")).decode("ascii").rstrip("=")
        lines.append(f"word\t{start:.6f}\t{end:.6f}\t{encoded}")
    timing_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"wrote exact timing sidecar: {timing_path} ({len(aligned)} words)", verbose)
    return True


def main():
    parser = argparse.ArgumentParser(description="ThreadGens Kokoro TTS helper")
    parser.add_argument("--text-file", required=True, help="UTF-8 text file to read")
    parser.add_argument("--output", required=True, help="Output WAV path")
    parser.add_argument("--voice", default="af_heart", help="Kokoro voice name, for example af_heart")
    parser.add_argument("--lang", default="a", help="Kokoro language code. Default 'a' = American English")
    parser.add_argument("--speed", type=float, default=1.0, help="Speech speed")
    parser.add_argument(
        "--sentence-pause-ms",
        type=int,
        default=180,
        help="Silence inserted between Kokoro output segments",
    )
    parser.add_argument("--verbose", action="store_true", help="Print Kokoro progress and dependency warnings")
    args = parser.parse_args()

    verbose = is_verbose(args)
    configure_quiet_mode(verbose)

    started = time.time()
    text_path = Path(args.text_file)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    delete_if_present(timing_path_for(output_path))

    log(f"reading text: {text_path}", verbose)
    text = text_path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit("No text to speak.")

    log("importing Kokoro dependencies...", verbose)
    try:
        with quiet_stderr(verbose):
            from kokoro import KPipeline
            import numpy as np
            import soundfile as sf
    except Exception as exc:
        raise SystemExit(
            "Kokoro dependencies are missing. Run setup_windows.bat again so it installs into .venv-kokoro.\n"
            f"Import error: {exc}"
        )

    log(f"loading Kokoro pipeline lang={args.lang} voice={args.voice} speed={args.speed}", verbose)
    chunks = []
    chunk_token_timings = []
    with quiet_stderr(verbose):
        pipeline = KPipeline(lang_code=args.lang)
        generator = pipeline(text, voice=args.voice, speed=args.speed)
        for index, result in enumerate(generator, start=1):
            if hasattr(result, "audio"):
                audio = result.audio
                tokens = getattr(result, "tokens", None)
            else:
                _, _, audio = result
                tokens = None
            chunks.append(audio_chunk_to_numpy(audio, np))

            relative_tokens = []
            if tokens:
                for token in tokens:
                    start_ts = getattr(token, "start_ts", None)
                    end_ts = getattr(token, "end_ts", None)
                    token_text = getattr(token, "text", "")
                    if start_ts is None or end_ts is None or end_ts <= start_ts:
                        continue
                    relative_tokens.append((str(token_text), float(start_ts), float(end_ts)))
            chunk_token_timings.append(relative_tokens)
            log(f"generated audio chunk {index} with {len(relative_tokens)} timed token(s)", verbose)

    if not chunks:
        raise SystemExit("Kokoro produced no audio.")

    pause_ms = max(0, min(2000, args.sentence_pause_ms))
    silence = np.zeros(int(SAMPLE_RATE * pause_ms / 1000), dtype=np.float32) if pause_ms > 0 else None
    combined = []
    timed_tokens = []
    sample_cursor = 0
    for index, chunk in enumerate(chunks):
        if index > 0 and silence is not None:
            combined.append(silence)
            sample_cursor += len(silence)
        chunk_start = sample_cursor / SAMPLE_RATE
        combined.append(chunk)
        for token_text, start_ts, end_ts in chunk_token_timings[index]:
            timed_tokens.append((token_text, chunk_start + start_ts, chunk_start + end_ts))
        sample_cursor += len(chunk)

    audio = np.concatenate(combined).astype(np.float32, copy=False)
    log(f"writing WAV: {output_path}", verbose)
    sf.write(str(output_path), audio, SAMPLE_RATE)
    try:
        write_timing_sidecar(output_path, text, timed_tokens, verbose)
    except Exception:
        delete_if_present(timing_path_for(output_path))
        raise
    log(f"done in {time.time() - started:.1f}s", verbose)


if __name__ == "__main__":
    main()
