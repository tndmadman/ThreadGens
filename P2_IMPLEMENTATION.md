# P2 pre-publish repetition audit

`P2Entrypoint` is the final production entrypoint. It invokes the complete P0/P1 pipeline first, then audits the finished video before the run is considered publish-ready.

## Integrated production order

1. P0 generates content with deterministic and semantic novelty protection.
2. P1 renders identities, narration, timed captions/scenes, and provenance metadata.
3. P2 fingerprints the exact script, rendered screenshots, actual P1 voice sidecars, narration timing, provenance settings, and completed MP4 frames.
4. P2 acquires an exclusive publish-history transaction lock.
5. `PublishAuditHistory` strictly loads recently approved publish fingerprints.
6. Semantic embeddings compare the completed script against approved publish history when enabled.
7. `PrePublishAuditor` scores content, finished visuals, rendered identities, actual voices/TTS, format, pacing, and stable P1 metadata.
8. Exact artifact/script duplicates and hard near-duplicate combinations block immediately.
9. Aggregate risk determines PASS/WARN/BLOCK and writes `publish_audit.json`.
10. Default `block` mode prevents callers and batch promotion from treating a blocked video as publish-ready.
11. PASS/WARN output is appended to approved publish history while the transaction lock is still held.

P0 generation history, P1 identity history, and P2 approved-publish history are separate on purpose.

## P1 integration

P2 does not infer P1 state solely from CLI arguments. It consumes viewer-facing/runtime evidence:

- actual selected voice identities from every `<segment>.voice.json` sidecar;
- perceptual hashes of the rendered avatar and author/header areas;
- perceptual samples from the completed MP4, which include burned captions and overlays;
- real narration-segment durations from FFprobe;
- stable fields from `<prefix>-provenance.json` or the final `.provenance.json` sidecar.

The P1 provenance signature deliberately excludes volatile fields such as generation timestamps and artifact hashes. It includes stable disclosure/content/voice/caption/image configuration so two otherwise identical presentations remain comparable even when generated at different times.

If some, but not all, narration segments have P1 voice sidecars, P2 fails closed instead of silently mixing inferred and actual voice state.

## Fingerprint components

The P2 fingerprint includes:

- script SHA-256 and full script for lexical/semantic comparisons;
- filename-independent final video/clip byte hash;
- perceptual dHashes from rendered social frames;
- avatar plus author/header perceptual hashes;
- three perceptual samples from each completed MP4;
- actual selected content format, narrative substyle, and pacing family;
- actual rendered P1 voice-set signature plus TTS engine;
- real per-segment narration durations;
- completed-artifact duration;
- stable P1 caption/delivery/provenance configuration signature.

## History and concurrency

Default approved-output history:

`data/publish_history.jsonl`

History parsing is strict. Malformed or unsupported rows fail closed.

The read, semantic comparison, decision, and accepted-history append are one serialized transaction. A fair in-process lock plus an OS file lock prevents simultaneous jobs from both approving against a stale snapshot.

P1 identity history is also strict on the integrated branch: malformed identity-history rows fail closed rather than being silently ignored and allowing recently used identities to be forgotten.

## Default controls

```properties
publishAuditEnabled=true
publishAuditMode=block
publishHistoryFile=data/publish_history.jsonl
publishHistoryLimit=100
publishAuditWarnThreshold=58
publishAuditBlockThreshold=78
```

CLI controls:

- `--publish-audit`
- `--no-publish-audit`
- `--publish-history PATH`
- `--publish-history-limit N`
- `--publish-audit-warn N`
- `--publish-audit-threshold N`
- `--publish-audit-mode block|warn`
- `--audit-report PATH`
- `--publish-metadata PATH`

## Validation

The integrated smoke workflow validates:

- full Java 21 compilation on Ubuntu and Windows;
- all P0 deterministic and real five-format FFmpeg tests;
- P1 caption, voice, identity, provenance, FFmpeg/libass, and profile tests;
- strict P1 identity-history corruption behavior;
- stable P1-to-P2 provenance signatures;
- P2 deterministic scoring/history and semantic hard blocks;
- P2 in-process and cross-process publish-history locking;
- real-media visual/identity/audio fingerprinting;
- actual P1 voice-sidecar ingestion;
- full production P2 entrypoint PASS then duplicate BLOCK behavior;
- provenance sidecar/manifest creation during the same end-to-end run;
- Reddit/X production, raw compatibility, OP image, stale-output, and invalid-video regressions;
- Windows P1/P2/integration runtime smoke tests and PowerShell parser validation.
