# ThreadGens P0 + P1 + P2 integration

This branch combines the completed P0 originality pipeline, P1 content-variation/provenance work, and P2 final publish audit.

Release acceptance requires the exact integration head to pass both GitHub Actions jobs:

- Ubuntu: P0, P1, P2, integration, real FFmpeg/libass, cross-process locking, and full PASS→BLOCK production-gate tests.
- Windows: full Java compile, P1 runtime smoke, P2 runtime smoke, integration smoke, profile regression, and PowerShell launcher parsing.

The branch must not be merged if either job is red or if P1/P2 integration tests show that actual voice sidecars, provenance signatures, strict identity history, or publish-history locking are bypassed.
