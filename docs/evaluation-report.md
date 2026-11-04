# Phase 2 evaluation — October 5, 2026

The Phase 2 runner freezes fixture/label/policy inputs, retains failed-run denominators, records model/prompt/policy identities and variability, and reports unknown cost when prices are omitted. All 120 authored regression replays passed; these scores remain harness validation. The authorized local live run returned HTTP 401 and a partial authentication-failure report. A successful live evaluation and independently labeled real-PR quality gates remain unmet. See [current status](phase2-status.md) and [setup/evaluation commands](phase2-setup.md).

# Evaluation report — September 30, 2026

Forty authored fixtures were replayed through the actual packaged harness in collector, diff-only and tool-using modes (120 runs). The 34 Java case pairs compiled: 68 source files, zero compilation errors, with JDK 21 and Spring TX. The remaining cases cover database schema changes and dependency inventory inputs.

The replay runner successfully reproduced the curated expected finding/quiet behavior and produced schema/evidence-validated artifacts. These are **harness correctness checks**. Replay findings were written from the labels; reporting their precision/recall as model accuracy would be circular. The collector produces evidence without semantic findings and is not a complete static-analyzer baseline.

Held-out live metrics—High/Critical precision, supported high-impact recall, false-positive rate, severity disagreements, latency/cost variability and regression-test usefulness—are pending. The live Gemini smoke attempt returned HTTP 401 and emitted a partial report with no findings. That empty list is not a clean review. No successful inference usage was reported, and no paid fallback occurred.

The dataset has 10 tuning and 30 held-out cases. It includes unsafe/corrected retry keys, money comparisons/rounding/division, proxy self-invocation, database uniqueness, secrets in logs, timeout removal, benign modern Java/refactors and dependency version changes that must not create invented migration claims. Some collaborator behavior is supplied as a designed contract; this dataset does not establish production generalization.

`python3 scripts/evaluate.py --provider live --split heldout --repeats 3 --output .undertow/eval-live` can run the same modes once account authentication/model access is usable. Labels are scored afterward and never passed to the live reviewer. Per-run reports preserve pinned commits, model/prompt identity, policy hashes, coverage, failures and usage. Pricing inputs are optional explicit per-million rates. Omitted prices now produce an unknown (`null`) cost; explicitly supplied zero rates are recorded as supplied, not inferred as free inference.

A stable release still needs the planned >=80% high-risk precision and >=70% supported-case recall assessed on live held-out outputs, a complete deterministic/static baseline, repeated-run measurements and human assessment of regression usefulness. Until then the product remains advisory and prerelease quality.
