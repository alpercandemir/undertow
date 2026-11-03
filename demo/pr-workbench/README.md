# Interactive demo and engineering workspace

An engineering workspace for standing contracts, proposed changes, legacy observations, and baseline/candidate evidence. The main example is [refund extraction](../refund-extraction/README.md): partial refunds must preserve the aggregate refund limit and operation-key semantics.

```sh
python3 -m http.server 8773 --bind 127.0.0.1 --directory demo/pr-workbench/dist
```

Open the [interactive demo](http://127.0.0.1:8773/) or the [full workspace](http://127.0.0.1:8773/workspace.html). No frontend installation, model key, or backend execution service is needed. `dist/` is the authored portable site source.

The demo entry focuses on one standing refund invariant. Select legacy/broken/corrected implementations, edit the JSON trace, try cumulative overflow, idempotent retry, or the exact remaining balance, and inspect the resulting operations. Implementation and preset selection evaluate immediately; manual JSON edits clear the result until **Evaluate trace**. **Share scenario** creates a versioned URL fragment containing the selected implementation and bounded trace, without storing it on a server. Invalid or oversized shared data is rejected. A URL hash is not a trusted evidence receipt.

`index.html`, `demo.js`, `demo.css`, and `demo-state.mjs` implement the demo. `workspace.html`, `app.js`, and `styles.css` preserve the full workspace. Both use the same `refund.mjs` browser model and recorded evidence, with no duplicate evaluator.

The file pane provides editable version 1 JSON contract drafts, free-text change intention, proposed acceptance checks, and replay cases. Source/verifier files and a temporary generated probe are inspectable. JSON uses the same model as the CLI's YAML input; the browser offers structural preflight, not full CLI validation.

Compare legacy, broken extraction, and corrected extraction. The table reads real recorded JUnit executions grouped as standing invariants, proposed behavior, and a temporary candidate probe. It does not compile edited Java or authenticate a fresh CI receipt. Draft edits cannot rewrite these results or approve policy. Editable traces run a separate bounded browser model; changed/invalid input cannot inherit an old replay result.

Legacy discovery inventories test descriptions, source lines, and hashes. **Open as draft** copies a candidate into an unapproved version 1 manifest. This narrow feature does not infer arbitrary Java semantics or freeze bugs as requirements. The [product design](../../docs/behavior-preservation-and-monoliths.md) defines the wider monolith discovery and evaluation pipeline.

The earlier public ticket PRs and trusted-imported GitHub results remain a secondary evidence reference. Optional public PR lookup fetches metadata only. Input is not persisted or sent to a model; metadata goes directly to `api.github.com` without browser credentials.

Validation:

```sh
node --test scripts/tests/pr-workbench.test.mjs
# JDK 21; prepare the reviewed fixture's dependencies first:
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test
python3 scripts/refund-showcase.py --output .undertow/refund-validation
```

No production extraction, general rule-mining engine, anonymous code runner, public deployment, or production acceptance is claimed. The [next-step plan](../../docs/next-pilot-and-public-playground.md) retains the public-page/adoption sequence.
