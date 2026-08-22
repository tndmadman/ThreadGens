# P2 pre-publish repetition audit

`P2Entrypoint` is the final production entrypoint. It invokes the complete P0 pipeline first, then audits the finished video before the run is considered publish-ready.

## Production order

1. P0 generates/renders the video and records generation novelty history.
2. P2 locates the exact script, rendered screenshots, narration audio, and final MP4/segment MP4s.
3. `PublishFingerprint` builds a canonical fingerprint of what the viewer receives.
4. `PublishAuditHistory` strictly loads recently approved publish fingerprints from `data/publish_history.jsonl`.
5. Ollama semantic embeddings compare the completed script against approved publish history when semantic novelty is enabled.
6. `PrePublishAuditor` scores content, visuals, voice/TTS, format, pacing, and optional metadata/provenance similarity.
7. Exact artifact/script duplicates and hard near-duplicate combinations block immediately.
8. Aggregate risk determines PASS/WARN/BLOCK.
9. A JSON `publish_audit.json` report is always written for an audited video.
10. In default `block` mode, BLOCK throws before callers (including the batch runner) can promote/copy the video as ready.
11. PASS/WARN videos are appended to approved publish history. `warn` mode may explicitly allow a BLOCK as `WARN_OVERRIDE` and records that override.

P0 generation history and P2 publish history are intentionally separate. A P2-rejected generation remains known to P0, while only approved/overridden finished videos enter P2 history.

## Fingerprint components

The P2 fingerprint includes:

- normalized script SHA-256 and full script for similarity comparisons;
- final video/clip byte hash;
- 64-bit perceptual dHash values from the rendered social frames;
- actual selected content format (resolved from P0 history when `--format auto` was used);
- TTS engine and selected voice;
- real per-segment audio durations from ffprobe;
- total duration;
- optional metadata signature.

The optional metadata signature is deliberately P1-compatible without a compile-time dependency. P2 captures relevant caption/identity/profile/provenance/voice-style CLI options when present and automatically hashes any of these sidecars when they exist:

- `production_manifest.json` in the image/output or video directory;
- `p1_manifest.json` in the image/output or video directory;
- any file explicitly supplied with `--publish-metadata PATH`.

This lets the P1 branch later expose richer caption/identity/provenance state without requiring P2 to duplicate those systems.

## Risk model

Per closest approved output, the aggregate score currently weights:

- content: 34%
- visual perceptual similarity: 18%
- voice/TTS: 10%
- content format: 10%
- normalized segment pacing: 18%
- optional P1 metadata signature: 10%

Ollama semantic premise similarity can raise the content component above lexical similarity. Recent same-format and same-voice streaks add up to 12 risk points to catch feed-level repetition that is not severe enough to make any one pair a duplicate.

Default thresholds:

```properties
publishAuditEnabled=true
publishAuditMode=block
publishHistoryFile=data/publish_history.jsonl
publishHistoryLimit=100
publishAuditWarnThreshold=58
publishAuditBlockThreshold=78
```

Hard blocks include:

- exact finished artifact duplicate;
- exact script duplicate;
- content/semantic similarity at or above the hard duplicate limit;
- highly similar content + visual presentation + pacing.

Using the same voice or same format by itself is not a hard block.

## CLI

P2-only controls are consumed before P0/raw renderer parsing:

- `--publish-audit`
- `--no-publish-audit`
- `--publish-history PATH`
- `--publish-history-limit N`
- `--publish-audit-warn N`
- `--publish-audit-threshold N`
- `--publish-audit-mode block|warn`
- `--audit-report PATH`
- `--publish-metadata PATH`

The existing P0 `--no-semantic-novelty` option also disables the P2 semantic comparison for deliberate debug/offline runs.

## Batch behavior

`tools/batch_create_videos.ps1` already executes jobs sequentially through `OpImageVideoSafeRunner`, which now routes to P2. Each successful job appends to the shared publish history before the next job begins. That means later videos in the same batch are compared against earlier approved videos in the batch automatically.

The batch script copies a finished MP4 into `final_videos` only after the Java command exits successfully. Therefore a default-mode P2 BLOCK prevents promotion into the ready folder while leaving the per-job render and `publish_audit.json` available for diagnosis.

## Fail-closed behavior

An existing malformed `publish_history.jsonl` is an error. P2 never treats corrupt history as a clean slate.

When semantic comparison is enabled and approved history exists, an Ollama embedding failure also fails the publish audit instead of silently skipping semantic comparison.

## Validation

`P2SmokeTest` covers:

- empty history PASS;
- exact artifact BLOCK;
- exact script BLOCK;
- same voice alone does not hard-block distinct content;
- publish-history round trip;
- malformed history fails closed;
- report creation.

`P2ArtifactSmokeTest` uses real FFmpeg/ffprobe media and validates:

- actual final artifact hashing;
- rendered-image perceptual hashing;
- real audio duration capture;
- publish-history integration;
- exact finished-artifact duplicate blocking.

GitHub Actions compiles the full Java source set on Ubuntu and Windows, runs P0 suites, both P2 suites, existing renderer/integration/failure-path tests, and Windows PowerShell parsing.
