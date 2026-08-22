# P0 content-originality pipeline

`P0Runner` is the production/default ThreadGens entry point for the P0 content-quality work. `CheckedRunner` remains available as the low-level/raw compatibility renderer and smoke-test target.

## Production flow

1. Resolve a genuinely different content format (`--format auto` by default).
2. For auto-generated scripts, add format-specific narrative instructions to the local LLM prompt.
3. Render the normal platform images and TTS audio through the existing checked renderer. Video flags are intentionally delayed so OP-image overlays are complete first.
4. Compare the candidate script against persistent local generation history.
5. If an auto-generated candidate is too repetitive, regenerate it with explicit anti-repeat feedback. Repeated failure stops the run instead of silently publishing a low-novelty script.
6. Remove synthetic engagement claims and synthetic X verification markers from the rendered output images.
7. Build a format-specific presentation composition.
8. Render each narrated segment with continuous motion instead of holding a static PNG for the full narration.
9. Stitch the dynamic clips using the existing H.264/AAC final-video path.
10. Only after a successful run, append the accepted script/format/topic fingerprint to local history.

## Content formats

`--format auto` rotates away from recently used formats. Explicit values are:

- `thread_story` — threaded story progression.
- `confession` — first-person reveal with a quote-focused visual composition.
- `debate` — alternating two-sided positions and left/right visual grammar.
- `best_answers` — independent ranked responses.
- `escalating_conversation` — short, directly responsive conversation turns with alternating message bubbles.

These formats change both the generation guidance and the video composition. They are not just background/color variants.

## Novelty guard

Default history file: `data/generation_history.jsonl` (git-ignored).

The guard uses deterministic local checks so it does not require another network service or embedding model:

- normalized SHA-256 exact-duplicate detection,
- token-shingle overlap,
- hook overlap,
- stop-word-filtered term-frequency cosine similarity,
- sentence/line structural fingerprints,
- repeated-line penalties.

Useful options:

- `--history-file PATH`
- `--history-limit N` (default 500 recent entries compared)
- `--novelty-threshold N` (default 48/100)
- `--novelty-retries N` (default 4 retries after the first candidate)
- `--no-novelty` for intentional one-off/debug runs

Auto-generated content is regenerated when rejected. Manual/supplied text is never silently rewritten; the runner emits a warning and continues so explicit user input remains authoritative.

## Integrity cleanup

Production output hides generated engagement metrics instead of presenting randomly fabricated likes/upvotes/reposts/views as real platform activity. It also removes the synthetic X verification badge. The footer is replaced by a neutral `Fictional thread • engagement hidden` label.

Use `--no-integrity-sanitize` only when intentionally debugging the raw legacy renderer.

## Dynamic video

The P0 video path requires both `ffmpeg` and `ffprobe`. If an explicit FFmpeg executable path is supplied, ThreadGens first looks for a sibling `ffprobe` executable.

The encoded delivery format remains conventional:

- H.264 / libx264
- AAC audio
- `yuv420p`
- `+faststart`

The change is in content presentation: every normal narrated segment receives continuous scale/crop movement, with a distinct motion profile and presentation composition for its selected content format.

## Compatibility

Existing scripts that invoke `redditTxtToImg.OpImageVideoSafeRunner` continue to work. That class is now a compatibility alias for `P0Runner`, so the Windows interactive and batch paths inherit P0 behavior without requiring users to change their commands.

`Runner` and the Gradle application/JAR manifest also point to `P0Runner`.

## Validation

`P0SmokeTest` covers:

- accepting first-time content,
- rejecting exact historical duplicates,
- scoring distinct content above duplicates,
- automatic format rotation,
- explicit format selection,
- synthetic verified-marker cleanup,
- successful format-specific image composition for all five formats.

GitHub Actions runs those tests in addition to the existing raw Reddit/X and OP-image compatibility smoke tests and a P0 integration image run.
