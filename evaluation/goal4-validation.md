# Goal 4 · Real-world validation and competitive benchmark

**November 20, 2026 · NO-GO for Goal 5.** Deterministic Undertow found none of the 18 labeled behavior changes in the historical corpus and added no findings beyond GitHub Copilot CLI. Copilot surfaced 15 of the 18, plus seven distinct source-supported outcomes outside the frozen labels. Undertow's zero findings make precision and example accuracy undefined. Gemini and independent maintainer assessment are pending. The available evidence supports finishing the comparison and review, with the paired execution pilot deferred.

## Dataset and comparison

- The frozen Goal 1 corpus contains 20 merged public Python library PRs, 10 each from Pallets Click and Encode HTTPX. It contains seven intentional changes to public behavior, nine fixes for reported bugs, and four runtime controls covering an annotation change, docs, tests, and a code-moving private-helper refactor. It includes library policy decisions such as query replacement, allowed proxy schemes, and JSON serialization. The 18 outcomes are retrospective source and test labels. They have no independent second adjudication or paired runtime replay. The selection favors cases with clear upstream evidence and has little coverage of Undertow's numeric rules or application-specific policies. [Frozen case metadata, outcomes, and evidence](historical.json) · [Goal 1 methodology](README.md)
- Goal 4 adds a separately scored regression-introducing case, Click #2934. Its prompt EOF change also broke ordinary stdin iteration through `CliRunner`, as reported in #2939 and confirmed by the subsequent fix. The case and regression label were recorded before running either reviewer on it. The original manifest and `supported` flags remained unchanged. All 21 pinned heads were verified against GitHub's merged-PR metadata and their bases against the first Git parent. [Supplemental manifest](goal4-regression.json) · [Regression report](https://github.com/pallets/click/issues/2939) · [Upstream fix](https://github.com/pallets/click/pull/2940)
- Both tools received the same pinned base/head source and tests with their normal context selection. Copilot CLI `1.0.114` used a fixed source-review prompt and on-demand access to the exported trees and full diff. Undertow chose bounded source regions and references. Both ran without original PR discussions, later commits, or repository test execution. This evaluates the prompted CLI workflow. Its account default served `mai-code-2-flash` in 11 corpus runs and `gpt-6-luna` in nine. The supplement also used Luna. Explicitly selecting `mai-code-2-flash` failed before review. One run per case with mixed model routing limits repeatability, and public code may be familiar from model training. [Reproduction protocol](competitive-pilot.md)

## Measured results on the frozen 20 PRs

Codex provisionally assessed the Copilot findings against the exact revisions and upstream evidence with tool identity visible. The judgments count a valid core behavior change separately from the accuracy of its example. Static typing alone was outside the runtime benchmark. [Copilot adjudications](results/goal4-copilot-adjudications.json)

| Metric | Undertow deterministic | GitHub Copilot CLI | Undertow Gemini |
| --- | ---: | ---: | ---: |
| Completed PRs in requested mode | 20/20 | 20/20 | Pending |
| Findings | 0 | 33 | Pending |
| Behavioral precision | Undefined (0/0) | 87.9% (29/33) | Pending |
| Recall on frozen labeled outcomes | 0% (0/18) | 83.3% (15/18) | Pending |
| False-positive findings per PR | 0.00 (0/20) | 0.20 (4/20) | Pending |
| False-positive findings per control PR | 0.00 (0/4) | 0.50 (2/4) | Pending |
| Concrete before/after example accuracy | Undefined (0/0) | 68.8% (22/32 offered) | Pending |
| Distinct source-supported outcomes beyond the other tool | 0 | 22 (15 labeled, 7 additional) | Pending |
| Median execution time per completed PR | 0.240 s | 52.437 s | Pending |
| Provider cost | $0 | Estimated $0.1292 total, $0.0053 median per PR | Pending |
| Findings that changed a test, fix, policy confirmation, or release decision in the pilot | Unmeasured | Unmeasured | Pending |

Undertow's supported-pattern recall is undefined because the frozen release labels mark all 18 outcomes outside its supported patterns. The all-label recall above gives the practical detection rate on the corpus. Both reviewers were quiet on the HTTPX code-moving refactor. Copilot's two control findings concerned the Click annotation-only change. [Undertow run](results/goal4-local.json) · [Copilot run](results/goal4-copilot.json) · [Metric definitions](competitive-pilot.md#blind-review-and-scoring)

Timing is local wall time. Undertow includes startup, Git reads, and report writes. Copilot includes its CLI session and provider/tool calls, with three sessions in flight and source exports outside the timer. The 20 local sessions consumed 5.177 total worker-seconds. To estimate metered local compute, multiply `5.177 / 3600` by the runner's hourly rate in dollars. No runner invoice was used. Copilot cost uses the 20 sessions' recorded usage converted from nano-AI units to credits at an estimated one billion nano-units per credit and $0.01 per credit. This gives a budget equivalent, with account billing, subscription, and concurrency effects outside the estimate. Access checks are excluded. [GitHub usage metrics](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/usage-and-billing) · [GitHub Copilot billing](https://docs.github.com/en/billing/concepts/product-billing/github-copilot-billing)

In the supplemental regression, Undertow reviewed source and returned zero findings in 0.233 seconds. Copilot took 61.403 seconds at an estimated $0.0077 and surfaced the regression plus a separate prompt-default behavior change. Both examples were provisionally supported by source. The regression could have motivated an EOF iteration test that preserves normal process exit. The upstream fix provides historical corroboration. No new maintainer decision is recorded. [Supplemental Undertow run](results/goal4-local-regression.json) · [Supplemental Copilot run](results/goal4-copilot-regression.json) · [Supplemental adjudications](results/goal4-copilot-regression-adjudications.json)

## Strengths and gaps

- **Goals 1–3.** The v0.3.0 baseline and a fresh release rerun both completed all 20 comparisons with no findings. The recorded Goal 2 worker also produced no findings after improving named thresholds, bounded multiline conditions, and caller binding. The current worker still found none of the 18 outcomes and reviewed source in 18 of the 20 PRs, matching the earlier coverage count. It reports structured examples, compatibility guidance, source references and `source_inferred` evidence. The controlled suite remains the evidence for these capabilities and keeps them separate from the historical score. Configuration tables and helper-local threshold assignments remain known fixture gaps. [v0.3.0 baseline](baselines/v0.3.0-local.json) · [Release rerun](results/goal4-release-rerun.json) · [Goal 2 run](baselines/goal2-local.json) · [Controlled checks](../tests/test_review_engine.py)
- **Detection gaps.** The local rules missed public behavior caused by helper dispatch, serializer arguments, state and exception propagation, and import timing. Examples include Click's cleanup callbacks on context exit, HTTPX's rejection of non-finite JSON, and the supplemental stdin regression. Both tools missed Click's intended removal of the suffix from names such as `init_data_command`, the final empty string from HTTPX text streaming, and deferred loading of both HTTPX dependencies at package import. Copilot detected a related initial empty-chunk change and other observable import-PR effects. Those findings did not establish the frozen outcomes. [Click #2604](https://github.com/pallets/click/pull/2604) · [HTTPX #2998](https://github.com/encode/httpx/pull/2998) · [HTTPX #3377](https://github.com/encode/httpx/pull/3377)
- **False positives and witness errors.** Undertow produced no false positives on the corpus, with zero findings to assess. Copilot invented an empty generated name by overlooking the separator check in #2604 and claimed a chained completion difference contradicted by the unchanged test in #2935. Its other two false positives concern static typing on the annotation control. The live run also shows why witness accuracy merits its own metric. Copilot found the standalone timeout change but used relative URLs in its examples, which cannot reach the slow endpoint through the default HTTP transport. [Copilot adjudications](results/goal4-copilot-adjudications.json) · [Click #2935](https://github.com/pallets/click/pull/2935) · [HTTPX #3116](https://github.com/encode/httpx/pull/3116)

## Decision and outstanding validation

| Provisional Undertow gate | Evidence |
| --- | --- |
| At least 80% precision | Undetermined. Deterministic mode emitted no historical findings. |
| Low noise on behavior-preserving refactors | Observed on four runtime controls, including one source-moving refactor. |
| Incremental actionable findings | Zero unique deterministic outcomes on 20 paired cases and the supplement. Gemini and decision impact are unmeasured. |
| Positive evidence from two independent maintainers | Pending. No independent assessments or follow-through have been submitted. |

Fast deterministic reviews and explicit evidence levels are useful foundations. This sample shows no incremental deterministic detection value, and its coverage gaps precede the question of runtime verification. Further detection or execution engineering should wait until a live Gemini comparison and independent review identify a workflow where accurate incremental findings change decisions. The benchmark provides no basis to generalize to application policies or all reviewer configurations.

Gemini requires `GEMINI_API_KEY`; the run failed its credential preflight. The [pilot instructions](competitive-pilot.md) preserve pinned case inputs and model configuration for that follow-up. The [maintainer assessment form](maintainer-assessment.md) and anonymous packets are prepared. Keep the tool key and provisional results with the coordinator until assessments arrive. There has been no maintainer outreach or external validation of this pilot.
