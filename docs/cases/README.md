# Eight business-behavior cases

The showcase [gallery run](https://github.com/alpercandemir/undertow-showcase/actions/runs/44042033765) is attached to [draft PR #3](https://github.com/alpercandemir/undertow-showcase/pull/3).

Open **Actions → Behavior case gallery** in the showcase repository and read the run summary. The gallery executes all eight cases on every push and PR, shows the actual operation trace and expected/observed difference, and retains evidence for each case. Engineers need no terminal setup.

| Case | Intended result | Business expectation |
|---|---|---|
| Partial refunds and safe retries | PASS | Refund 30 of 100, retry without a second effect, refund the remaining 70, retry after exhaustion; invalid new and conflicting requests reject without effects |
| Preview, accept and preserve ownership | PASS | Preview preserves state, the recipient accepts, and later previews/duplicate/unauthorized attempts leave ownership stable |
| Retry charges the refund twice | VIOLATION | The same settled operation must not add another refund |
| Split refunds exceed the captured payment | VIOLATION | Total refunds cannot exceed the captured payment |
| An operation key accepts a different amount | VIOLATION | A settled operation key is bound to its original amount |
| A link preview transfers the ticket | VIOLATION | Preview must not change ownership or settle the invitation |
| Another account accepts the invitation | VIOLATION | Only the named recipient may accept |
| The component cannot compile | INCOMPLETE | No business verdict is possible until Java compilation succeeds |

The [catalog](behavior-gallery.json) declares expectations and explicit happy-path walkthroughs. The [runner](../../scripts/behavior-gallery.py) constructs mutations only in disposable copies. The unchanged seeded search receives the profile, source and budgets; it never receives the expected label, target violation or walkthrough. Happy-path walkthroughs run separately against actual compiled Java after a complete bounded search. They record the exact fixture's return behavior; the ticket policy itself permits either harmless acceptance or rejection of a duplicate acceptance attempt.

Each violation requires the correct named rule, exact-snapshot replay and the generated JUnit assertion failure. The same generated test is adopted into a pristine correction, and the full suite must pass. Existing-suite outcomes remain visible: some examples add missing coverage, while others clarify a failure existing tests already detect. A compiler error must contain the intentionally introduced missing-symbol diagnostic; an unrelated network or build problem cannot count as a successful demonstration.

The gallery job is green only when all eight observed outcomes match their labels and required evidence is complete. This is fixture validation, not eight business passes: five rows must still show **VIOLATION**, and one must show **INCOMPLETE**. Real candidate checks in the separate **Behavior review** workflow remain red for actual violations. Expected failures are not hidden using `continue-on-error` in that workflow.

Artifacts include the catalog hash, authored source changes, complete run reports, happy-path observations, reduced replay inputs, proposed regression patches, JUnit XML and correction-suite logs. The workflow writes a partial summary after every case; failures and incomplete runs remain visible. These authored examples do not establish independent detection accuracy or measured time saved.
