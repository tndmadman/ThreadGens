# ThreadGens

ThreadGens is a local Java pipeline for turning Reddit-style threads or X-style posts/replies into 9:16 short-form video assets.

The production path now integrates all three quality layers:

- **P0 originality/integrity** — five content-aware formats, deterministic + semantic novelty, source-clean fictional social rendering, timed visual states, motion, and transitions.
- **P1 variation/provenance** — timed captions, voice pools/delivery presets, persistent identity recency, and AI/manual provenance/disclosure metadata.
- **P2 pre-publish audit** — final finished-video repetition analysis across approved output history with PASS/WARN/BLOCK gating.

The P2 gate evaluates actual content and presentation repetition. It does not add classifier-evasion noise, spoof platform metadata, or fabricate human upload behavior.

## Production flow

```text
Prompt / manual script
        ↓
P0 content + semantic novelty
        ↓
Reddit/X rendering + P1 identity rotation
        ↓
P1 TTS voice planning + narration sidecars
        ↓
P1 captions + timed visual scenes
        ↓
Dynamic final MP4 + provenance metadata
        ↓
P2 finished-output fingerprint
        ↓
PASS / WARN / BLOCK
        ↓
Approved publish history
```

P0 generation history, P1 identity history, and P2 approved-publish history are deliberately separate.

## Windows setup

Run:

```text
setup_windows.bat
```

Setup checks/installs Java 21, FFmpeg/FFprobe, Kokoro, Piper, Ollama, and the local models used by the production pipeline:

```text
llama3.1:8b
nomic-embed-text
```

Then run:

```text
run_ai_windows.bat
```

Batch generation:

```text
batch_create_videos_windows.bat
```

## Build

```bash
javac -d out src/redditTxtToImg/*.java
```

or:

```bash
gradle build
```

## Production entrypoints

Recommended compatibility-friendly CLI:

```bash
java -cp out redditTxtToImg.CheckedRunner data/comments.txt output --platform reddit
```

Direct final production entrypoint:

```bash
java -cp out redditTxtToImg.P2Entrypoint data/comments.txt output --platform reddit
```

Normal `CheckedRunner`, the GUI compatibility path, `Runner`, `OpImageVideoSafeRunner`, Gradle application, runnable JAR, and Windows batch flow route through `P2Entrypoint`.

`P2Entrypoint` invokes the complete P0/P1 pipeline first and only audits after finished video/provenance output exists.

P0-only orchestration remains available for deliberate development/debug runs:

```bash
java -cp out redditTxtToImg.P0Entrypoint data/comments.txt output --platform reddit
```

Explicit raw renderer/debug path:

```bash
java -cp out redditTxtToImg.RawCheckedRunner data/comments.txt output --platform reddit
```

Direct renderer classes remain available for development:

```bash
java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output
java -cp out redditTxtToImg.XThreadGenerator data/comments.txt output
```

Raw rendering bypasses P0/P1/P2 orchestration, but renderer-source integrity still prevents fabricated engagement and X verification.

Supported platforms:

- `reddit`
- `x`
- `twitter` as an alias for `x`

## Local AI generation

Reddit example:

```bash
java -cp out redditTxtToImg.CheckedRunner --platform reddit --auto \
  --post-title "Finish this story in the comments" \
  --topic "I found a locked box behind my dryer." \
  --count 10 \
  --llm-model llama3.1:8b
```

X example:

```bash
java -cp out redditTxtToImg.CheckedRunner --platform x --auto \
  --post-title "Wrong answers only" \
  --topic "Why is there a shopping cart in my living room?" \
  --count 10
```

For X, `--post-title` is a hidden reply-style instruction and `--topic` is the visible original post.

## P0 formats and novelty

Recommended:

```text
--format auto
```

Explicit formats:

```text
thread_story
confession
debate
best_answers
escalating_conversation
```

Accepted generated scripts are stored in:

```text
data/generation_history.jsonl
```

P0 performs deterministic duplicate/structure checks followed by local Ollama embedding comparison for substantially reworded versions of the same premise.

Useful controls:

- `--history-file PATH`
- `--history-limit N`
- `--novelty-threshold N`
- `--novelty-retries N`
- `--embedding-model MODEL`
- `--semantic-threshold 0.86`
- `--semantic-history-limit N`
- `--no-novelty`
- `--no-semantic-novelty`

Malformed generation history fails closed when strict novelty validation is active.

## P1 captions and visual timing

P1 builds timing from the exact narration sidecar and measured WAV duration.

Caption modes:

```text
--captions off
--captions word
--captions sentence
```

`word` mode uses ASS karaoke highlighting. `sentence` uses timed sentence cues. `off` removes visible captions while sentence timing can still drive visual scenes.

Useful controls:

- `--caption-words N`
- `--visual-max-scenes N`

Timing is deterministic and audio-duration-aligned, but it is estimated rather than phoneme-level forced alignment.

## P1 voice variation

Voice selection modes:

```text
single
series
per-slide
```

Example:

```bash
java -cp out redditTxtToImg.CheckedRunner data/comments.txt output \
  --platform reddit \
  --tts kokoro \
  --voice-series "af_heart,af_bella,am_adam" \
  --voice-selection series \
  --series-id example-series \
  --tts-delivery calm \
  --captions word \
  --video --concat-video
```

Useful controls:

- `--voice NAME_OR_PATH`
- `--voice-series VOICE1,VOICE2`
- `--voice-selection single|series|per-slide`
- `--series-id ID`
- `--tts-delivery natural|calm|energetic|dramatic`
- `--tts-speed 0.60..1.60`
- `--tts-language CODE`
- `--tts-sentence-pause-ms 0..2000`

Every successful narration segment writes:

```text
<segment>.txt
<segment>.voice.json
```

The P2 audit reads these `.voice.json` files to fingerprint the voice(s) actually rendered, rather than trusting only the launch-time `--voice` argument.

## P1 identity history

Persistent identity history:

```text
data/identity_history.jsonl
```

P1 rotates names/profile images by recency and avoids same-video duplication where the pools permit it. AI/custom profile images are preferred when available.

The integrated implementation uses a same-process mutex, OS file lock, and atomic replacement. Malformed nonblank identity-history rows fail closed instead of being silently ignored.

Controls:

- `--identity-history-file PATH`
- `--identity-history-limit N`
- `--no-identity-history`

## P1 provenance and disclosure

Successful runs can write:

```text
<output>/metadata/<prefix>-provenance.json
<final-video>.provenance.json
```

The manifest records content origin, platform/format/model, novelty state, voice strategy and actual segment voices, caption timing method, image-generation configuration, synthetic-identity/integrity policies, and SHA-256 artifact hashes.

MP4 files receive standard disclosure tags such as `comment`, `description`, `artist`, and `encoded_by`.

This is generation provenance/disclosure metadata; it is not a cryptographically signed C2PA credential.

Controls:

- `--metadata-dir PATH`
- `--disclosure TEXT`
- `--no-provenance-metadata`

## P2 pre-publish audit

Approved finished-output history:

```text
data/publish_history.jsonl
```

P2 fingerprints and compares:

- exact script hash;
- lexical content similarity;
- semantic premise similarity;
- final video/clip byte fingerprint;
- rendered social-frame perceptual hashes;
- sampled frames from the completed MP4;
- avatar/author identity-region perceptual hashes;
- actual P1-rendered voice-set signature and TTS engine;
- selected content format;
- normalized narration-segment pacing;
- stable P1 caption/delivery/provenance configuration.

P2 outputs:

```text
PASS
WARN
BLOCK
```

Default production behavior is `block` mode. A BLOCK writes the audit report but returns failure before a caller can promote the video as publish-ready.

Default P2 settings:

```properties
publishAuditEnabled=true
publishAuditMode=block
publishHistoryFile=data/publish_history.jsonl
publishHistoryLimit=100
publishAuditWarnThreshold=58
publishAuditBlockThreshold=78
```

P2 controls:

- `--publish-audit`
- `--no-publish-audit`
- `--publish-history PATH`
- `--publish-history-limit N`
- `--publish-audit-warn N`
- `--publish-audit-threshold N`
- `--publish-audit-mode block|warn`
- `--audit-report PATH`
- `--publish-metadata PATH`

The history read, semantic comparison, decision, and approved-history append are one serialized transaction using an in-process fair lock plus an OS file lock.

Malformed publish history fails closed.

## Dynamic video

Video generation requires FFmpeg and FFprobe.

```bash
java -cp out redditTxtToImg.CheckedRunner --platform x --auto \
  --topic "Why is there a shopping cart in my living room?" \
  --count 10 \
  --tts kokoro \
  --voice af_heart \
  --video --concat-video
```

The video pipeline uses narration-aligned visual states rather than looping one unchanged screenshot. P0/P1 scene timing, continuous motion, captions, and format-specific transitions are combined into conventional H.264/AAC/yuv420p output with `+faststart`.

## OP image generation

OP image generation is optional.

Local ComfyUI:

```text
--image-mode comfyui
```

Existing local image:

```text
--image-mode local --op-image path/to/image.png
```

Default ComfyUI endpoint:

```text
http://127.0.0.1:8188
```

Default checkpoint:

```text
RealVisXL_V5.0_fp32.safetensors
```

## Batch behavior

Batch jobs execute sequentially through `OpImageVideoSafeRunner`, which routes through P2.

Each successful video enters approved publish history before the next job, so later videos in the same batch are compared against earlier approved videos.

The batch script copies a final MP4 into `final_videos` only after the Java command exits successfully. A P2 BLOCK therefore prevents promotion while retaining the job render and audit report for diagnosis.

## Default runtime files

```text
output/script/generated_comments.txt
output/*.png
output/audio/*.wav
output/audio/*.txt
output/audio/*.voice.json
output/video/*.mp4
output/video/final.mp4
output/video/final.mp4.provenance.json
output/video/publish_audit.json
<output>/metadata/<prefix>-provenance.json

data/generation_history.jsonl
data/identity_history.jsonl
data/publish_history.jsonl
```

History/runtime files are git-ignored.

## Validation

The integrated GitHub Actions workflow validates on Ubuntu:

- complete Java compilation;
- P0 deterministic novelty/history tests;
- real P0 FFmpeg/ffprobe video tests for all five formats;
- P1 caption, voice, identity and provenance unit tests;
- P1 FFmpeg/libass caption/provenance integration;
- profile-image regressions;
- strict identity-history corruption handling;
- stable P1→P2 provenance signatures;
- P2 deterministic scoring/history tests;
- P2 semantic hard-block behavior;
- in-process and cross-process publish-history locking;
- real-media visual/identity/audio fingerprinting;
- actual P1 voice-sidecar ingestion;
- a full production PASS then duplicate-BLOCK scenario;
- Reddit/X production, raw compatibility, OP-image and failure-path regressions.

Windows validates:

- complete Java compilation;
- P1 runtime smoke tests;
- P2 runtime smoke tests;
- integrated P1/P2 boundary tests;
- profile-image regression tests;
- PowerShell launcher parsing.

Detailed architecture:

- `P0_IMPLEMENTATION.md`
- `P1_IMPLEMENTATION.md`
- `P2_IMPLEMENTATION.md`
- `INTEGRATION_STATUS.md`

## Runnable JAR

```bash
gradle jar
java -jar build/libs/ThreadGens-0.7.0-p0-p1-p2-integrated.jar data/comments.txt output
```
