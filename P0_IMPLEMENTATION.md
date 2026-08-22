# P0 content-originality pipeline

`P0Entrypoint` is the production/default ThreadGens entry point for the P0 content-quality work. It owns hidden format-aware auto generation, then delegates the accepted script to `P0Runner` for rendering, integrity cleanup, dynamic video, and history persistence. `CheckedRunner` remains available as the low-level/raw compatibility renderer and smoke-test target.

## Production flow

1. Resolve a genuinely different content format. `--format auto` first chooses formats compatible with the title/body (for example, disputes prefer debate while story-continuation prompts prefer story-compatible formats), then uses persistent history to avoid recently overused formats.
2. For `--auto`, generate replies with `FormatAwareTextGenerator`. Format/originality instructions are placed only in the hidden Ollama prompt; the visible title and original post body are preserved exactly.
3. Compare each auto-generated candidate against persistent local generation history before rendering.
4. If a candidate is too repetitive, regenerate it with explicit anti-repeat feedback. Repeated failure stops the run instead of silently publishing a low-novelty script.
5. Pass the accepted generated script to the normal checked platform renderer as explicit input, so the legacy auto generator cannot leak hidden prompt guidance into visible content.
6. Render platform images and TTS audio. Video flags are intentionally delayed so OP-image overlays are complete first.
7. Remove synthetic engagement claims and synthetic X verification markers from the rendered output images.
8. Build a format-specific presentation composition.
9. Render each narrated segment with continuous motion instead of holding a static PNG for the full narration.
10. Stitch the dynamic clips using the existing H.264/AAC final-video path.
11. Only after a successful production run, append the accepted script/format/topic fingerprint to local history.

Before a requested video run begins, ThreadGens deletes matching stale segment/final MP4 outputs. A failed generation therefore cannot be mistaken for a newly successful video because an old file happened to remain on disk.

## Content formats

`--format auto` is recommended. Auto selection combines content fit with recent usage rather than choosing blindly. Explicit values are:

- `thread_story` — threaded story progression.
- `confession` — first-person reveal with a quote-focused visual composition.
- `debate` — alternating two-sided positions and left/right visual grammar.
- `best_answers` — independent ranked responses.
- `escalating_conversation` — short, directly responsive conversation turns with alternating message bubbles.

These formats change both the hidden generation prompt and the video composition. They are not just background/color variants.

Both Windows generation paths expose the same format choices:

- `tools/run_ai_windows.ps1` offers an interactive selector.
- `tools/batch_create_videos.ps1` accepts `-Format auto|thread_story|confession|debate|best_answers|escalating_conversation`.

## Hidden-prompt isolation

The visible OP/title must never be used as a transport for internal generation instructions. `P0Entrypoint` therefore generates the auto script before the platform renderer runs, preserving the original `--post-title` and `--topic` values for display while sending format and novelty guidance only to Ollama.

After generation, `--auto` is removed and the generated script file replaces the comments input. This prevents the legacy platform-specific auto generator from running a second time.

Manual runs are also isolated from stale `output/script/generated_comments.txt` files so an old auto script cannot silently replace explicit user input during novelty/caption processing.

For X generation, the P0 entry point also normalizes the no-style case so the Reddit-oriented default title (`Finish this story in the comments`) cannot accidentally influence a normal X post when the caller did not supply `--post-title`.

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

Auto-generated content is regenerated when rejected. Manual/supplied text is never silently rewritten; the renderer emits a warning and continues so explicit user input remains authoritative. Rejected manual duplicates are not added to history again.

The corresponding persistent defaults live in `defaults.txt`:

```properties
format=auto
historyFile=data/generation_history.jsonl
historyLimit=500
noveltyThreshold=48
noveltyRetries=4
noveltyEnabled=true
integritySanitize=true
```

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

Existing scripts that invoke `redditTxtToImg.OpImageVideoSafeRunner` continue to work. That class is now a compatibility alias for `P0Entrypoint`, so the Windows interactive and batch paths inherit P0 behavior without requiring command changes.

`Runner` and the Gradle application/JAR manifest also point to `P0Entrypoint`.

`P0Runner` remains the rendering/orchestration engine underneath the safe entry point. Production callers should use `P0Entrypoint` (or the existing Windows scripts) rather than invoking raw/legacy entry points directly. Direct `P0Runner --auto` calls are routed back through `P0Entrypoint` as an additional guard against bypassing hidden-prompt isolation.

## Validation

`P0SmokeTest` covers:

- accepting first-time content,
- rejecting exact historical duplicates,
- scoring distinct content above duplicates,
- automatic format rotation,
- content-aware format selection,
- explicit format selection,
- synthetic verified-marker cleanup,
- successful format-specific image composition for all five formats,
- removal of legacy `--auto` before rendering a generated script,
- preservation of the visible original topic,
- hidden format/novelty instructions not becoming the visible OP,
- explicit propagation of the resolved format,
- manual-input protection from stale generated-script paths,
- stale segment/final MP4 cleanup.

The motion filter was also exercised against real local `ffmpeg`/`ffprobe` for all five formats and produced valid H.264/AAC MP4 clips.

GitHub Actions runs the P0 unit tests in addition to the existing raw Reddit/X and OP-image compatibility smoke tests and a P0 integration image run.
