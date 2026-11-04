# Undertow: consolidated AI-era strategy and roadmap

Date: November 3, 2026. Status: proposed product direction and delivery sequence.

Engineering now covers the roadmap's local, CI, agent, release, and operational evidence paths. See [implementation status](ai-era-implementation-status.md) for observed checks and outstanding external acceptance; the planning assessment below remains historical context.

This roadmap defines product priorities, implementation boundaries, and acceptance requirements. Existing product behavior and acceptance gates remain as documented until explicitly changed.

## Conclusion

Develop Undertow as a **contract verification and change assurance system for engineers and coding agents**. Start with Java/Maven and existing CI. The first valuable product is a small contract guard that tells a team which declared requirements are satisfied, violated, or still unverified for a specific change.

Deterministic execution does not automatically make an AI-authored expectation correct or establish production harmlessness. Evidence strength, automatic blocking, implementation scope, and delivery order need explicit decisions.

Use this division of responsibility:

| Participant | Responsibility |
|---|---|
| Contract owner | Define intended behavior, external assumptions, required checks, acceptable exceptions, and operational limits. |
| Coding agent or engineer | Implement the change and propose contracts, tests, and fixes. |
| Independent CI/verifiers | Execute approved checks against immutable subjects and emit trustworthy evidence. |
| Undertow | Connect contracts to evidence, identify gaps and risk, explain results, and evaluate declared policies. |
| Existing delivery/operations systems | Enforce enabled checks, deploy the verified artifact, observe behavior, and halt or recover when necessary. |

Undertow's distinctive value is the relationship between intent and evidence. It should integrate existing testing, scanning, delivery, and monitoring systems rather than recreate them.

## Product priorities

Prioritize executable business contracts, pinned policy and read-only model boundaries, simpler onboarding, and evaluation that distinguishes authored replay from live-model quality. Keep mechanical formatting in build checks and hosted feature expansion paused.

Connect contracts to tests and constraints, detect enforcement changes, and assess verifier sensitivity with reviewed mutations. Provide early agent feedback through working-tree previews and optional SARIF. Keep verification and release decisions separate, preserve artifact identity through deployment, and require operational acceptance.

## Implementation decisions

| Topic | Decision |
|---|---|
| Hosted service | Freeze feature investment in place. Preserve security fixes and tests. Extract only when build/runtime coupling causes a measured problem. A restructuring project is not the first milestone. |
| Style and coding guidance | Remove mechanical style assessment from the model path, while retaining useful design, security, and maintenance guidance. The guide is a policy source referenced by existing rules; do not delete it wholesale. |
| Contract representation | Introduce one minimal `.undertow/contracts.yaml` for new adopters, linked to exact approved verifiers. Provide versioned built-in defaults and explicit migration from old layouts; retain report compatibility. |
| Evidence versus truth | Grounding verifies references and provenance. Semantic conclusions still need an appropriate oracle, assumptions, and independently assessed quality. |
| Automatic merge blocking | Begin in shadow mode. Gate only explicitly required, owner-approved contracts with valid evidence. An AI-generated assertion does not become authoritative merely because CI executed it. |
| Enforcement weakening | Detect structural changes, but distinguish a change from a proven weakening. Assertion count is not a correctness measure. Test renames and equivalent constraints need approved remapping and behavioral verification. |
| Generated test execution | Use an isolated CI stage outside the model worker. The model emits a candidate artifact and has no shell or execution authority. Reuse the existing trusted importer. |
| Base/head differential | Require a relevant assertion failure with a trusted expected behavior, not compilation, dependency, or infrastructure failure. Repeat to detect flakiness. For intentional behavior changes, compare against the newly approved contract. Existing violations can fail required checks even when present on both revisions. |
| Mutation labels | Validate mutation operators and outcomes. Exclude uncompilable, equivalent, or out-of-contract mutants. Mutations assess verifier sensitivity; independently labeled historical PRs assess model usefulness and generalization. |
| Live evaluation timing | Start baseline measurement early after a budgeted preflight, then maintain it throughout. Deterministic contract work can progress without model availability. Frozen test sets and failure denominators remain mandatory. |
| Blocking quality target | An advisory accuracy target cannot authorize blocking. Choose gate-specific tolerances with owners after shadow results; report sample counts and uncertainty. Zero observed false blocks is not a universal zero-false-positive guarantee. |
| Agent integration | Stabilize a structured CLI result first. Expose contract reads early, then a thin MCP transport over the same implementation. Add local previews without granting them release authority. |
| Working-tree trust | The feature branch HEAD is not inherently trusted. Capture a fixed local candidate and a configured trusted policy commit separately; show freshness and scope. Recheck the actual merge candidate in CI. |
| Analysis tooling | Keep bounded source navigation with its stated limitations. Import semantic/compatibility evidence only where a pilot requires it; avoid building a compiler or supporting every ecosystem at once. |
| API/database/security checks | Add one demonstrated verifier gap at a time. API compatibility, DDL locking, timeouts, and secret handling depend on declared scope and environment; an AST or schema diff alone cannot certify all runtime behavior. |
| SARIF and annotations | Add an output adapter after result semantics stabilize and a pilot needs the integration. Preserve source identity and verify destination support. PR/JSON output is sufficient for the first increment. |
| Python/Java split | Present one supported user command, initially wrapping proven helpers where appropriate. Migrate implementation only when packaging or support costs justify it. |
| Quiet output | Avoid repetitive comments for confirmed irrelevant changes. Keep unsupported scope, failed runs, and missing required evidence visible in a status result. Unknown applicability is not confirmed irrelevance. |
| Documentation and LOC targets | Optimize onboarding and maintenance outcomes. Maintain a short entry path and current status; retain detailed contracts, security, and historical evidence. LOC and document count are diagnostics, not exit gates. |
| Returning to hosting | Reconsider hosting after demonstrated user value and the service's own operational/security gates. Customer runtime integration is not an absolute prerequisite for a narrowly scoped hosted verifier. |

## Corrections grounded in this checkout

**Payment uniqueness and order uniqueness are different contracts.** The SQL primary key and `OrderIntegrityIT.concurrentRequestsCommitOnlyOneOperation` establish properties of order/event records. In [OrderService](../demo/order-service/src/main/java/dev/undertow/demo/OrderService.java), the gateway call occurs before the database write. A unique order row cannot by itself prevent two external charges. The demo gateway supplies its own idempotency behavior. Bind separate order-uniqueness and payment-effect contracts to appropriate tests, including observable gateway effects and explicit gateway assumptions. [PaymentBehaviorTest](../demo/order-service/src/test/java/dev/undertow/demo/PaymentBehaviorTest.java) provides a useful lost-response example; more scenarios are needed for a broader claim.

**The hosted footprint is substantial, but its size is not itself a defect.** The current checkout contains 4,099 service-package Java lines out of 8,792 production Java lines, approximately 46.6%. This supports a prioritization discussion, not deletion of authorization, encryption, accounting, or isolation code used by an active deployment.

**Live output exists, but evaluated reliability is still unestablished.** Inspection of a retained local report found `mode: live`, one finding, five model calls, and overall `status: partial`. The October status documents do not capture all retained local activity. This is narrower than a completed accuracy benchmark and does not satisfy the independent pilot gates. This historical observation does not include a new provider call.

**The entire coding guide is not injected into every prompt.** `Reviewer.promptRules()` selects rule fields, and `Policy.rule()` can supply referenced sections. Remove irrelevant rule selection and assess prompt usage rather than assuming the guide's full size equals per-call token cost.

## Result and authority model

Keep three separate records:

1. **Review execution:** the existing complete/partial/failed/skipped status, including model and tool failures.
2. **Contract verification:** satisfied/unsatisfied/incomplete, with explicit out-of-scope and approved-exception records.
3. **Release decision:** eligible/held according to an explicitly enabled owner policy, followed by deployment and runtime observations.

A contract is satisfied only when its required verifiers ran successfully for the correct candidate and policy version, using approved expected behavior. Empty suites, missing test IDs, skipped checks, wrong commits, untrusted results, and unsupported required verification produce incomplete results. Keep raw test execution outcomes distinct from this aggregate decision.

Record evidence strength separately: hypothesis, source-supported observation, reproduced behavior, or approved-verifier result. These are not interchangeable levels of global safety. A credible security concern remains visible even when reproduction is unavailable; suitable static evidence can satisfy an explicitly defined security rule without executing an exploit.

The model may propose impact mappings and additional tests, but cannot reduce mandatory scope or approve expected behavior. Contract/test/policy changes require the appropriate ownership workflow. Generic bundled defaults must have versions and hashes, and must not silently assert organization-specific business intent. Contract files remain inert; they cannot select credentials, execute arbitrary commands, or change host budgets.

## Delivery roadmap

Effort ranges below are planning estimates for one engineer familiar with the repository and timely domain-owner/CI support. They are not deadlines. Re-estimate after the first contract slice. Progress depends on acceptance evidence, and no external API access, model spend, or automatic merge gating is authorized by this document.

| Stage | Scope and deliverables | Exit evidence | Indicative effort |
|---|---|---|---|
| 0. Focus and establish baseline | Freeze hosted feature expansion; choose one pilot repository; define initial contract owners and expected behavior; inventory existing tests; distinguish harness regression, verifier tests, and live evaluation; publish a current status entry. Plan a bounded live baseline separately. | Agreed contract examples and verification subjects; known gaps recorded; baseline artifacts reproducible. Model unavailability does not stall deterministic work. | 2-3 days |
| 1. Contract-to-evidence MVP | Minimal versioned manifest; exact JUnit bindings; trusted policy loading; existing CI import; satisfied/unsatisfied/incomplete output; basic PR summary. Start with payment idempotency and separate order-state contracts. | Corrected case passes; known violation fails; missing/disabled/zero-match tests are incomplete; stale evidence is rejected; head-side policy weakening cannot create success. Runs without a model key. | 1-2 weeks |
| 2. Human and agent feedback | One supported CLI entry, structured JSON, concise PR results, contract reads before coding, optional thin MCP wrapper, immutable staged/working-tree previews, migration guidance and packaged defaults. | An unfamiliar engineer adopts one meaningful contract and an agent reads/verifies it; local candidate mutation invalidates old evidence; untrusted policy changes cannot acquire authority. Measure onboarding and failure-understanding time. | 1-2 weeks |
| 3. Stronger verifiers and reproduction | Add tenant-isolation checks and the highest-value compatibility/security gap from the pilot. Support approved candidate regression artifacts executed in isolated CI. Add targeted mutation/property-based tests, not a generic framework. | Relevant assertions reproduce against the appropriate baseline/candidate; compile/infra failures cannot count as reproduced defects; seeded violations are detected; mutable/generated expectations cannot self-approve. | 2-3 weeks |
| 4. Governed pilot and optional gates | Run shadow verification on real authorized changes; measure false alarms, missing evidence, usefulness, latency, and cost. Enable only selected mature required contracts after owner review. Add optional SARIF/annotations if needed. | Exact binding and evidence rejection cases pass; defined gate-specific tolerances are supported by an adequate sample; required unknowns hold release; disputes/temporary exceptions have owners and expiry. Existing live-quality gates remain applicable to AI claims. | 1-2 weeks for integration; observation/sample time is additional |
| 5. Release and operational feedback | Verify merge candidate and artifact provenance; attach deployment digest and contract versions; integrate existing canary/monitoring/halt/recovery systems for one business flow. Reassess hosted service demand and operational readiness independently. | A controlled failure is observed and contained; the deployed artifact links to evidence; recovery handles irreversible effects; service rollout meets its own isolation, revocation, cleanup, restore, and incident gates. | Scope after choosing a deployment environment |

The first useful deliverable is Stage 1, approximately 2-3 weeks including focus work under these assumptions. The model benchmark and user feedback continue across stages; do not defer all measurement until the end. No claim of complete AI-era transformation depends on meeting a nominal week count.

### First implementation backlog

| Work item | Main implementation area | Acceptance example |
|---|---|---|
| Define contract schema and approved expectations | New contract schema/model; `Policy` and `RuleCatalog` integration | Duplicate IDs, unknown verifier identities, and malformed scopes are rejected; candidate contract validation does not change current authority. |
| Bind contracts to individual executions | `CiEvidence`, trusted importer, JUnit normalization | An unrelated green JUnit file cannot satisfy the payment contract; skipped and absent required IDs remain incomplete. |
| Compute verification independently of the model | A small deterministic evaluator with its own result contract | The same verified input produces the same result when the model is unavailable; AI output cannot overwrite that result. |
| Protect expectation and evidence changes | Policy source, approved test inventory, candidate identity | Deleted/renamed/changed acceptance tests trigger explicit remapping or review; stale or conflicting results cannot become green. |
| Demonstrate actual business behavior | Existing payment and order demos; later customer tenant example | Count gateway effects separately from database records; show both corrected and deliberately violating behavior. |
| Present one actionable result | JSON/Markdown reporting and current publisher | An engineer sees the affected contract, required failed/missing check, evidence link, and next action without reading a trace. |
| Dogfood the same mechanism | Existing boundary/publication tests | Declare Undertow's own contracts for trusted policy, evidence-backed publication, credential separation, and no paid fallback. |

Commands such as `contracts validate`, `verify`, `init`, and an MCP endpoint are proposed interfaces, not currently delivered capabilities. Choose final names while preserving existing CLI behavior. Defer large file moves, storage migration, new SaaS features, and a broad security-tool catalog from this first backlog.

### Execution and local-preview boundaries

Generated tests and repository build scripts execute in a disposable CI environment with bounded CPU, memory, process count, and time. Keep model, publisher, production, and control-service credentials outside it. Prepare dependencies through a controlled stage or cache and constrain network access; simply requesting an offline Maven invocation does not establish isolation. Use clean independent environments for compared revisions to prevent contamination. Execution provenance must identify both the application subject and the test/oracle artifact.

Local previews capture an immutable content manifest, fixed policy version, and explicit file scope. Start with tracked/staged content; untracked inclusion is deliberate, with existing path/symlink and sensitive-file controls. A preview must never be represented as evidence for a subsequently changed tree or the final merged artifact. MCP adds a transport, not additional authority.

### Evaluation and promotion rules

Maintain four distinct evidence sets: deterministic harness regression; approved-verifier checks including validated mutations; live AI evaluation with independently labeled held-out and historical changes; and operational rollout/recovery exercises. Never combine their scores into a single accuracy claim.

Start with existing Phase 2 evaluation requirements: frozen held-out cases, repeated live runs, authorized historical PRs across two repositories, benign controls, sufficient high-risk positives, and independent reviewers. Authored replay remains useful for harness behavior. Failed, partial, timed-out, and quota-limited runs remain in the denominator. A model/prompt/verifier update runs the relevant frozen checks before promotion; nightly paid evaluation is optional and budget-controlled.

For blocking, trust approved verifiers and reviewed expectations, not an aggregate model precision number. All acceptance fixtures for missing, stale, tampered, skipped, empty, and wrong-subject evidence must fail to grant satisfaction. Record real false blocks and their cost before deciding which contracts may be mandatory. A new generated verifier enters as a candidate and becomes mandatory only through the contract ownership process.

Measure time to first meaningful contract, time to understand a failure, required contracts with current evidence, escaped contract violations, supported mutation detection, human-confirmed useful findings, false alarms/blocks, evidence freshness, and verification latency/cost. The proposed 15-minute onboarding and 10-second local-feedback goals are useful hypotheses to test on a documented workload; they are not established performance claims.

## Production and hosting priorities

Runtime signals must use an independently meaningful observation: an order-row count cannot detect every duplicate external payment. Define the monitored scope, expected traffic, observation window, detection delay, owner, and mitigation. A quiet canary without relevant traffic is incomplete evidence. Rollback stops future effects; payments, messages, and destructive migrations may require reconciliation or forward recovery.

Continue to retain authorization, provenance, isolated worker/publisher boundaries, budget controls, and explicit incomplete results. Preserve dependency investigation as supporting evidence. Use targeted API/schema/database/security adapters and property tests only where they close a declared gap.

Before broader hosted access, the existing Phase 3 gaps still require evidence: actual container/egress isolation, crash cleanup, key/access revocation, stale publication handling, backups/restore, tenant separation, alerts, and incident response. Revisit H2/global serialization and modularization based on actual load and maintainability. Hosting may resume when core value and these gates are established; a live-model benchmark alone is insufficient.

## Planning evidence

The planning assessment checked payment flow/tests, policy/prompt construction, the production source footprint, and selected metadata from a retained live report. No source excerpts from local runtime artifacts are included here.

Historical test counts are not fresh live, sandbox, or production validation. Consult the [implementation status](ai-era-implementation-status.md) for delivered behavior and observed checks.
