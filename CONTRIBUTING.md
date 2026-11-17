# Contributing

Keep source, documentation, comments, filenames and commit messages in English. The active product is CI-driven behavior search, regression proposals and approved contract verification. Its supported examples are the refund and ticket components. Keep engineer-facing flows centered on reports and decisions; CLI instructions belong in maintainer references.

## Development

Use JDK 25 and Maven 3.9+ for the CLI; fixture execution targets Java 21 and also works on the harness JDK. Python 3.10+ runs the search and workflow helpers.

```sh
mvn --batch-mode --no-transfer-progress spotless:apply clean verify
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test
mvn --batch-mode --no-transfer-progress -f demo/ticket-transfer/pom.xml test
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check-product-scope.py --jar target/undertow.jar
python3 scripts/public-check.py
```

Maven checks formatting and runs SpotBugs at the High threshold. Java formatting uses google-java-format AOSP with four-space indentation. Keep fixture source formatting independent from harness-wide formatting. Follow `.editorconfig` and the retained engineering guidance in `CODING-SKILL.md`.

Preserve the frozen search-core hashes in `experiments/behavior-search/strategy-lock.json` unless explicitly creating a new strategy version and validation round. Keep expected labels and authored mutations outside search generation. A failure must replay and produce the relevant generated assertion; skips, execution errors and missing reports are not behavioral discoveries.

Exercise the gallery when changing adapters or expectations. Exercise `scripts/explore-smoke.py` against the built JAR when changing runtime packaging. Tests should cover observable behavior, source identity, input boundaries and real execution rather than mirror implementation details.

## Evidence and review

Keep complete, violating and incomplete outcomes distinct. Never infer contract approval from a generated test or an Actions artifact. Keep host-pinned policy and trusted import receipts outside candidate control. MCP remains read-only; do not add execution, publication or source-write tools.

Document changes with the user-visible behavior, validation and practical scope. Workflow summaries must be usable without terminal commands. The eight-case gallery is authored validation, not independent accuracy evidence or measured human time saved.

## Archive and public source

The retired platform snapshot is immutable recovery data. `scripts/check-product-scope.py` checks its file-level integrity and verifies that retired paths and packaged dependencies remain absent. Do not import archived code into active modules or execute the archive from CI. Restore to a separate directory for inspection; reintroduction requires a deliberate new change.

Use synthetic example data. Never commit credentials, environment files, private customer data, generated execution reports or workstation paths. Before publishing, follow the content and secret-scanning checks in [Security](SECURITY.md), including scanning any new archive payload before compression.
