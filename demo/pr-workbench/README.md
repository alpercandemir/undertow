# Undertow contract playground

A buildless local UI for the [next-step plan](../../docs/next-pilot-and-public-playground.md). One loop: **set a draft business rule → choose an action → run → compare expected and observed behavior**.

From the Undertow repository root:

```sh
python3 -m http.server 8773 --bind 127.0.0.1 --directory demo/pr-workbench/dist
```

Open [the local playground](http://127.0.0.1:8773). Use an HTTP server; browser module/JSON loading does not work reliably from a `file:` URL. No installation, model key, GitHub token, or backend is needed. `dist/` is the authored portable site source; there is no duplicate generated frontend tree.

Start with **Ticket preview**: the ticket must stay with Mira. Run **Show the invitation** to see the rule hold. Choose **Use a breaking action**, then run again: the preview accepts the transfer and the owner becomes Leo. Change the draft expectation and rerun to compare against that different rule. **Recipient access** demonstrates Alex trying to accept Leo's ticket. Input edits invalidate the previous result until another run.

The main screen contains only Rule, Action, and Result. **See the real PR example** expands the separate regression, correction, missing-evidence assessment, and source/CI links. **Inspect a public PR link** expands optional metadata lookup. These are references, not additional steps in the sandbox flow.

The archived reports were produced by the actual Java verifier after trusted GitHub artifact import. Source/run/policy identities and normalized JUnit evidence are included under `dist/evidence/`. The page reads those files; it does not independently authenticate them. They are not signatures or reusable trusted receipts. The no-evidence report is an actual verifier assessment with its CI bundle withheld. Sandbox rules are structured, locally evaluated drafts; a sandbox pass is not an approved contract or newly executed JUnit evidence. Editing the draft never changes an archived PR result.

The two public showcase PRs stay open for demonstration. The deliberately failing PR must not be merged. GitHub artifacts expire; the plan explains how to rerun and import evidence rather than silently treating a stale snapshot as current.

Validation:

```sh
node --test scripts/tests/pr-workbench.test.mjs
# JDK 21 for the fixture:
mvn --batch-mode --no-transfer-progress -f demo/ticket-transfer/pom.xml clean test
```

The static page persists no input, uses no external scripts, fonts, analytics, model calls, or browser credentials, and executes no submitted repository code. Optional metadata goes to `api.github.com` only. The current service is not deployed.
