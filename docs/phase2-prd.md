# Undertow Phase 2 — Product Requirements Document

Date: October 5, 2026  
Status: Draft ready for implementation planning  
Product objective: Turn evidence-backed change review into a usable, measurable pilot for Java teams' everyday pull request workflow.  
Default platform sequence: GitHub.com in Phase 2; GitLab and Bitbucket in Phase 3, ordered by customer demand.

The user confirmed that the first customer's platform is not yet known. GitHub-first is therefore a planning assumption that can change when customer requirements are confirmed. Maintain this PRD in English.

This document specifies target behavior. Commands, fields, and integrations not explicitly marked as existing are planned features. It supplements the original [project plan](../PROJECT-PLAN.md); see [implementation status](implementation-status.md) for delivered capabilities.

## 1. Product decision

Undertow reviews code changes against engineering rules and business invariants. It explains the triggering condition, technical and business impact, supporting evidence, and a proposed regression test. The primary Phase 2 user interface is the PR report. The CLI remains the entry point for local reviews and CI execution.

AI findings remain advisory throughout Phase 2. Tests, compilation, and configured deterministic checks can remain existing CI gates. A `BLOCKER` label or high model confidence alone does not block merging. Automatic merging and autonomous code fixes are out of scope.

The review core gains an explicit Git-provider adapter boundary. Validate that boundary with the existing GitHub integration and contract tests before shipping additional providers. If the first customer requires GitLab or Bitbucket, bring one provider's basic integration into the Phase 2 pilot under the scope-change rule below.

**A GitHub adapter already exists.** Phase 2 must harden, generalize, and validate it, and add controlled automatic triggering. Building a GitHub publisher from scratch is not a missing prerequisite. GitLab and Bitbucket require new native adapters.

## 2. Starting point

| Area | Existing capability | Phase 2 requirement |
|---|---|---|
| Review | Pinned base/head Git commits, bounded tools, source and evidence validation | Measure reliability and failure behavior with a live model |
| Analysis scope | Java 21 source, single-module Maven; harness runs on JDK 25 | Make support limits visible during setup and in reports |
| Rules | Nine YAML rules; trusted policy loaded from the base commit | Schema, applicability, exceptions, lifecycle, and rule tests |
| Reports | Markdown, JSON, evidence, and trace files | Concise PR summaries, source links, and measurable coverage |
| GitHub | Existing adapter, manually triggered workflow, summary comment updates, stale-commit checks | Live PR verification, private repository access, controlled automatic triggering |
| Other platforms | CLI can review a prepared local Git repository | Native PR/MR metadata, authentication, and publishing adapters do not exist |
| Evaluation | Forty authored replay fixtures; live-model quality remains unmeasured | Blind labeling, live held-out evaluation, and a real-PR pilot |

Earlier validation in this work session passed 55 harness tests, seven Python tests, four demo behavior tests, and 40 showcase replays. PostgreSQL integration tests were not rerun because Docker was not running. These results do not measure live-model accuracy.

### Existing GitHub integration and remaining work

| Component | Implemented today | Phase 2 work |
|---|---|---|
| [GitHubClient](../src/main/java/dev/undertow/github/GitHubClient.java) | Reads PR metadata; creates or updates a single owned bot summary; validates report provenance and detects stale commits | Move behind the common adapter contract; preserve existing behavior; verify public/private access and failure recovery |
| [CLI](../src/main/java/dev/undertow/cli/Undertow.java) | `pr-info` reads metadata; `publish` validates and publishes a report | Preserve compatibility and make provider boundaries explicit |
| [Snapshot helper](../scripts/github-snapshots.py) | Fetches public GitHub source as bare Git objects | Add authenticated private access and separate provider-specific source acquisition |
| [Agent review workflow](../.github/workflows/agent-review.yml) | Maintainer starts `workflow_dispatch`; `publish=true` enables the separate publishing job | Add opt-in PR-event automation for trusted contributions and retain authorized manual execution |
| [Reusable action](../action.yml) | Runs a review and produces artifacts | Document that publication is a separate step; supply a complete integration example |
| Native Checks / annotations | Not implemented | P1 enhancement after the P0 summary workflow |

Publishing is already implemented once explicitly requested through the workflow or CLI. Automatic review and publication in response to PR events are a separate capability to deliver in Phase 2. The current workflow is not triggered by every PR opening or push, and hosted live-model-to-comment verification remains incomplete.

GitHub-specific assumptions currently include the API address and bot identity in `GitHubClient`, the clone address in `github-snapshots.py`, and Actions workflows. Local Git object analysis makes additional providers feasible; it does not establish native support for them.

## 3. Users and workflow

| User | Need | Successful outcome |
|---|---|---|
| Developer | See meaningful change risks before opening a PR | Can reproduce a report locally with the same policy and commits |
| Reviewer / technical lead | Concise, current, evidence-backed PR feedback | Can navigate from a finding to source and evidence and identify incomplete analysis |
| Rule owner | Change team policies under version control | Invalid policies fail validation; policy changes are traceable |
| Pilot owner | Assess value and operating cost | Can inspect valid/invalid findings, latency, and usage measurements |

1. The developer runs formatting, compilation, and tests with existing tools.
2. The developer may review committed branch changes through the CLI. Staged or uncommitted-file support is not required for Phase 2.
3. PR creation, updates, or an authorized rerun request are handled according to the configured trigger policy.
4. A trusted runner pins the inputs, loads the trusted base policy, and produces a review.
5. A separate publishing step creates or updates one summary comment on the current PR; detailed outputs remain available as artifacts.
6. A new commit makes the previous review visibly outdated. The same summary is refreshed after the new review completes.
7. A human reviewer evaluates findings. Fixes, false positives, and accepted risks are recorded against run and finding identifiers.

## 4. Scope and priorities

P0: Required for Phase 2 pilot exit. P1: Phase 2 enhancement after P0 gates pass; does not delay the pilot. P2: Independent Phase 3 or later work. Requirement IDs beginning with `P2-` identify Phase 2 work, not priority P2.

| ID | Priority | Deliverable |
|---|---|---|
| P2-01 | P0 | Live-model access, failure classification, and quality evaluation |
| P2-02 | P0 | Harden existing GitHub publishing; deliver controlled PR-event automation and a private repository pilot |
| P2-03 | P0 | Local CLI setup, preflight checks, and policy validation |
| P2-04 | P0 | Rule schema, applicability/exceptions, trusted base policy, and compatibility |
| P2-05 | P0 | Import existing CI evidence and represent report states accurately |
| P2-06 | P0 | Provider adapter contract and migration of the existing GitHub integration |
| P2-07 | P0 | Pilot feedback, measurement, failure/access scenarios, and client demonstration |
| P2-08 | P1 | GitHub Checks/annotations and controlled line-level feedback |
| P2-09 | P1 | Source/analysis caching and context selection within budgets |
| P3-01 | P2 | Customer-prioritized GitLab or Bitbucket integration |
| P3-02 | P2 | Multi-module Maven and classpath-based semantic analysis |
| P3-03 | P2 | Central administration UI if demand is validated |

Phase 2 excludes Gradle, additional programming languages, universal framework coverage, complete JAR API comparison, a general plugin marketplace, hosted multi-tenant SaaS, autonomous fixes, agent execution of generated tests, and mandatory merge blocking based on model findings. Adding a model provider is not a prerequisite for this phase.

## 5. Functional requirements and acceptance criteria

### P2-01 — Live model and evaluation

- Report authentication, model access, quota, rate limits, timeouts, and invalid output as distinct failure causes. Never include secret values in outputs or automatically switch to a paid fallback or another provider.
- Clearly distinguish replay and live results. Do not present authored replay metrics as model accuracy.
- Evaluate the existing 30 held-out cases with three repetitions across `collect`, `diff`, and `tools`. Use the live provider for model-driven modes; `collect` remains a model-free collection baseline and is not presented as static-analyzer accuracy.
- Never pass labels to the model. Cases used to tune rules or prompts are no longer treated as unseen; add a new frozen evaluation set when necessary.
- Evaluate at least 30 authorized historical PRs from at least two repositories. Include at least 10 benign controls. Quality claims also require at least 20 independently labeled, supported high-risk defect examples. Report sample counts separately; insufficient examples leave the quality gate unmet.
- Two reviewers independently assess technical correctness, importance, and regression-test usefulness. Record and adjudicate disagreements.

**Acceptance:** Deliver evidence of a successful live end-to-end run, frozen evaluation inputs, model/prompt/policy identities, complete result files, and the metrics in Section 9. Failed or incomplete runs must remain visible in success-rate reporting.

### P2-02 — Existing GitHub publishing and automatic PR workflow

- Reuse the existing metadata and publishing implementation. Retain maintainer-triggered execution as an available workflow.
- Deliver an administrator opt-in mode for automatic review and publication on trusted internal PR creation, new commits, and ready-for-review events. This mode is P0; manual execution alone does not satisfy Phase 2 acceptance.
- Skip automatic AI review of draft PRs by default. Forks and external contributions require a trusted maintainer's trigger. Event text and model output cannot establish authorization.
- Treat PR code as data. Never execute PR-controlled workflows, configuration, or build scripts in a context holding model credentials or publication permissions.
- Document and verify access for public and pilot private GitHub.com repositories. Treat Git fetch authentication and API authentication as separate concerns.
- Serialize or safely cancel runs for the same PR. An older run must not overwrite a newer result. After an ambiguous POST failure, query existing publication state before retrying so that duplicate comments are not created.
- Update only a summary owned by the authenticated application's bot and carrying the expected marker. A marker copied into another user's comment is insufficient.
- Check PR state and source/target commits before and after publication. Mark reports that become outdated during publication as stale; never display them as current success.

**Acceptance:** On a real test PR, demonstrate risky change → report → fix → update of the same comment. Verify that opt-in trusted PR events invoke review and publication, draft/external contribution rules are enforced, and authorized manual reruns still work. Test repeated triggers, concurrent runs, closed PRs, new heads, advanced targets, and publication failures without producing a falsely current report.

### P2-03 — CLI and setup

- Use the same versioned review engine locally and in CI. Show the JDK 25 runtime requirement and Java 21 analysis boundary during setup.
- Planned commands: `undertow doctor`, `undertow rules validate`, `undertow rules list`, and `undertow rules explain ID`. These do not exist at the time of this PRD. Preserve existing `review`, `pr-info`, and `publish` commands.
- By default, `doctor` checks local environment and configuration. Network/model preflight requires an explicit option. Report only whether credentials are present, never their values.
- Distinguish validation of the trusted review policy from validation of a proposed policy change. Validating a candidate policy does not let the PR replace its own governing policy.
- Preserve `review` exit-code compatibility. Document that `0` means artifacts were produced and does not certify absence of risk. CI must not interpret the process exit code alone as a successful AI review.

**Acceptance:** A new developer with JDK/Git and credentials already available produces a report on the sample repository within 30 minutes using the documentation. Missing JDK, policy, commits, or credentials produce actionable errors. Existing demo and evaluation commands remain usable.

### P2-04 — Rule system v2

Rules have two execution types: `contextual` rules use evidence-backed model interpretation; `deterministic` rules consume outputs from registered analyzers. A YAML sentence does not create a new analysis algorithm or grant tool permissions.

| Field | Requirement |
|---|---|
| Identity and version | Stable `id`, `version`, description, and owner; reject duplicate IDs and unknown fields |
| Normative content | `wording`, `strength`, `label`, and guideline reference; keep risk and merge decisions separate |
| Applicability | Language, include/exclude paths, execution type, and `enabled` |
| Evidence | Required data/tools; record `not_evaluated` and the reason when unavailable |
| Examples | Unsafe, corrected, and where possible benign counterexamples; fixture links |
| Exception | Rule and scope, justification, owner, expiration date; visible in reports |
| Executor | A registered analyzer identifier for deterministic rules; no arbitrary shell or HTTP definitions |

- Validate schema, enums, IDs, references, paths/globs, and dates while loading policy. Invalid rules fail before a model call.
- Provide a migration path for the v1 catalog. Validate existing `sections` references; the new format may support file/heading references. Do not silently change rule meaning.
- Load policy and exceptions from the trusted base commit. Adding an exception or `enabled: false` in the PR must not suppress its current review. Rule coverage identifies the policy version used.
- Phase 2 supports company rules as files pinned in the repository. Fetching mutable remote rule packs at runtime and multi-level organization inheritance are deferred to Phase 3.
- Never silently omit rules when the catalog exceeds the available budget. Report selected, out-of-scope, and unevaluated rules with reasons. Reading a file does not establish that every applicable rule was checked.

**Acceptance:** Add a contextual tenant-isolation rule without modifying application code, and run unsafe/corrected examples. Independently test invalid IDs, missing references, invalid globs, expired exceptions, head-side disabling, and missing evidence. A `BLOCKER` rule label does not become an AI merge gate.

### P2-05 — CI evidence and result contract

- Initially import JUnit results and SpotBugs findings, preserving existing dependency-tree evidence support. Prioritize other analyzers separately.
- Verify the repository, commit, and CI run associated with evidence. Hashes establish content integrity but do not establish a trusted origin; verify runner/provenance and artifact source too.
- The agent does not execute tests. Present CI-executed tests as execution evidence separately from the model's `proposed` regression tests.
- Group overlapping deterministic and model findings while preserving their sources. Unverified CI input is not evidence of success.
- Preserve `complete/partial/failed/skipped` review status. Track publication separately as `published/stale/failed/not_requested`; API failures must not destroy review artifacts.
- Complete means the review process finished within its declared scope and budget, not that every possible defect was found. Never present partial, failed, or skipped results as clean or approved.

**Acceptance:** Reject evidence with a wrong SHA, modified content, wrong CI run, stale report, or missing artifact. Distinguish test failures, analyzer findings, and AI proposals in the report. Existing test CI continues if AI review fails; the review failure remains visible.

### P2-06 — Git-provider adapter boundary

Use `ChangeRequest` as the core concept and the provider's PR/MR terminology in user-facing output. The model provider (`ModelClient`) and Git-provider adapters are independent boundaries.

- `ChangeRequestRef`: provider, instance address, stable repository identity, PR/MR number, and URL.
- `ChangeSnapshot`: source head, target head, analysis diff base, trusted policy commit, and provider diff version when relevant. A merge base and the current target tip are not interchangeable; document each field's semantics.
- Separate metadata reading, Git snapshot access, summary publication, and optional status/line reporting through narrow interfaces. The core must not depend on API URLs, token types, or the literal `github-actions[bot]` identity.
- Adapters declare capabilities: summary comments, commit status, annotations, and discussion updates. Use summary/artifact fallback where an optional capability is unavailable and disclose the limitation.
- Bot identity, pagination, rate limits, retries, comment update versions, and source-line mapping belong to the adapter.
- Validate instance addresses and repository identities. PR content cannot choose a new API host or credential destination. Private Git fetch credentials must not enter logs or artifacts.
- Canonical finding identity must not rely on the model generating the same ID each run. Use a fingerprint based on provider/repository/change/rule and location context to reconcile repeated publications; test false matches.

**Acceptance:** Move the existing GitHub adapter behind this contract and verify public and newly supported private flows. An offline fake adapter consumes the same report without changes to the review core. Shared tests cover bot ownership, stale commits, repeat publication, pagination, permission errors, and rate limits. Passing fake-adapter tests does not establish GitLab or Bitbucket support.

### P2-07 — PR experience and pilot feedback

The first screen of the summary shows review status, reviewed commit, important finding count, major coverage gaps, and a detailed-report link. Each finding includes a code link, risk, confidence explanation, triggering condition, evidence, and proposed test. Keep long evidence in artifacts and respect comment size limits.

The client demonstration contains three flows: duplicate-payment risk and its correction, a company-specific rule, and missing model/CI evidence. Clearly label replay and live demonstrations. Do not present authored output as live when credentials fail.

Pilot feedback may use a versioned local JSON/CSV file containing run/finding identity, correct/incorrect/uncertain assessment, action, and rationale. A separate web UI is not required. Silently deleting feedback or having the model grade its own finding as correct is unacceptable.

**Acceptance:** A reviewer can reach evidence for two important findings from the PR, distinguish resolved findings, and record false-positive feedback. A partial report with no findings, a live-access failure, and an unsupported file must be clearly visible.

### P1 enhancements

- **P2-08:** GitHub Checks and a bounded number of valuable line-level annotations. Use summary links when line mapping cannot be verified. Contract tests cover excessive comments, duplicate annotations, and provider status mappings. Verify required token permissions; marketplace distribution of a GitHub App is not required. GitHub Checks supports rich reports and annotations. [Official documentation](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-checks)
- **P2-09:** Key source/parsing caches by repository, commit, and analyzer version. Do not blindly reuse model output. Reevaluate reviews when policy, model/prompt, or CI evidence changes. Allow disabling/clearing caches and prevent cross-repository data leakage.

## 6. UI and CI strategy

| Surface | Phase 2 decision | Rationale |
|---|---|---|
| CLI | P0 | Local diagnosis, reproducibility, and execution on any compatible CI runner |
| PR summary comment | P0; primary product surface | Visible where reviewers already work |
| JSON/Markdown artifacts | P0 | Detailed inspection, retention, and downstream integration |
| Checks / line annotations | P1 | Better navigation; not a prerequisite for basic reporting |
| Web dashboard | Phase 3 discovery | Build after validating multi-repository policy and history needs |
| IDE extension | Later | Assess demand after the CLI/PR pilot |

The Git host and CI provider need not be the same. For example, a Bitbucket repository can be reviewed on Jenkins or another runner. Given a local repository and pinned commits, the CLI can produce a report. Native PR/MR publication needs the relevant adapter: GitHub already has one; GitLab and Bitbucket do not yet have one.

## 7. GitHub, GitLab, and Bitbucket roadmap

| Platform | Current state | Planned integration | Phase |
|---|---|---|---|
| GitHub.com | Existing metadata/publishing adapter and manually triggered workflow for public repositories; hosted live verification incomplete | Harden existing implementation; public/private pilot, one summary, controlled automatic triggers; then Checks | Phase 2 |
| GitHub Enterprise Server | No custom-host/version support | Separate verification against an instance/version/authentication matrix | Phase 3 if customer demand requires it |
| GitLab.com | No native MR adapter | MR metadata, updated note, commit status; then inline discussions | Phase 3 |
| GitLab Self-Managed | No native support | Configurable host in the same provider family; selected server versions, CA/network, and access tests | Phase 3 according to pilot needs |
| Bitbucket Cloud | No native PR adapter | PR metadata/comments, build status; then Code Insights reports/annotations | Phase 3 |
| Bitbucket Data Center | No native support | Separate API adapter, server-version and access matrix | Phase 3 if customer demand requires it |

GitLab supports MR summaries through its Notes API and line discussions through its Discussions API. Commit statuses are a separate integration. GitLab's External status checks feature is documented as Ultimate-tier; basic MR reporting must not depend on it. [Notes](https://docs.gitlab.com/api/notes/) · [Discussions](https://docs.gitlab.com/api/discussions/) · [Commit status](https://docs.gitlab.com/api/commits/#set-commit-pipeline-status) · [External status checks](https://docs.gitlab.com/user/project/merge_requests/status_checks/)

Bitbucket Cloud supports PR comments and commit statuses through its API; Code Insights provides richer reports and annotations. Data Center uses a separate REST API family. Do not assume Cloud and Data Center share URL or authentication conventions. These platform capabilities make adapters feasible; they are not implemented Undertow features. [Cloud PR API](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/) · [Cloud statuses](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/) · [Code Insights](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-reports/) · [Data Center PR API](https://developer.atlassian.com/server/bitbucket/rest/v816/api-group-pull-requests/)

### Phase 3 entry and sequencing

After Phase 2 exit criteria pass, select one provider with validated demand. Without a customer preference, the default order is GitLab followed by Bitbucket Cloud. This is a commercial planning assumption, not a technical dependency. Give Bitbucket Data Center and GitLab Self-Managed distinct acceptance scopes once customer server versions and access are known.

Each adapter's first release includes metadata, public/private source access, one updated summary, stale-commit checks, a CI example, and artifact links. Rich inline/Code Insights output and mandatory merge checks are subsequent increments. Full feature parity is not a first-release requirement.

**If the customer requires another platform in Phase 2:** Bring that adapter's basic release into the pilot and defer P1 Checks/cache work. Preserve P0 correctness, trusted policy, and provenance requirements. Do not commit to one undifferentiated Bitbucket integration estimate before identifying Cloud versus Data Center and the required version.

**Adapter acceptance:** On a real test PR/MR, verify creation, updates, wrong bot identity, force-push, target advancement, closed requests, permission loss, rate limits, reruns, and network interruption. Map GitLab diff SHAs and Bitbucket line anchors according to their API contracts; never attach a finding to an unverified line. Switching providers must not change the meaning of the canonical findings, risks, or evidence.

## 8. Operational and data requirements

- Preserve time, model-call, tool-call, and token budgets. Produce visibly partial results for large diffs or unsupported files; disclose any reduction in coverage.
- Treat repository content, CI output, and model responses as untrusted data. The review loop receives no shell, build, source-write, or publication authority.
- Read API keys and tokens from environment variables or a secret store, never from reports or traces. Verify log redaction with sample credential tests.
- Source and evidence may contain sensitive data. Restrict pilot artifacts to repository access; target seven days of default CI retention, configurable to organizational policy. Explain external model data flow during setup.
- Preserve local artifacts after publication failure and allow republishing without another model review. Measure review and publication outcomes separately.
- Record runtime/model dependencies and schema versions in reports. If report v2 is needed, provide migration notes and fixtures for existing consumers; do not silently reinterpret v1.

## 9. Success metrics and exit gates

These thresholds are Phase 2 pilot targets, not measured current performance. Publish dataset/sample counts, model versions, uncertainty intervals, and limitations alongside results. Repetitions of the same example do not count as new independent cases.

| Metric | Measurement | Pilot exit target |
|---|---|---|
| High-risk precision | Correct HIGH/CRITICAL/HIGH_CRITICAL findings divided by all findings at those levels | At least 80%; report numerator/denominator and 95% confidence interval |
| Supported-defect recall | Detected labeled high-risk defects divided by supported labeled high-risk defects | At least 70%; failed/partial runs cannot remove missed examples from the denominator |
| Benign false alarms | Benign PRs receiving a false behavioral-risk finding divided by all benign PRs | At most 10%; disclose small-sample uncertainty |
| Regression suggestion usefulness | Suggestions graded actionable and relevant divided by assessed suggestions | At least 80%; human assessment |
| Review completion | Completed live tools runs divided by live tools runs with supported, valid inputs | At least 90%; external-service failures remain in the denominator and are also reported separately |
| Repeat variability | Finding, severity, and status differences across three runs of the same case | Report all differences; zero safety/evidence-contract violations |
| Latency | Runner start to artifact availability on reference PRs with at most 20 changed files and 500 changed lines | p95 at most five minutes; measure CI queue time separately |
| Cost | Per-run usage/tokens and estimates using explicitly supplied current prices | Within the pilot budget; missing pricing means unknown, not free |
| Publication reliability | Tested stale, duplicate, and bot-ownership scenarios | Zero falsely current reports, unauthorized comment changes, or duplicate summaries |
| Setup | Time to first demo report with dependencies and access ready | At most 30 minutes |

No findings or insufficient positive samples cannot count as passing precision/recall. Report real-PR and synthetic results separately. If targets are unmet, continue evaluation or narrow the supported scope rather than declaring pilot readiness. Mandatory AI-based merge gating remains outside Phase 2 even if these thresholds pass.

## 10. Delivery sequence

| Milestone | Work | Exit evidence | Dependencies |
|---|---|---|---|
| M0 — Live foundation | Model preflight, error classification, frozen evaluation set | Successful live review and visible failure report | Usable model access and permission to use data |
| M1 — Contracts | Rule v2, migration, CLI validation, provider interfaces | Passing schema/contract tests and v1 regressions | M0 quality evaluation may continue in parallel |
| M2 — Everyday PR workflow | Migrate and harden existing GitHub adapter; private fetch, PR-event automation, publication, CI evidence | Real risky/corrected PR demo, including automatic triggers and manual reruns | M1, test repository, and runner permissions |
| M3 — Pilot and measurement | Real-PR evaluation, feedback, documentation | Section 9 results and remaining issues | M0–M2 |
| M4 — Enhancements | Checks/annotations and targeted caching | Equivalent results with improved latency/navigation | After P0 gates pass |

This PRD does not commit to a schedule or effort estimate. Size the work once the customer platform, runner access, data permissions, and evaluation samples are ready. Each implementation PR must reference requirement IDs, acceptance tests, and a rollback procedure.

## 11. Risks, open decisions, and definition of done

| Risk / decision | Assumption or response | Decision point |
|---|---|---|
| First customer platform | Default to GitHub; bring a basic GitLab/Bitbucket adapter forward if required | Product owner before M1 |
| Bitbucket edition / self-hosted version | Cloud and Data Center require separate acceptance scopes | Before sizing adapter work |
| Model access and data sharing | Start with the existing provider, fixtures, and authorized PRs | M0 |
| Insufficient real-defect samples | Document the gap and defer quality claims | M3 |
| False positives | Human feedback, narrow scope, deterministic evidence | Every evaluation/pilot iteration |
| Rule/prompt overfitting | Used examples become tuning inputs; freeze a new held-out set | Each policy/model change |
| Enterprise multi-module requirements | Check pilot compatibility early; scope multi-module support separately | M0 |
| Review cost | Pilot owner sets run/budget limits; pricing supplied separately | Before live pilot operation |
| Platform version/license differences | Publish a minimum feature set and tested version/plan matrix | Each adapter release |

Phase 2 is complete when all P0 acceptance criteria and measurement gates pass, existing tests/replay regressions pass, and the real GitHub PR workflow is verified end to end. This includes controlled automatic review/publication for trusted PR events and retained maintainer-triggered execution. Deliver documentation for setup, rules, credentials, partial outcomes, rollback, and demonstrations. Clearly distinguish live-model and replay demonstrations and list unsupported platforms and analysis limits in release notes.

## 12. References

Repository references: [Policy](../src/main/java/dev/undertow/policy/Policy.java), [Reviewer](../src/main/java/dev/undertow/harness/Reviewer.java), [GitHub publisher](../src/main/java/dev/undertow/github/GitHubClient.java), [workflow](../.github/workflows/agent-review.yml), [CLI](../src/main/java/dev/undertow/cli/Undertow.java), [evaluation](evaluation-report.md), [showcase](client-showcase.md).

Platform capabilities were checked against the official GitHub, GitLab, and Atlassian documentation linked above on October 5, 2026. The Data Center link is an API-version example, not an Undertow server-version support commitment. Recheck authentication methods, permissions, licensing, and server-version requirements when implementing each adapter.
