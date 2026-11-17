# Legacy platform snapshot — November 17, 2026

This archive preserves the complete publishable working-source tree immediately before product consolidation, including uncommitted work. A Git base commit alone would not identify that state. It contains no Git database, credentials, build outputs or ignored local evidence.

| File | Purpose |
|---|---|
| `source.tar.gz` | Original source, configuration, tests, scripts, documentation and assets; file modes preserved |
| `manifest.json` | SHA-256 of the archive and every source file, original modes and base commit |
| `retired-paths.json` | Exact paths removed from the active source tree |

The snapshot contains 394 files; 291 paths were retired. Shared files such as the CLI wiring, Maven configuration and test helpers were simplified in place; their previous versions are also recoverable from the full snapshot.

## Retired capabilities

- Hosted HTTP service, web dashboard, worker, database, GitHub App gateway and service configuration.
- Generic model-driven PR review, Gemini integration, review tools, publication adapters, model evaluations and the old composite action/workflow.
- Release policy evaluation, artifact attestation, rollout monitoring, halt/recovery and pilot measurement commands.
- Browser playground, older order/customer/dependency demonstrations, recording helpers and superseded product plans.

Active behavior search, contract verification, trusted CI import, local preview/freshness, approved regression evidence, SARIF and read-only MCP were preserved. Shared trust-boundary tests were moved into focused active suites.

## Verify or recover

From the active repository, `python3 scripts/check-product-scope.py` verifies every archived file and checks that retired paths remain absent. CI additionally checks that the packaged JAR contains no retired implementation or dependency.

To inspect the historical source, extract it into a **new, separate directory**:

```sh
mkdir /tmp/undertow-legacy-2026-11-17
tar -xzf archive/legacy-platform-2026-11-17/source.tar.gz \
  -C /tmp/undertow-legacy-2026-11-17
```

The extracted configuration and documentation describe the old product. They are historical references, not current setup instructions. Do not extract over the active checkout. Reintroducing an archived capability requires a new source change and review.
