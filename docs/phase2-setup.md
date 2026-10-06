# Phase 2 setup and operating guide

Requirements: JDK 25, Maven 3.9+, Git, and Python 3. Undertow reviews Java 21 syntax and single-module Maven projects. It does not resolve overloaded methods against a classpath, execute reviewed builds/tests, or support Gradle, additional languages, GitLab, Bitbucket, or custom GitHub hosts. AI findings, including `BLOCKER`, remain advisory.

## Local setup (P2-01, P2-03)

```sh
java -version
mvn verify
python3 scripts/prepare-demo.py --output .undertow/first-demo
java -jar target/undertow.jar doctor --repo .undertow/first-demo --base HEAD~1 --head HEAD
java -jar target/undertow.jar rules validate --repo .undertow/first-demo --base HEAD~1
java -jar target/undertow.jar rules list --repo .undertow/first-demo --base HEAD~1
java -jar target/undertow.jar rules explain JAVA-RETRY-001 --repo .undertow/first-demo --base HEAD~1
java -jar target/undertow.jar review --repo .undertow/first-demo --base HEAD~1 --head HEAD \
  --replay .undertow/first-demo/replay.json --output .undertow/first-report
```

Open `report.md`, `report.json`, `evidence.json`, `trace.jsonl`, and `publication.json`. Replay is authored harness validation and does not measure model accuracy. With dependencies ready, these commands are intended to produce a first report within 30 minutes; actual setup time must be measured by the pilot owner.

Configure the model and credential environment name in **committed trusted policy**. Set that environment variable through your secret manager. `doctor` reports presence only and performs no network request by default. `doctor --network` explicitly tests model access without providing repository source. A successful preflight does not establish review quality. Authentication, model access, quota, rate limits, timeout, invalid output, unavailable service, and budget exhaustion have distinct report failure causes. No model/provider or paid fallback is selected automatically.

For a live demo, omit `--replay`. Source and evidence are sent to Google. Only use data authorized for that account's privacy/retention arrangement. Do not paste keys into chat, source, command arguments, traces, or artifacts. A credential present in the environment may still be invalid. Missing policy files or commits fail before any model call; resolve those errors first.

Exit code `0` retains its v1 meaning: artifacts were produced, including partial/skipped outcomes. Exit `2` means final-output validation failed. Setup errors are nonzero. Read `status` and `details.failure_cause`; an empty partial report is not a clean review. Publication has a separate receipt and exit code.

## Rules and trusted policy (P2-04)

The v1 catalog remains supported with the same IDs, wording, strengths, labels, and section references. On load it becomes contextual rule data with version `1`, repository ownership, Java applicability, enabled state, and `get_diff`/`read_source` evidence requirements. Existing optional/style kinds remain normative hints, never executable algorithms. V1 migration does not silently install new analyzers.

V2 uses `schema_version: 2`, a `rules` array, and optional `exceptions`. Each rule declares `id`, `version`, `owner`, `description`, `wording`, `strength`, `label`, `execution`, `enabled`, `languages`, `include`, `exclude`, `required_tools`, and `examples`. Supply `sections` referring to `CODING-SKILL.md`, or `references` containing repository `path` and exact Markdown `heading`. Example fixture paths are validated at the policy commit. Unknown fields, duplicate IDs, unsupported enums, missing headings, unsafe paths/globs, duplicate YAML keys, unregistered executors, invalid dates, and expired exceptions are rejected during load.

Supported globs are repository-relative literals, `*`, `?`, and `**`; `**/` also matches a root-level path. Braces and character classes are deliberately unsupported. A scoped exception has an `id`, `rule_id`, `include`, `justification`, `owner`, and ISO `expires` date. Dates expire after their UTC calendar date. Expired exceptions are errors, not silent suppressions.

To migrate, retain each existing rule's ID and normative content, add v2 metadata/applicability/evidence/example fields, then validate the candidate commit. [tenant-rules-v2.yaml](examples/tenant-rules-v2.yaml) is a complete company-specific rule example with [unsafe](examples/tenant-unsafe.java) and [corrected](examples/tenant-corrected.java) source. It can be added to an existing v2 catalog without application changes. Its regression scenario is a tenant attempting to read another tenant's guessed order ID.

```sh
java -jar target/undertow.jar rules validate --repo /path/to/repo --base main --candidate feature
```

This checks both trusted base policy and proposed policy independently. Candidate validity grants no review authority. `review` defaults to base policy. When the diff starts at a merge base, pass `--policy-commit TARGET_SHA --target-head TARGET_SHA`; the target policy and analysis baseline are separate. Head-side disabling and exceptions cannot suppress the current review.

Execution types are `contextual` and `deterministic`. Deterministic executors are registered `junit` or `spotbugs` imports. They do not run a shell or implement new analysis from a YAML sentence. Explicit evidence-backed contextual `rule_assessments` are required to claim evaluation. Source reads alone do not count. Coverage records every rule as evaluated, unevaluated, out of scope, or excepted, with the selected version and reason. Missing evidence/budgets remain visible and can make an otherwise empty review partial.

## GitHub.com workflow (P2-02, P2-06)

Use `.github/workflows/agent-review.yml` on the trusted default branch. Ordinary CI has no model secret and stays independent of agent review. Configure the repository secret `GEMINI_API_KEY` and, for automatic internal PR review/publication, the administrator-controlled variable `UNDERTOW_AUTO_REVIEW=true`. Opening, synchronizing, reopening, and ready-for-review events review trusted internal, non-draft PRs whose author has live write/admin/maintain permission. Forks/external contributions and draft AI reviews require a maintainer `workflow_dispatch`; dispatch is also checked for live maintainer permission. PR text, comments, model output, and head-side workflow changes cannot authorize a run.

The privileged `pull_request_target` workflow builds only the trusted default-branch event SHA. It never checks out, builds, or runs PR code. A separate job invalidates the owned summary after commit/state changes, including draft/closed transitions. Git source is acquired as bare objects. The model step has no publication token. Publication is a separate job with same-run, allowlisted artifact provenance checks. Runs serialize per repository/PR; old head/target results are rejected. This follows [GitHub's guidance for pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target). Repository/organization Actions execution policies may additionally require enabling that trigger.

Private Git acquisition and API access are separate credentials/capabilities. `github-snapshots.py` reads `GITHUB_FETCH_TOKEN` (falling back to `GITHUB_TOKEN`) only during Git fetch via temporary askpass, disables credential persistence/global helpers/redirects, and suppresses Git error payloads. It fetches bounded history, computes the actual merge base, and records `analysis_diff_base`, `target_head`, and `policy_commit`. If the merge base exceeds 2000 commits, obtain authorized history explicitly; no target-tip substitution is made. The adapter resolves the current target tip through the branch-ref API on each metadata/publication check; PR `base.sha` can remain cached after target advancement. The publisher checks that tip independently of the merge base.

GitHub Actions tokens use the GitHub Actions bot identity. Another GitHub App must supply trusted `--bot-login APP[bot] --bot-id NUMERIC_ID`, paired with that application's token by the operator. Ownership requires numeric ID, login, bot type, and marker; copied markers are insufficient. Tokens must have repository read access for metadata, contents access for private fetch, and PR write access for comments. The adapter declares summary support; Checks/annotations/status/discussion updates remain unavailable.

The publisher initially writes a visibly pending summary, verifies PR state/commits, and then finalizes it. It checks again after finalization and marks changes stale. A lost POST response triggers an owned-summary lookup, not another POST. An unresolved ambiguous creation records `ambiguous_create`; republishing that receipt queries/reconciles existing state and refuses blind creation. Keep one writer per PR; the workflow's concurrency group is the supported cross-process serialization mechanism. Local publishers must also serialize. Network failure after finalization can prevent best-effort invalidation; that run's receipt is failed and must never count as current success. A later commit event invalidates old summaries independently of model access.

```sh
java -jar target/undertow.jar pr-info --repository OWNER/REPO --pr 1 --output pr.json
python3 scripts/github-snapshots.py --metadata pr.json --output source.git
java -jar target/undertow.jar publish --repository OWNER/REPO --pr 1 \
  --repo source.git --report-dir .undertow/report \
  --details-url https://github.com/OWNER/REPO/actions/runs/RUN_ID
```

Artifacts survive publication failure. Retry publication from the same artifact directory without another model call. `publication.json` reports `published`, `stale`, `failed`, or `not_requested`. The reviewed SHA, run ID, bot comment ID, and canonical finding fingerprints are recorded separately from review status. Fingerprints include provider/repository/change/rule/location context and ignore model IDs.

The first comment screen shows review state, reviewed commit, important finding count, coverage gaps, mode, and artifact link. Findings have pinned code links, trigger/impact/confidence, evidence references, and proposed tests. Removed findings from incomplete runs are explicitly unresolved. Even complete runs only describe their declared scope. Detailed evidence remains in artifacts; comments are bounded.

The reusable action still produces artifacts; **it does not publish**. Its optional `policy-commit`, `target-head`, `ci-bundle`, and `ci-receipt` inputs match the CLI. For a complete integration, use the included workflow: prepare authenticated snapshots, invoke the pinned trusted action with the same commits/policy, upload `report.json`, `report.md`, `evidence.json`, `trace.jsonl`, and `publication.json`, then publish in a serialized job with write permissions. Do not execute a reviewed head-side action definition. Pin a distributed action by a full commit SHA and retain provenance for every transferred artifact.

## CI evidence (P2-05)

`capture-ci.py` retains dependency-tree v1 compatibility. A local v1 bundle verifies content hashes and pinned commits but remains **unverified origin**; its test results cannot establish success.

`import-ci.py` imports already-produced JUnit XML, SpotBugs XML, and one Maven dependency tree from an explicitly allowlisted GitHub workflow/run. It verifies repository, exact head SHA, completed run, supported trusted internal push/dispatch origin, run/artifact association, archive API digest, pagination, ZIP paths/types/sizes, XML safety, and content hashes. Arbitrary PR-event artifacts are rejected. An artifact source proves where data came from, not that tests cover every behavior or that the analyzer is infallible.

```sh
python3 scripts/import-ci.py --repository OWNER/REPO --run-id RUN_ID --workflow-id TRUSTED_WORKFLOW_ID \
  --base ANALYSIS_BASE_SHA --head HEAD_SHA --output ci.json --receipt trusted-ci-receipt.json
java -jar target/undertow.jar review --repo source.git --base ANALYSIS_BASE_SHA --head HEAD_SHA \
  --policy-commit TARGET_SHA --target-head TARGET_SHA --ci-bundle ci.json \
  --ci-receipt trusted-ci-receipt.json --output .undertow/report
```

For the bundled manual workflow, set the administrator variable `UNDERTOW_CI_WORKFLOW_ID` and supply `ci_run_id`; an invalid import remains missing evidence. The CI workflow now retains `single-module-evidence` for explicit import, alongside the original combined `deterministic-evidence` archive. The importer defaults to the single-module artifact; use `--artifact-name` for another authorized artifact. Automatic PR events never choose an arbitrary CI run from PR text. Keep the importer and receipt in trusted runner storage, outside PR-controlled files. Supplying a fabricated receipt is not a verification mechanism. CLI publication accepts only artifacts authorized by its operator; the hosted workflow verifies same-run provenance before publication. Wrong SHA, repository/run/artifact context, modified contents, missing files, oversized inputs, or stale source are rejected. Missing analyzer files stay missing, rather than becoming zero findings. Archives containing multiple modules/trees remain outside the supported import scope.

CI-executed tests, analyzer findings, and model-proposed regression tests are distinct report sections. Overlapping model/SpotBugs locations preserve both sources in the summary. The agent never executes proposed tests. Verified failing tests remain failing; unverified passing input stays unverified. The bundled workflow does not automatically import arbitrary PR CI artifacts: choose a trusted workflow/run explicitly.

## Evaluation and feedback (P2-01, P2-07)

```sh
python3 scripts/evaluate.py --split heldout --freeze-only --output .undertow/frozen-eval
python3 scripts/evaluate.py --provider live --split heldout --repeats 3 \
  --modes collect,diff,tools --output .undertow/live-eval
python3 scripts/feedback.py --report-dir .undertow/report --output pilot-feedback.jsonl \
  --finding-id FINDING_ID --reviewer REVIEWER --assessment correct --action fixed \
  --technical correct --importance important --usefulness actionable --rationale 'Evidence checked against the observed trigger'
python3 scripts/pilot-measure.py --manifest pilot-manifest.json --feedback pilot-feedback.jsonl --output pilot-metrics.json
```

Frozen inputs bind fixture/label/policy hashes. Changed inputs require a new evaluation destination. Labels are scorer data, never model context. `collect` is a model-free collection baseline. Failed process/model runs remain in denominators and retain failure records. Results include run identities, model/prompt/policy identities, status/severity/finding variability, p95 latency, usage, and high-risk numerator/denominator/intervals. Prices are unknown unless explicitly supplied with `--input-rate` and `--output-rate`; zero usage/pricing is not assumed free. Repeated runs are not independent cases; synthetic and real-PR results remain separate.

[The pilot manifest template](examples/pilot-manifest.json) starts empty deliberately. Add authorized historical PR cases with `repository`, `number`, `supported`, `valid_inputs`, `benign`, `reports` (relative report paths for repetitions), and `defects` (`rule_id`, `severity`, two distinct `reviewers`, and `adjudicated`). Two human reviewers must independently assess technical correctness, importance, and proposed-test usefulness. Feedback is append-only JSONL with run/finding identity; corrections append new entries. Disagreements require an explicit human adjudication with reviewer/rationale in `adjudications`, keyed `RUN_ID/FINDING_ID`. Uncertain/missing votes leave the gate unmet. The model cannot grade itself through its tool registry.

Real-PR quality gates require at least 30 historical PRs, two repositories, 10 benign controls, and 20 supported independently labeled high-risk defects, plus the PRD's accuracy, reliability, latency, cost, setup, and publication requirements. A new synthetic test repository cannot supply those historical/independent samples. Neither empty output nor replay scores satisfy live accuracy targets.

## Rollback and retention

Set `UNDERTOW_AUTO_REVIEW=false` to stop automatic AI review/publication; maintainer dispatch remains available. Disable the workflow to stop all jobs. Return to a previous trusted harness commit if reverting the engine. V1 rules/reports remain readable; generated reports now use v2 metadata (see [migration notes](phase2-report-contract.md)). Keep artifacts and receipts when debugging; do not silently rewrite feedback or frozen inputs. CI artifacts default to seven days and should have repository-only access. Change retention to the organization's approved policy, especially for source/evidence.

The client demonstration consists of duplicate-payment risky/fixed runs, a tenant-isolation company rule, and missing model/CI evidence. Manual `replay_case` dispatch options are explicitly authored replay; automatic events use the live client. No failed credential is substituted with a fixture labeled live.
