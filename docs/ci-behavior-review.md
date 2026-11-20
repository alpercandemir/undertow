# Behavior review in GitHub Actions

The engineer's interface is the workflow summary. CI performs the execution; an implementation agent handles accepted changes. No command-line exercise is required for acceptance.

## What runs

[Behavior review](../.github/workflows/behavior-review.yml) runs on pushes, PRs and manual dispatch. Each candidate job checks the actual source at the event commit (the exact head SHA for PRs), runs its existing Maven suite in a fresh copy, searches the selected expectation, replays a finding on its exact snapshot, and executes the generated JUnit test against the candidate. Source hashes, tool hashes, budgets and actual outcomes are retained.

The **refund-retry-calibration** job constructs a labeled authored defect in a temporary copy. It requires the existing tests to pass, search to find a violation, replay to match, and the generated test to fail on that expectation. It then searches the correction, adopts the same test in a fresh correction copy, and requires the full suite and that exact test to pass. A green calibration means these checks completed; it does not excuse a red candidate job.

The workflow uses read-only permissions, pinned actions, ephemeral hosted runners, no model/provider secrets and no automatic comment or policy publication. It never uses `pull_request_target` to execute candidate code. Like ordinary Maven CI, it executes the selected repository's build and source. This is limited to owned, reviewed standalone components matching the adapters, not an arbitrary repository service. Review workflow and adapter edits as source changes; candidate-controlled outputs do not become trusted contract receipts.

## Eight-case gallery

The separate **Behavior case gallery** workflow runs [eight authored cases](cases/README.md): two explicit happy paths, five distinct rule violations and one compilation failure. Its summary shows all outcomes and traces together. A green gallery job validates the expected mix of results; it does not turn the five violations into business passes. Candidate verdicts remain in the separate **Behavior review** workflow.

## Read and decide

Open the PR check's **Details**, then the workflow summary. Read the baseline comparison, reduced operations, expected/observed state, replay and generated-test results. Expand the proposed Java test inline. Download the linked evidence artifact only if deeper inspection is needed; it retains logs, reports, snapshots, replay JSON and `regression.patch` for 14 days.

| Candidate result | Meaning | Engineer decision |
|---|---|---|
| Violation | Replay and generated assertion confirm a behavior failure | Accept the rule and request a fix/test, revise the rule, or reject with reasons |
| No violation within budget | Existing suite passed and the bounded search completed without a finding | Continue normal review; this is not proof of correctness |
| Existing test failure | Existing suite failed; bounded search found no additional violation | Review the existing failure; do not claim added coverage |
| Incomplete | Setup, build, execution, replay or generated-test validation did not finish | Request an automation/integration fix; retain any partial finding separately |

An accepted draft is applied by the implementation agent in the normal change PR. The workflow itself makes no repository writes, merge decisions or standing policy approvals. A previously retained generated-test path is never overwritten by a proposed new patch; reconcile it in source review. The corrected PR runs CI again with the retained test in the ordinary suite. This closes adoption through actual reviewed source and subsequent CI execution, not a manual copy exercise.

The decision can live in the normal PR review. The optional [record](templates/independent-review-record.md) is for collecting pilot evidence, not extra required paperwork. A review should say whether the expectation is sound, whether to retain the regression, and what concrete benefit it added. Do not equate machine elapsed time with human time saved.

## Existing showcase repository

The owned example is [undertow-showcase](https://github.com/alpercandemir/undertow-showcase). Its root ticket component already matches the ticket adapter. The integration package adds a standalone `refund-service` fixture, a vendored behavior runner with a file-hash manifest, and [this workflow](examples/showcase-behavior-review.yml). Candidate ticket/refund jobs and authored calibration jobs are separate.

Vendoring makes a checkout self-contained while the new runner is still unreleased. `tools/undertow/SOURCE.json` identifies every packaged input and the original checkout state; a dirty base commit alone is not represented as a published release. Update the package through normal source review. The integration agent uses `scripts/package-behavior-review.py`; reviewers do not need to invoke it. Existing contracts and their workflow remain intact.

## Recorded integration check before product cleanup

On November 17, 2026, the showcase integration at `714503063fcc128e84545aaeb55a6934883cddb7` passed all four jobs on both [push](https://github.com/alpercandemir/undertow-showcase/actions/runs/44040378667) and [pull request](https://github.com/alpercandemir/undertow-showcase/actions/runs/44040527390). The existing ticket contract workflow also passed. [PR #3](https://github.com/alpercandemir/undertow-showcase/pull/3) merged the profile-based integration into the showcase's default branch.

The downloaded refund-calibration artifact identifies a clean checkout at that commit: six existing tests passed, replay reproduced the retry failure, the generated JUnit assertion observed refunded total 2 instead of 1, the correction search completed without a finding, and all seven correction tests including the identical generated test passed. The Actions summary contains the proposed Java and links to evidence; it asks for decisions rather than shell commands. This is authored integration evidence, not independent owner approval or measured investigation savings.

Local validation also passed 70 Python helper tests, actual candidate failure exit handling, both calibration paths, vendored candidate execution, actionlint, frozen-core hashes, public-content checks and Gitleaks scans. Java harness sources were not changed by this CI revision.

## Maintainer integration reference

The execution entry point is `scripts/behavior-review.py`. Select an owned standalone component with `--subject` and a supported `--behavior`; use `--test-prefix` for its repository-relative directory. The runner copies `pom.xml` and `src/`, so parent reactors, external modules and custom build inputs need explicit integration work. Python 3.10+, Maven 3.9+ and JDK 21+ are required on the runner. The baseline prepares the dependency cache; subsequent search/replay builds run offline. No model key is required.

Exit codes: **0** for a completed clear candidate or a successful labeled calibration, **1** for a validated candidate violation or existing assertion failure, **2** for incomplete execution. The workflow never hides exit 1 with `continue-on-error`. Reports and artifacts are published with `always()`; if setup prevents the report, the summary explicitly says no conclusion is available. Reruns use new ephemeral outputs and artifact names include the attempt number.

The [CLI guide](explore.md) remains available for debugging and adapter development. The earlier unfamiliar-engineer setup exercise is retired. Rule review, independent cases and observed usefulness remain empirical requirements in the [current acceptance criteria](independent-review.md).

GitHub documents the [job-summary interface](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#adding-a-job-summary) and [workflow permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions) used here.
