# P2 pre-publish repetition audit

`P2Entrypoint` is the final production entrypoint. It invokes the complete P0 pipeline first, then audits the finished video before the run is considered publish-ready.

## Production order

1. P0 generates/renders the video and records generation novelty history.
2. P2 locates the exact script, rendered screenshots, narration audio, and final MP4/segment MP4s.
3. `PublishFingerprint` builds a canonical fingerprint of what the viewer actually receives, including rendered identity regions and sampled frames from the finished MP4.
4. `PublishAuditHistory` strictly loads recently approved publish fingerprints from `data/publish_history.jsonl`.
5. Ollama semantic embeddings compare the completed script against approved publish history when semantic novelty is enabled.
6. `PrePublishAuditor` scores content, visuals, rendered identities, voice/TTS, format, pacing, and optional metadata/provenance similarity.
7. Exact artifact/script duplicates and hard near-duplicate combinations block immediately.
8. Aggregate risk determines PASS/WARN/BLOCK.
9. A JSON `publish_audit.json` report is always written for an audited video.
10. In default `block` mode, BLOCK throws before callers (including the batch runner) can promote/copy the video as ready.
11. PASS/WARN videos are appended to approved publish history. `warn` mode may explicitly allow a BLOCK as `WARN_OVERRIDE` and records that override.

P0 generation history and P2 publish history are intentionally separate. A P2-rejected generation remains known to P0, while only approved/overridden finished videos enter P2 history.

## Fingerprint components

The P2 fingerprint includes:

- script SHA-256 and full script for lexical/semantic similarity comparisons;
- final video/clip byte hash that is independent of output filename;
- 64-bit perceptual dHash values from the rendered social frames;
- avatar plus author/header perceptual hashes cropped from the actual rendered Reddit/X frames, so username/profile reuse is measured without OCR and without depending on P1 internals;
- three perceptual samples from each completed MP4, so burned captions, overlays, motion composition, and other final-video presentation differences are included;
- actual selected content format;
- TTS engine and selected known voice;
- real per-segment audio durations from ffprobe;
- real completed-artifact duration from ffprobe;
- optional P1 metadata signature.

For `--format auto`, P2 first resolves the exact P0 history row for the rendered script. If P0 history recording was explicitly disabled, P2 reruns the same deterministic format selector against the unchanged history so the fingerprint records the actual selected format instead of the literal value `auto`.

Unknown/missing voice identifiers do not count as repeated voices and do not create a voice streak penalty.

Publish-history schema 2 adds rendered-identity hashes. Schema-1 history rows remain readable and simply have no identity evidence, while malformed or unsupported history still fails closed.

The optional metadata signature is deliberately P1-compatible without a compile-time dependency. P2 captures relevant caption/identity/profile/provenance/voice-style CLI options when present and automatically hashes any of these sidecars when they exist:

- `production_manifest.json` in the image/output or video directory;
- `p1_manifest.json` in the image/output or video directory;
- any file explicitly supplied with `--publish-metadata PATH`.

The final-MP4 perceptual samples also allow later P1 caption and overlay changes to influence visual similarity even before a stable P1 manifest schema is available.

## Risk model

Per closest approved output, the aggregate score currently weights:

- content: 32%
- visual perceptual similarity: 15%
- voice/TTS: 8%
- content format: 8%
- normalized segment pacing: 15%
- rendered identity similarity: 14%
- optional P1 metadata signature: 8%

Ollama semantic premise similarity can raise the content component above lexical similarity. Recent same-format, same-known-voice, and repeated rendered-identity streaks add up to 12 risk points to catch feed-level repetition that is not severe enough to make any one pair a duplicate.

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

Using the same voice, same format, or same rendered identity by itself is not a hard block. Repeated identity reuse contributes to aggregate/streak risk instead.

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

If P2 cannot probe or sample a completed video artifact, or cannot decode a rendered social frame needed for visual/identity fingerprinting, the audit fails instead of silently omitting evidence.

## Validation

`P2SmokeTest` covers:

- empty history PASS;
- exact artifact BLOCK;
- exact script BLOCK;
- semantic premise hard BLOCK;
- same known voice alone does not hard-block distinct content;
- unknown voice identifiers do not create false voice reuse;
- rendered identity reuse is detected without hard-blocking by itself;
- schema-2 publish-history round trip including identity hashes;
- schema-1 publish-history backward compatibility;
- malformed history fails closed;
- audit-report identity score output.

`P2ArtifactSmokeTest` uses real FFmpeg/ffprobe media and validates:

- actual final artifact hashing;
- source-image perceptual hashing;
- rendered avatar/author identity hashing;
- sampled finished-MP4 perceptual hashing;
- real audio duration capture;
- real final-video duration capture;
- publish-history integration;
- exact finished-artifact duplicate blocking;
- identical rendered identity comparison.

GitHub Actions compiles the full Java source set on Ubuntu and Windows, runs P0 suites, both P2 suites, existing renderer/integration/failure-path tests, and Windows PowerShell parsing.
