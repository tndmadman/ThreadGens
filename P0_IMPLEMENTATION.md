# P0 content-originality pipeline

`P0Entrypoint` is the production ThreadGens entry point. `CheckedRunner`, `OpImageVideoSafeRunner`, `Runner`, Gradle application runs, the runnable JAR, the GUI, and the Windows launchers all route normal work through the same P0 pipeline. `RawCheckedRunner` is the explicit compatibility/debug path when raw platform rendering is intentionally required.

## Production flow

1. Resolve a content-appropriate format and narrative substyle while avoiding recent cooldowns.
2. For `--auto`, generate with hidden format/originality instructions while preserving the visible title/body exactly.
3. Validate persistent generation history. Existing malformed history fails closed instead of behaving like a clean slate.
4. Run deterministic novelty checks: normalized hashes, shingles, hook overlap, term-frequency similarity, structural fingerprints, and repeated-line penalties.
5. Run semantic novelty against recent scripts with local Ollama embeddings (`nomic-embed-text` by default). This catches substantially reworded versions of the same underlying story/joke/conflict.
6. Regenerate rejected auto content with explicit anti-repeat feedback. Exhausted retries stop the run.
7. Render source-clean Reddit/X frames. Generated output does not fabricate engagement, verification state, or precise fake timestamps.
8. Generate TTS and complete any OP-image overlay before video rendering.
9. Build a format-specific presentation composition.
10. Split each narration into duration-weighted visual states targeting roughly one visible content/focus change every 2.5 seconds.
11. Render those states with continuous motion and concatenate them into the narrated clip.
12. Stitch clips with a transition profile specific to the selected content format.
13. After successful production output, append the accepted script/format/substyle/topic fingerprint to history.

Requested segment/final MP4s are deleted before regeneration so stale files cannot make a failed run appear successful.

## Content formats

`--format auto` is recommended. Explicit values are:

- `thread_story` — threaded story progression.
- `confession` — first-person reveal and quote-focused presentation.
- `debate` — alternating positions and left/right visual grammar.
- `best_answers` — independent ranked responses.
- `escalating_conversation` — short responsive turns and message-style presentation.

The formats differ in generation guidance, layout, motion behavior, timed-state labeling/focus, and final transitions. They are not cosmetic skins.

Each format has four structural substyles selected with `--format-variant`. The recommended `auto` mode balances them from generation history so repeated top-level formats still vary their narrative progression and pacing family.

Format-specific final transitions are intentionally deterministic rather than random:

- Thread story: restrained fade.
- Confession: slower fade-to-black.
- Debate: alternating directional slides.
- Best answers: upward wipe.
- Escalating conversation: quick upward slide.

## Hidden-prompt isolation

Internal format and novelty instructions never travel through the visible `--post-title` or `--topic` fields. `P0Entrypoint` generates the script first, removes `--auto`, and passes the accepted script to the renderer as explicit input.

Manual input is protected from stale generated-script files. For X, omitting `--post-title` also produces a truly empty hidden style instead of inheriting the Reddit-oriented default title.

## Novelty guard

Default history file: `data/generation_history.jsonl` (git-ignored).

Deterministic checks run first because they are cheap and reliable for direct/near duplicates. Semantic comparison then uses Ollama `/api/embed` against recent accepted scripts.

Defaults:

```properties
format=auto
historyFile=data/generation_history.jsonl
historyLimit=500
noveltyThreshold=48
noveltyRetries=4
noveltyEnabled=true
semanticNoveltyEnabled=true
embeddingModel=nomic-embed-text
semanticThreshold=0.86
semanticHistoryLimit=50
integritySanitize=true
```

Useful options:

- `--history-file PATH`
- `--history-limit N`
- `--novelty-threshold N`
- `--novelty-retries N`
- `--embedding-model MODEL`
- `--semantic-threshold 0.86`
- `--semantic-history-limit N`
- `--no-novelty` for deliberate debug/one-off runs
- `--no-semantic-novelty` to disable only the embedding layer while keeping deterministic novelty and strict history validation

Windows setup pulls both `llama3.1:8b` and `nomic-embed-text`, so a normal setup contains the semantic dependency.

## Integrity

Fabricated social signals are removed at the renderer source, not painted over afterward:

- Reddit generated frames do not create upvote/view counts.
- X generated frames do not create reply/repost/like/view counts.
- X generated frames do not draw verified badges.
- X generated frames do not use a fixed precise posting time.
- Neutral fictional-thread labeling remains where appropriate.

`IntegritySanitizer` is now only a final legacy safety net. It validates the frame and removes legacy X verification-blue pixels if an older/custom renderer introduces them.

## Timed dynamic video

The P0 video path requires `ffmpeg` and `ffprobe`.

Each spoken segment is converted into multiple timed visual states. State count scales with audio duration and narration is divided into near-equal spoken chunks, targeting a state change approximately every 2.5 seconds for normal content. Each state changes visible focus text/progress while retaining the selected format's presentation grammar, and motion continues inside each state.

Delivery remains conventional:

- H.264 / libx264
- AAC audio
- `yuv420p`
- `+faststart`

No codec/metadata randomization or classifier-evasion noise is used.

## Entry points and compatibility

Production-safe entry points:

- `redditTxtToImg.P0Entrypoint`
- `redditTxtToImg.CheckedRunner`
- `redditTxtToImg.OpImageVideoSafeRunner`
- `redditTxtToImg.Runner`
- Gradle application / runnable JAR
- GUI generation button
- `run_ai_windows.bat`
- `batch_create_videos_windows.bat`

Explicit raw/debug path:

- `redditTxtToImg.RawCheckedRunner`
- direct platform renderer classes

The raw path still omits fabricated social metrics because integrity is now enforced at the renderer source, but it intentionally bypasses P0 format/novelty/timed-video orchestration.

## Validation

`P0SmokeTest` covers deterministic novelty, strict history parsing, format selection/rotation, long-narration visual cadence, hidden-prompt isolation, option/value isolation, format-specific image composition, legacy verification cleanup, and stale-video cleanup.

`P0VideoSmokeTest` performs real FFmpeg/ffprobe validation for **all five formats**. For each format it creates timed states, renders two H.264/AAC clips, stitches them with that format's transition profile, verifies duration, and verifies both audio and video streams.

GitHub Actions includes:

- Java 21 compile of all first-party Java sources.
- P0 unit smoke suite.
- Real FFmpeg/ffprobe P0 video suite for all five formats.
- Production Reddit and X image smoke tests.
- Explicit raw compatibility smoke test.
- OP-image overlay integration smoke test.
- Failure-path tests for stale/missing input and video without TTS.
- A Windows job that compiles the Java sources and parses the setup, interactive runner, and batch-runner PowerShell files with the Windows PowerShell parser.
