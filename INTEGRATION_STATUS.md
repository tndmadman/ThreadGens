# ThreadGens P0 + P1 + P2 integration

`main` is the integrated production baseline. P0 originality, P1 content variation/provenance, and the P2 final publish audit are no longer separate release branches.

The final production entrypoint is `redditTxtToImg.P2Entrypoint`. Normal compatibility entrypoints and Windows launchers must ultimately route through that gate for publish-ready video work. P0-only and raw renderer entrypoints remain development/debug paths.

Release acceptance requires the exact candidate head to pass all relevant GitHub Actions validation:

- Ubuntu smoke: P0, P1, P2, integration, real FFmpeg/libass, cross-process locking, and the full PASS→duplicate-BLOCK production gate.
- Windows smoke: Java compile, P1/P2/integration/profile regressions, launcher parsing, and the standard batch launcher self-test.
- Windows batch production validation: direct parsing of the complete parallel scheduler/worker/dashboard stack, Python helper compilation, the parallel scheduler self-test, and the full Windows batch launcher self-test.

Do not promote a candidate when a required validation job is red, or when P1/P2 integration tests show that actual voice sidecars, provenance signatures, strict identity history, publish-history locking, or the P2 production gate are bypassed.

Historical feature branches may retain commits that are technically ahead of `main` because work was replayed or superseded rather than merged with identical ancestry. Branch ahead-count alone is therefore not evidence of missing production functionality; compare net file behavior against `main` before recovering old branch history.
