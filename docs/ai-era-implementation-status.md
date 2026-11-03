# AI-era implementation and acceptance status

Date: November 3, 2026. This is the current cross-project status. The [consolidated roadmap](ai-era-consolidated-roadmap.md) is the active plan; the original project plan and Phase 2/3 documents retain historical design and specialist acceptance requirements.

## Engineering across the six stages

| Stage | Implemented | Observed acceptance and remaining limits |
|---|---|---|
| 0. Focus and baseline | Internal Java/Maven sample repositories; separate payment, tenant, API, and order-state expectations; dogfood contracts; distinct regression, verifier, AI-evaluation, and operational evidence sets. Hosted feature expansion remains paused. | User authorized sample repositories for this execution. Demo owners/labels are explicitly synthetic. Existing real-customer ownership, budgeted live-model baseline, and independent pilot requirements remain separate. |
| 1. Contract evidence | Versioned inert manifest/result schemas; exact JUnit bindings; approved policy/oracle identity; trusted CI import; complete/incomplete/violating outcomes; Markdown and JSON; test/build-control inventory changes, including newly introduced Maven controls. | Local adversarial evidence rejection and no-model execution pass. Synthetic imports exercise the receipt boundary. Real GitHub workflow evidence is accepted only through the trusted importer, not local files claiming origin. |
| 2. Human and agent feedback | Versioned/hash-identified starter; runtime-aware launcher; structured contract reads; thin read-only stdio MCP; immutable staged/working previews with explicit scope, deliberate untracked files, and freshness invalidation; migration guidance. | Packaged initialization/read/verify and all gallery cases exercised. Local candidate edits invalidate previews. Human onboarding time and selected-client MCP compatibility still require observation; no timing goals are advertised as achieved. |
| 3. Stronger verification | Negative tenant authorization, shared identifiers across 32 tenants, declared public API shape, repeated payment/tenant mutations, approved external-oracle archive plans, bounded CI-only Docker runner, and repeated independent baseline/candidate comparison. | Relevant assertions detect reviewed mutations; errors/skips/flakiness cannot count as reproduced defects. Real local Docker execution passes UID/network-route/filesystem/socket/credential surface checks using a pinned image. This does not certify arbitrary Docker hosts or every compatibility/security property. |
| 4. Governed pilot and gates | Shadow study measurement, separate independent reviewer labels and uncertainty, opt-in pinned release policy, exact-subject expiring exceptions, required-unknown holds, optional SARIF. AI review cannot self-enable gates. | Synthetic shadow studies exercise measurement without granting promotion authority. Gate/exception rejection cases pass locally. Real user samples and owner-selected risk tolerances remain necessary before enabling gates outside the fixture. |
| 5. Release and feedback | Exact-head build capture/import, candidate/target ancestry check, tested artifact digest attestation, trusted monitoring-export boundary, minimum traffic/window/freshness rules, halt confirmation, and irreversible-effect reconciliation. | Sample exercises cover substituted binaries, quiet canaries, violations, containment, and recovery. They use simulated external receipts and execute no production action. Real deployment/exporter integration and hosted operational gates remain environment-specific acceptance. |

Implementation across stages does not make the empirical gates interchangeable. A source reference is not a semantic oracle; mutation sensitivity is not live-model quality; a synthetic receipt is not real CI provenance; a controlled recovery decision is not a production recovery rehearsal.

## Reproducible evidence

The [README](../README.md) is the main cookbook. [Contract verification](contracts.md) documents the trusted importer and exact bindings; [advanced assurance](assurance.md) documents previews, MCP, independent reproduction, isolated execution, release policy, and operations. Generated artifacts remain ignored under `.undertow/`.

| Check | Current evidence |
|---|---|
| Full harness `mvn spotless:apply verify` | Final local run passes: 139 tests with no failures/errors/skips, formatting, and zero High-threshold SpotBugs findings. |
| Workflow/helper tests | 28 Python tests pass, including archive traversal, source/oracle substitution, execution flags, imported build/oracle records, and malformed JUnit rejection. |
| Order/payment application | Five unit tests and three Docker/PostgreSQL integration tests pass with JDK 21. |
| Customer application | Five tests pass, including cross-tenant payment denial, 32 tenants sharing an ID, and explicit API-shape checks. |
| Reviewed mutations | Payment-key and tenant-filter mutations each produce two corrected passes and two relevant assertion failures with unchanged oracle hashes. |
| Full synthetic showcase | Six independent real Maven/JUnit fixture builds; separate payment and tenant violations; exact evidence rejection; stale preview; MCP; eligible/mismatched artifact; healthy/quiet/violating/contained/reconciled rollout states. External CI/monitoring receipts and reviewer labels are simulated. |
| Isolated external oracle | Official Maven/JDK image resolved from its registry and pinned by digest; two real JUnit checks pass in an unprivileged, network-disabled, read-only-root container with bounded CPU/memory/PIDs/time and cleanup. No provider/publisher credentials or host Docker socket are mounted. |
| Dependency upgrade | Baseline and corrected Spring callers compile and run; the intervening historical upgrade fails compilation with the expected selected API incompatibility. This is compatibility evidence, not a behavioral assertion failure. |
| Evidence gallery | All six case selectors and result links inspected in the browser; README preview captured from the generated gallery. No third-party scripts/fonts/analytics. |
| Authored AI replay | Earlier stable-JAR run: 120 partial reports with no process/provider failure causes and no expected-finding mismatches in diff/tools. Partial coverage is retained. These are authored harness checks, not live-model accuracy or completed semantic reviews. |
| Public content / secret scans | English/public-content check passes, including the translated dependency-demo guide. Checksum-verified Gitleaks 8.30.1 reports no leaks in available Git history. The candidate export scan also reports no leaks. Relative Markdown links and anchors resolve after cleanup. |

The initial replay attempt overlapped JAR packaging and recorded one unavailable process. That failure remains in its retained denominator. A separately retained stable-JAR replay removed the packaging overlap; the failed attempt was not erased or relabeled.

## Cleanup and maintenance

One current implementation-status document replaces the obsolete alpha summary. The earlier change-assurance proposal is superseded by the consolidated roadmap. Two unused demo/recording wrappers were removed; the actual optional AI replay corpus, recording artifact, compiler fixture checker, and legacy CI format adapter remain because they have distinct compatibility/evidence purposes. Links now point to the current cookbook/status. The dependency demo's content is preserved in English.

Hosted source, security tests, encrypted-state/accounting boundaries, policy guidance, and Phase 2/3 acceptance records are retained. A hosted feature freeze is not removal of those supported interfaces. Repository-native source artifacts remain separate from generated local reports.

## External acceptance still open

No new paid/live-model request or production deployment was made. Existing frozen held-out/historical-PR evaluation, independent human assessment, owner agreements, actual exporter authentication, real canary/incident exercises, and the service's own isolation, egress, crash cleanup, revocation, restore, deletion, and incident gates are not supplied by synthetic fixtures. Consult [Phase 2 status](phase2-status.md), [Phase 3 status](phase3-status.md), and [evaluation requirements](phase2-setup.md#evaluation-and-feedback-p2-01-p2-07).

A real organization should review the contracts, run shadow verification on authorized changes, select tolerances from an adequate sample, enable only mature required contracts, and bind the final tested artifact to its actual deployment/monitoring system. The implementation leaves those authority decisions explicit.

## Next product increment

The [public playground and pilot plan](next-pilot-and-public-playground.md) defines the next adoption sequence. Its [local prototype](../demo/pr-workbench/README.md) presents a three-part loop: draft business rule, action, and observed result. Two owned public PRs with real GitHub-imported JUnit results sit in a separate expandable reference. It distinguishes archived verification, local simulation, and public metadata. This adds a concrete demonstration; independent adoption and operational acceptance above remain open.
