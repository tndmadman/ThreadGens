# P2 pre-publish repetition audit

`P2Entrypoint` is the final production entrypoint. It invokes the complete P0 pipeline first, then audits the finished video before the run is considered publish-ready.

## Production order

1. P0 generates/renders the video and records generation novelty history.
2. P2 locates the exact script, rendered screenshots, narration audio, and final MP4/segment MP4s.
3. `PublishFingerprint` builds a canonical fingerprint of what the viewer actually receives, including rendered identity regions and sampled frames from the finished MP4.
4. P2 acquires an exclusive transaction lock for the configured publish-history path.
5. `PublishAuditHistory` strictly loads recently approved publish fingerprints from `data/publish_history.jsonl`.
6. Ollama semantic embeddings compare the completed script against approved publish history when semantic novelty is enabled.
7. `PrePublishAuditor` scores content, visuals, rendered identities, voice/TTS, format, pacing, and optional metadata/provenance similarity.
8. Exact artifact/script duplicates and hard near-duplicate combinations block immediately.
9. Aggregate risk determines PASS/WARN/BLOCK and a JSON `publish_audit.json` report is written.
10. In default `block` mode, BLOCK throws before callers (including the batch runner) can promote/copy the video as ready.
11. PASS/WARN videos are appended to approved publish history while the same exclusive transaction lock is still held. `warn` mode may explicitly allow a BLOCK as `WARN_OVERRIDE` and records that override.

The history read, semantic comparison, decision, and accepted-history append are one serialized transaction. A fair in-process lock plus an OS file lock prevents two simultaneous ThreadGens jobs from both evaluating against the same stale history snapshot and approving duplicate candidates.

P0 generation history and P2 publish history are intentionally separate. A P2-rejected generation remains known to P0, while only approved/overridden finished videos enter P2 history.

## Fingerprint components

The P2 fingerprint includes:

- script SHA-256 and full script for lexical/semantic similarity comparisons;
- final video/clip byte hash that is independent of output filename;
- 64-bit perceptual dHash values from the rendered social frames;
- avatar plus author/header perceptual hashes cropped from the actual rendered Reddit/X frames, so username/profile reuse is measured without OCR and without depending on P1 internals;
- three perceptual samples from each completed MP4, so burned captions, overlays, motion composition, and other final-video presentation differences are included;
- actual selected content format;
- TTS engine and selected voice, including resolution of the renderer's configured default voice when `--voice` was omitted;
- real per-segment audio durations from ffprobe;
- real completed-artifact duration from ffprobe;
- optional P1 metadata signature.

For `--format auto`, P2 first resolves the exact P0 history row for the rendered script. If P0 history recording was explicitly disabled, P2 reruns the same deterministic format selector against the unchanged history so the fingerprint records the actual selected format instead of the literal value `auto`.

Unknown/missing voice identifiers do not count as repeated voices and do not create a voice streak penalty. When the renderer actually has a configured default voice, P2 resolves that default rather than incorrectly treating it as unknown.

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

## Batch and concurrent behavior

`tools/batch_create_videos.ps1` executes jobs sequentially through `OpImageVideoSafeRunner`, which routes to P2. Each successful job appends to the shared publish history before the next job begins. Later videos in the same batch are therefore compared against earlier approved videos automatically.

The batch script copies a finished MP4 into `final_videos` only after the Java command exits successfully. A default-mode P2 BLOCK prevents promotion into the ready folder while leaving the per-job render and `publish_audit.json` available for diagnosis.

Separate ThreadGens processes may also share one publish-history file safely: P2 locks a sibling `.lock` file for the complete decision transaction, while an in-process fair lock covers concurrent calls inside one JVM.

## Fail-closed behavior

An existing malformed `publish_history.jsonl` is an error. P2 never treats corrupt history as a clean slate.

When semantic comparison is enabled and approved history exists, an Ollama embedding failure also fails the publish audit instead of silently skipping semantic comparison.

If P2 cannot probe or sample a completed video artifact, or cannot decode a rendered social frame needed for visual/identity fingerprinting, the audit fails instead of silently omitting evidence.

If the publish-history transaction lock cannot be acquired, the audit fails rather than proceeding without synchronization.

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
- concurrent history transactions inside one JVM serialize and cannot both approve an empty-history candidate;
- audit-report identity score output.

A separate CI probe launches two independent Java processes against one empty publish-history path and verifies the OS file lock allows exactly one row to be recorded.

`P2ArtifactSmokeTest` uses real FFmpeg/ffprobe media and validates:

- actual final artifact hashing;
- source-image perceptual hashing;
- rendered avatar/author identity hashing;
- sampled finished-MP4 perceptual hashing;
- configured default voice resolution;
- real audio duration capture;
- real final-video duration capture;
- publish-history integration;
- exact finished-artifact duplicate blocking;
- identical rendered identity comparison.

GitHub Actions also runs a complete `P2Entrypoint` production-gate scenario using a deterministic fake Piper executable: the first real video run must PASS and create one history record, then the identical second run must BLOCK and must not append another approved-history row.

GitHub Actions compiles the full Java source set on Ubuntu and Windows, runs P0 suites, both P2 suites, the in-process and cross-process lock tests, the full P2 production-gate test, existing renderer/integration/failure-path tests, and Windows PowerShell parsing.
