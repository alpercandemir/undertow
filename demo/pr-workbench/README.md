# Undertow contract playground

A buildless local UI for the [next-step plan](../../docs/next-pilot-and-public-playground.md). Explore an owned public ticket-transfer regression, its passing alternative, exact rule/test bindings, and missing evidence. Edit the business flow and contract proposal without silently changing the historical verification result.

From the Undertow repository root:

```sh
python3 -m http.server 8773 --bind 127.0.0.1 --directory demo/pr-workbench/dist
```

Open [the local playground](http://127.0.0.1:8773). Use an HTTP server; browser module/JSON loading does not work reliably from a `file:` URL. No installation, model key, GitHub token, or backend is needed. `dist/` is the authored portable site source; there is no duplicate generated frontend tree.

Try the regression, correction, and missing-evidence cases. Change preview behavior or acceptance authorization, choose the actor, and inspect the local model. Edit intended behavior or required evidence to see a proposal with its approval/evidence gap. Paste either showcase PR URL to load its pinned report. Other canonical public GitHub PR links trigger an optional direct public-metadata request and receive no contract verdict. The showcase metadata check identifies moved head/target subjects without claiming to refresh CI evidence.

The archived reports were produced by the actual Java verifier after trusted GitHub artifact import. Source/run/policy identities and normalized JUnit evidence are included under `dist/evidence/`. The page reads those files; it does not independently authenticate them. They are not signatures or reusable trusted receipts. The no-evidence report is an actual verifier assessment with its CI bundle withheld. Business-flow experiments are a separate browser simulation, not newly executed JUnit tests. Freeform intent changes require an owner to assess whether the bound test still checks that intent.

The two public showcase PRs stay open for demonstration. The deliberately failing PR must not be merged. GitHub artifacts expire; the plan explains how to rerun and import evidence rather than silently treating a stale snapshot as current.

Validation:

```sh
node --test scripts/tests/pr-workbench.test.mjs
# JDK 21 for the fixture:
mvn --batch-mode --no-transfer-progress -f demo/ticket-transfer/pom.xml clean test
```

The static page persists no input, uses no external scripts, fonts, analytics, model calls, or browser credentials, and executes no submitted repository code. Optional metadata goes to `api.github.com` only. The current service is not deployed.
