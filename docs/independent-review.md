# Review the automated result and decide

The workflow executes; the engineer reviews evidence and makes decisions. The previous manual setup/search/replay/adoption exercise is retired. Requiring an unfamiliar engineer to operate CLI commands is not an acceptance criterion.

Start from the [GitHub Actions summary](ci-behavior-review.md). It supplies existing-suite results, bounded search outcomes, reduced failures, replay, generated JUnit execution and proposed source. The authored calibration additionally exercises fail-before/pass-after behavior and disposable adoption. The implementation agent applies accepted changes, and the next CI run verifies the retained test.

## Decisions that remain human

1. **Accept or revise the rule.** The component owner checks intended behavior, units, rejection/retry semantics, assumptions and whether observations read real component state. For refund retry, review the [definition](../experiments/behavior-search/retry-scenario.json), [oracle](../experiments/behavior-search/retry_behavior.py) and [adapter](../experiments/behavior-search/java/RefundAdapter.java). A green suite cannot decide business intent.
2. **Accept or reject the finding and test.** Read the failure trace and proposed test in the Actions summary. Request a fix and retain the regression, request changes to the expectation, or explain why the finding is not useful. An implementation agent applies the decision in the PR. Approval is attached to that PR's source and run, not inferred from a generated draft.
3. **Assess value.** Did the existing suite miss this failure? Did the reduced case clarify an existing failure? Was the test actually retained? Record observed review effort if measured; machine runtime is not investigation time saved.

Use a normal PR review. The [optional decision record](templates/independent-review-record.md) can gather evidence without duplicating logs already retained by CI.

## Independent evidence

Bundled authored defects validate the automation, not general detection accuracy. For stronger evidence, an owner selects a real compatible change or an independently prepared defect and correct control after the strategy is fixed. An implementation agent integrates and runs those cases through the same pipeline. Keep witness inputs and labels out of search tuning. Record every candidate, including misses, execution problems and exclusions; tuning creates a new evaluation round.

An unfamiliar reviewer should be able to understand the result and decide from GitHub alone. Record requests for explanation and resulting improvements to the report. No installation, terminal setup, manual replay or manual test copying is required of that reviewer.

## Current acceptance criteria

| Criterion | Evidence |
|---|---|
| Automation works | Recorded CI run: baseline, search, replay, generated assertion; authored correction search and full adopted suite pass separately |
| Rule is sound | Accountable component owner accepts the recorded rule, adapter observations and scope |
| Review is usable | An engineer can interpret the Actions result and make a finding/test decision without the author operating a separate tool for them |
| Test adoption | Reviewed test retained in source, with actual execution in subsequent component CI |
| Value is observed | A specific additional failure or useful reproduction beyond existing tests, plus reviewer assessment |
| Independent mechanism validation | Independently selected cases and controls run under an unchanged strategy; misses/problems retained |

A CI pass supplies execution evidence. It does not automatically close rule review, real adoption, independent validation or value assessment. A successful owned example supports a scoped demonstration, not production readiness or general accuracy claims.
