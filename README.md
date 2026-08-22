# ThreadGens

ThreadGens is a local Java pipeline for turning Reddit-style threads or X-style posts/replies into 9:16 short-form video assets.

The production path now has two quality layers:

- **P0 generation/originality** — content-aware formats, deterministic + semantic script novelty, source-clean social rendering, timed visual states, and format-specific video motion/transitions.
- **P2 pre-publish audit** — final finished-video repetition analysis across approved output history before a video is considered ready.

The P2 gate does not add classifier-evasion noise or random metadata. It evaluates actual content and presentation repetition.

## Production flow

```text
Prompt / manual script
        ↓
P0 content + semantic novelty
        ↓
Reddit/X rendering
        ↓
TTS / optional OP image
        ↓
Timed dynamic video
        ↓
Final MP4
        ↓
P2 finished-output fingerprint
        ↓
PASS / WARN / BLOCK
        ↓
Approved publish history
```

P0 generation history and P2 publish history are separate on purpose. A P2-rejected generation remains known to P0, but it is not added to the approved publish history.

## Windows one-click setup

Double-click:

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

The interactive runner asks for platform, prompt content, format, TTS/voice, optional OP-image generation, and video creation. Because it routes through the production entrypoint, generated videos receive the P2 audit automatically.

Default outputs:

```text
output/script/generated_comments.txt
output/*.png
output/images/*.png
output/cache/images/*.txt
output/audio/*.wav
output/video/*.mp4
output/video/final.mp4
output/video/publish_audit.json
```

Local histories:

```text
data/generation_history.jsonl
data/publish_history.jsonl
```

Both history files are git-ignored.

## Build

```bash
javac -d out src/redditTxtToImg/*.java
```

or:

```bash
gradle build
```

## Production entrypoints

`CheckedRunner` remains the compatibility-friendly CLI entrypoint:

```bash
java -cp out redditTxtToImg.CheckedRunner data/comments.txt output --platform reddit
```

Normal `CheckedRunner`, `Runner`, `OpImageVideoSafeRunner`, the GUI, Gradle application, runnable JAR, and Windows launchers route through `P2Entrypoint`. P2 invokes the full P0 pipeline internally before auditing completed video output.

Direct final production entrypoint:

```bash
java -cp out redditTxtToImg.P2Entrypoint data/comments.txt output --platform reddit
```

`P0Entrypoint` remains available when you deliberately want P0 generation/rendering without the P2 final audit:

```bash
java -cp out redditTxtToImg.P0Entrypoint data/comments.txt output --platform reddit
```

Explicit low-level renderer/debug path:

```bash
java -cp out redditTxtToImg.RawCheckedRunner data/comments.txt output --platform reddit
```

Direct renderers also remain available for development:

```bash
java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output
java -cp out redditTxtToImg.XThreadGenerator data/comments.txt output
```

Raw rendering bypasses P0/P2 orchestration. Renderer-source integrity rules still prevent fabricated engagement or X verification.

Supported platforms:

- `reddit`
- `x`
- `twitter` as an alias for `x`

## GUI

```bash
java -cp out redditTxtToImg.GuiApp
```

The GUI calls `CheckedRunner`, so normal GUI video generation inherits P0 + P2 behavior.

## Local AI generation

Make sure Ollama is running. Manual model setup:

```bash
ollama pull llama3.1:8b
ollama pull nomic-embed-text
```

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

For X, `--post-title` is a hidden reply-style instruction and `--topic` is the visible original post. If no X style is supplied, ThreadGens does not inherit the Reddit default title.

## P0 content formats

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

The formats alter generation structure, layout, motion, timed visual focus, pacing, and final transitions rather than acting as cosmetic skins.

## P0 novelty

Accepted generated scripts are recorded in:

```text
data/generation_history.jsonl
```

P0 first runs deterministic checks for exact/near duplicate wording, hooks, and structure. It then uses local Ollama embeddings to catch substantially reworded versions of the same premise.

Useful P0 controls:

- `--history-file PATH`
- `--history-limit N`
- `--novelty-threshold N`
- `--novelty-retries N`
- `--embedding-model MODEL`
- `--semantic-threshold 0.86`
- `--semantic-history-limit N`
- `--no-novelty`
- `--no-semantic-novelty`

A malformed existing generation history fails closed when strict novelty validation is active.

## P2 pre-publish audit

P2 audits the finished video package against previously approved output in:

```text
data/publish_history.jsonl
```

The fingerprint includes:

- exact script hash;
- lexical content similarity;
- Ollama semantic premise similarity;
- final MP4/clip byte fingerprint;
- perceptual hashes of rendered social frames;
- selected content format;
- known voice and TTS engine;
- normalized real narration-segment durations from FFprobe;
- recent same-format / same-voice streaks;
- optional caption/identity/profile/provenance metadata signatures.

P2 outputs one of:

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

P2 CLI controls:

- `--publish-audit`
- `--no-publish-audit`
- `--publish-history PATH`
- `--publish-history-limit N`
- `--publish-audit-warn N`
- `--publish-audit-threshold N`
- `--publish-audit-mode block|warn`
- `--audit-report PATH`
- `--publish-metadata PATH`

`--no-semantic-novelty` also disables the P2 embedding comparison for deliberate offline/debug runs.

### P1 metadata integration hook

P2 has no compile-time dependency on the separate P1 work. When P1 metadata becomes available, P2 can incorporate it through relevant caption/identity/profile/provenance/voice-style CLI values and these optional sidecars:

```text
production_manifest.json
p1_manifest.json
```

Those files are checked in the output/image and video directories. Additional manifests can be supplied with `--publish-metadata PATH`.

This keeps the P2 branch independently buildable while allowing richer P1 state to become part of the final repetition fingerprint after integration.

## Social-render integrity

Generated fictional social frames do not invent platform engagement signals:

- no generated Reddit upvote/view counts;
- no generated X reply/repost/like/view counts;
- no generated X verification badge;
- no fixed precise fake X timestamp.

Relative chronology and neutral fictional-thread labels may still be shown for readable thread layout.

## OP image generation

OP image generation is optional.

Local ComfyUI:

```bash
--image-mode comfyui
```

Existing local image:

```bash
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

Important options:

- `--image-mode none|local|comfyui`
- `--op-image FILE`
- `--image-dir DIR`
- `--image-cache-dir DIR`
- `--comfy-url URL`
- `--image-checkpoint NAME`
- `--image-width N`
- `--image-height N`
- `--image-steps N`
- `--image-cfg N`
- `--image-sampler NAME`
- `--image-scheduler NAME`
- `--image-negative TEXT`
- `--image-timeout SECONDS`

## Local TTS

Supported engines:

- `--tts kokoro`
- `--tts piper`
- `--tts none`

Kokoro example:

```bash
java -cp out redditTxtToImg.CheckedRunner --platform reddit --auto \
  --topic "I found a second phone hidden in my car." \
  --count 10 \
  --tts kokoro \
  --tts-command .venv-kokoro/Scripts/python.exe \
  --voice af_heart
```

TTS options:

- `--voice NAME_OR_PATH`
- `--voice-dir DIR`
- `--list-voices`
- `--tts-command CMD`
- `--audio-dir DIR`
- `--tts-timeout SECONDS`

## Dynamic video

Video generation requires FFmpeg and FFprobe.

```bash
java -cp out redditTxtToImg.CheckedRunner --platform x --auto \
  --topic "Why is there a shopping cart in my living room?" \
  --count 10 \
  --tts kokoro \
  --tts-command .venv-kokoro/Scripts/python.exe \
  --voice af_heart \
  --video --concat-video
```

Each narration segment is split into timed visual states based on actual audio duration, targeting a visible focus/content change roughly every 2.5 seconds for normal narration. Motion continues inside each state, and clips are stitched with format-specific transitions.

Delivery remains conventional H.264/AAC/yuv420p with `+faststart`.

Video options:

- `--video`
- `--concat-video`
- `--video-dir DIR`
- `--video-command CMD`
- `--fps N`
- `--video-timeout SECONDS`
- `--final-video NAME.mp4`

## Batch video creation

Edit:

```text
data/batch_videos.txt
```

Use two non-empty lines per video.

Reddit:

```text
line 1 = post title
line 2 = post body
```

X:

```text
line 1 = hidden reply style
line 2 = visible X post text
```

Run:

```text
batch_create_videos_windows.bat
```

The batch runner executes jobs sequentially through the P2 production path. A successful video is added to publish history before the next job, so later jobs are compared with earlier successful videos in the same batch.

Final copies are collected under:

```text
output/batch_videos/<platform>_<timestamp>/final_videos/
```

A P2 BLOCK returns failure before the batch script performs that final copy. The per-job render and audit report remain available for diagnosis.

## Profile pictures and usernames

Generate profile assets:

```text
generate_profiles_windows.bat
```

Procedural mode:

```bash
python tools/generate_profiles.py --count 100 --size 256
```

ComfyUI mode:

```bash
python tools/generate_comfy_profiles.py --count 25 --size 512 --comfy-url http://127.0.0.1:8188
```

Generated profiles go to `assets/pfp/`; generated usernames go to `data/author_names.txt`.

## Validation

The repository smoke workflow validates:

- Java 21 compilation on Ubuntu and Windows;
- deterministic P0 novelty and strict history behavior;
- real P0 FFmpeg/ffprobe timed-video generation for all five formats;
- deterministic P2 scoring and strict publish-history behavior;
- semantic P2 hard-block behavior;
- real P2 FFmpeg/ffprobe artifact capture;
- perceptual rendered-image hashing;
- exact artifact/script blocking;
- audit report creation;
- production Reddit/X rendering;
- raw compatibility behavior;
- OP-image integration;
- stale-output and invalid video failure paths;
- Windows PowerShell parser validation.

Detailed architecture:

- `P0_IMPLEMENTATION.md`
- `P2_IMPLEMENTATION.md`

## Important files

- `src/redditTxtToImg/P2Entrypoint.java` — final production orchestration and publish gate.
- `src/redditTxtToImg/PublishFingerprint.java` — finished-output fingerprint capture.
- `src/redditTxtToImg/PublishAuditHistory.java` — strict approved publish history.
- `src/redditTxtToImg/PrePublishAuditor.java` — PASS/WARN/BLOCK risk scoring.
- `src/redditTxtToImg/P0Entrypoint.java` — P0 generation/originality orchestration.
- `src/redditTxtToImg/P0Runner.java` — P0 render/video/history orchestration.
- `src/redditTxtToImg/CheckedRunner.java` — compatibility-friendly production CLI.
- `src/redditTxtToImg/RawCheckedRunner.java` — explicit raw/debug renderer path.
- `src/redditTxtToImg/NoveltyGuard.java` — deterministic generation novelty.
- `src/redditTxtToImg/SemanticNoveltyGuard.java` — local Ollama embedding novelty.
- `src/redditTxtToImg/VideoTimeline.java` — duration-based visual timing.
- `src/redditTxtToImg/TimedVisualStateRenderer.java` — per-segment visual states.
- `src/redditTxtToImg/DynamicVideoGenerator.java` — FFmpeg timed-state rendering/transitions.
- `defaults.txt` — production defaults.
- `tools/run_ai_windows.ps1` — interactive Windows pipeline.
- `tools/batch_create_videos.ps1` — Windows batch pipeline.

## Runnable JAR

```bash
gradle jar
java -jar build/libs/ThreadGens-0.6.0-p2-publish-audit.jar data/comments.txt output
```

Video example:

```bash
java -jar build/libs/ThreadGens-0.6.0-p2-publish-audit.jar --platform x --auto \
  --post-title "Wrong answers only" \
  --topic "Why is there a shopping cart in my living room?" \
  --count 10 \
  --tts kokoro --voice af_heart \
  --video --concat-video
```
