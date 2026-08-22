# P1 content variation and provenance pipeline

P1 extends the P0 production path instead of adding a parallel runner. `P0Entrypoint` still owns hidden format-aware generation, and `P0Runner` now coordinates P1 timing, scene rendering, video disclosure, and the final provenance manifest. The existing Reddit and X renderers share the same voice and identity components.

## Timed captions and visual changes

`CaptionTimeline` tokenizes the exact narration sidecar written beside every WAV. It measures the completed audio with FFprobe and allocates time using word length plus punctuation pauses. This supports:

- `word`: compact ASS cues with per-word karaoke highlighting.
- `sentence`: one timed cue per sentence.
- `off`: no visible captions while sentence timing still drives scenes.

The timing is deterministic and audio-duration-aligned, but it is not phoneme-level forced alignment. The provenance manifest identifies it as estimated timing.

Visual cues target the completed P0 cadence of roughly 2.5 seconds, merge sub-0.85-second states, and are capped by `visualMaxScenes`. `TimedVisualStateRenderer` uses those same cue boundaries for its active narration cards and progress treatment. `DynamicVideoGenerator` preserves P0's format-specific motion and final transitions, burns the ASS track, and keeps the H.264/AAC/yuv420p/+faststart delivery format.

## Voice pools and delivery

`VoicePlan` is shared by Reddit and X rendering. It supports:

- `single`: the existing `--voice` behavior.
- `series`: one deterministic voice from `--voice-series` for the complete video. `--series-id` keeps related videos on the same narrator; otherwise the visible prompt provides a stable per-video key.
- `per-slide`: deterministic rotation through the voice pool.

Delivery presets coordinate speech speed and sentence silence. Explicit speed, language, and pause settings remain available. Kokoro receives these through `tools/kokoro_tts.py`; Piper receives speed as inverse `length_scale` and sentence silence through its CLI.

Every successful audio segment writes:

- `<segment>.txt`: exact narration used for timing.
- `<segment>.voice.json`: engine, selected voice, delivery, speed, language, and pause.

## Identity history

`IdentityHistory` selects names and profile images together. Selection favors identities absent from recent history, then least-recently-used candidates, while avoiding duplicates within one video whenever the pools are large enough. Validated AI profile images are used for every slide when an AI pool exists.

Default history: `data/identity_history.jsonl`.

The history update uses a same-process mutex, an inter-process file lock, and atomic replacement, so concurrent Reddit/X or batch runs do not corrupt the file. When every candidate is on cooldown, selection degrades to least-recently-used rather than returning a blank identity.

## Provenance and disclosure

`ProvenanceManifest` runs after rendering, integrity cleanup, dynamic video, and novelty recording have completed. It writes a JSON manifest containing:

- AI/manual content origin, platform, format, model, and novelty result.
- TTS engine, voice strategy/pool, delivery configuration, and selected voice per segment.
- image-generation mode/checkpoint, caption mode/timing method, and scene-change status.
- synthetic identity and integrity policies.
- portable generated-artifact paths, byte sizes, and SHA-256 hashes.

Each MP4 also receives standard `comment`, `description`, `artist`, and `encoded_by` tags containing or identifying the disclosure. The final MP4 receives an adjacent `.provenance.json` copy for distribution with the video.

The manifest explicitly records that artifact hashes use SHA-256 and that the metadata is not cryptographically signed. It is generation provenance and disclosure metadata, not a C2PA credential.

Default automatic-generation disclosure:

`AI-assisted fictional content with synthetic narration and identities; engagement is hidden.`

Manual input defaults to `Fictional content with synthetic identities; engagement is hidden.` unless `--disclosure` supplies an explicit value.

## Validation

`P1SmokeTest` covers caption timing, ASS karaoke output, sentence scenes, visual frame variation, voice selection, delivery presets, identity cooldown, provenance hashes, adjacent video metadata, and AI-origin propagation.

`P1VideoSmokeTest` is an FFmpeg/libass integration harness. It creates local test audio and verifies caption burn-in, multiple visual scenes, final duration, and embedded MP4 disclosure metadata.
