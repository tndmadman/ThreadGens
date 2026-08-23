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
KOKORO_REPO_ID = "hexgrad/Kokoro-82M"


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


def _model_token_parts(token):
    """Normalize old test tuples and the richer production token shape.

    Production tokens are (text, trailing_whitespace, start, end). The legacy
    three-field shape remains accepted for small unit tests and callers that
    already provide one timestamped token per word.
    """
    if len(token) >= 4:
        text, whitespace, start, end = token[:4]
    elif len(token) == 3:
        text, start, end = token
        whitespace = " "
    else:
        raise ValueError("Malformed Kokoro model token.")
    return str(text or ""), str(whitespace or ""), start, end


def group_model_tokens_into_words(model_tokens):
    """Collapse Kokoro MTokens into the original whitespace-delimited words.

    Kokoro/Misaki can represent one visible word as several MTokens: contractions,
    punctuation, quotes, symbols, and similar graphemes are common examples.
    Some of those punctuation MTokens intentionally have no acoustic timestamp.
    The token.whitespace field is authoritative for the original word boundary,
    so preserve every token and group first; only then derive the word's timing
    from the timestamped acoustic tokens inside that group.
    """
    grouped = []
    text_parts = []
    starts = []
    ends = []

    def flush_word():
        nonlocal text_parts, starts, ends
        text = "".join(text_parts).strip()
        if text:
            start = min(starts) if starts else None
            end = max(ends) if ends else None
            grouped.append((text, start, end))
        text_parts = []
        starts = []
        ends = []

    for token in model_tokens:
        token_text, whitespace, start, end = _model_token_parts(token)
        text_parts.append(token_text)
        if start is not None and end is not None:
            start_value = float(start)
            end_value = float(end)
            if end_value > start_value:
                starts.append(start_value)
                ends.append(end_value)
        if whitespace:
            flush_word()

    flush_word()
    return grouped


def align_model_tokens_to_input_words(text, model_tokens):
    """Map exact Kokoro acoustic timestamps back to the original visible words.

    This intentionally does not require token spelling to equal source spelling.
    Kokoro's G2P/tokenizer may split or normalize graphemes while preserving the
    original whitespace boundaries. Position and whitespace therefore provide a
    safer mapping than lexical equality, while start/end values still come only
    from Kokoro's model-predicted token durations.
    """
    source_words = re.findall(r"\S+", text)
    grouped = group_model_tokens_into_words(model_tokens)
    if not source_words or len(grouped) != len(source_words):
        return []

    aligned = []
    for source_word, (_, start, end) in zip(source_words, grouped):
        if start is None or end is None or end <= start:
            # Standalone punctuation/emoji can be a visible whitespace token but
            # has no acoustic duration because nothing is spoken. Keep it in the
            # reveal word count and anchor it to an adjacent exact spoken word.
            # A lexical word without acoustic timing is still a hard failure.
            if normalized_word(source_word):
                return []
            aligned.append([source_word, None, None])
        else:
            aligned.append([source_word, float(start), float(end)])

    for index, item in enumerate(aligned):
        if item[1] is not None:
            continue
        previous = next(
            (aligned[j] for j in range(index - 1, -1, -1) if aligned[j][1] is not None),
            None,
        )
        following = next(
            (aligned[j] for j in range(index + 1, len(aligned)) if aligned[j][1] is not None),
            None,
        )
        anchor = previous if previous is not None else following
        if anchor is None:
            return []
        item[1] = anchor[1]
        item[2] = anchor[2]

    previous_start = -1.0
    result = []
    for source_word, start, end in aligned:
        if start + 0.0001 < previous_start or end <= start:
            return []
        result.append((source_word, start, end))
        previous_start = start
    return result


def write_timing_sidecar(output_path, text, model_tokens, verbose):
    timing_path = timing_path_for(output_path)
    delete_if_present(timing_path)

    aligned = align_model_tokens_to_input_words(text, model_tokens)
    if not aligned:
        if require_exact_timing():
            delete_if_present(output_path)
            grouped_count = len(group_model_tokens_into_words(model_tokens)) if model_tokens else 0
            source_count = len(re.findall(r"\S+", text))
            raise RuntimeError(
                "Kokoro token timing could not be mapped safely to every narrated word "
                f"(source words={source_count}, model word groups={grouped_count}). "
                "Production requires model-derived timing, so this WAV was rejected instead of falling back to estimated timing."
            )
        log("exact token timing unavailable; Java will use its measured-duration fallback", verbose)
        return False

    lines = [TIMING_HEADER]
    for word, start, end in aligned:
        encoded = base64.urlsafe_b64encode(word.encode("utf-8")).decode("ascii").rstrip("=")
        lines.append(f"word\t{start:.6f}\t{end:.6f}\t{encoded}")
    timing_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"wrote model-derived timing sidecar: {timing_path} ({len(aligned)} words)", verbose)
    return True


def _token_whitespace(token):
    whitespace = getattr(token, "whitespace", "")
    if whitespace is True:
        return " "
    if not whitespace:
        return ""
    return str(whitespace)


def _optional_timestamp(value):
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


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
    chunk_model_tokens = []
    with quiet_stderr(verbose):
        pipeline = KPipeline(lang_code=args.lang, repo_id=KOKORO_REPO_ID)
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
            timed_count = 0
            if tokens:
                for token in tokens:
                    token_text = str(getattr(token, "text", "") or "")
                    whitespace = _token_whitespace(token)
                    start_ts = _optional_timestamp(getattr(token, "start_ts", None))
                    end_ts = _optional_timestamp(getattr(token, "end_ts", None))
                    if start_ts is not None and end_ts is not None and end_ts > start_ts:
                        timed_count += 1
                    else:
                        start_ts = None
                        end_ts = None
                    relative_tokens.append((token_text, whitespace, start_ts, end_ts))
            chunk_model_tokens.append(relative_tokens)
            log(
                f"generated audio chunk {index} with {len(relative_tokens)} model token(s), "
                f"{timed_count} acoustically timed",
                verbose,
            )

    if not chunks:
        raise SystemExit("Kokoro produced no audio.")

    pause_ms = max(0, min(2000, args.sentence_pause_ms))
    silence = np.zeros(int(SAMPLE_RATE * pause_ms / 1000), dtype=np.float32) if pause_ms > 0 else None
    combined = []
    model_tokens = []
    sample_cursor = 0
    for index, chunk in enumerate(chunks):
        if index > 0 and silence is not None:
            combined.append(silence)
            sample_cursor += len(silence)
        chunk_start = sample_cursor / SAMPLE_RATE
        combined.append(chunk)
        for token_text, whitespace, start_ts, end_ts in chunk_model_tokens[index]:
            absolute_start = None if start_ts is None else chunk_start + start_ts
            absolute_end = None if end_ts is None else chunk_start + end_ts
            model_tokens.append((token_text, whitespace, absolute_start, absolute_end))
        sample_cursor += len(chunk)

    audio = np.concatenate(combined).astype(np.float32, copy=False)
    log(f"writing WAV: {output_path}", verbose)
    sf.write(str(output_path), audio, SAMPLE_RATE)
    try:
        write_timing_sidecar(output_path, text, model_tokens, verbose)
    except Exception:
        delete_if_present(timing_path_for(output_path))
        raise
    log(f"done in {time.time() - started:.1f}s", verbose)


if __name__ == "__main__":
    main()
