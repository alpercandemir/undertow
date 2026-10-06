# Current Phase 2 work — October 5, 2026

See [Phase 2 delivery status](phase2-status.md) for the current implementation, verification evidence, and remaining live/pilot gates. Phase 2 is not yet declared complete.

# Implementation status — September 30, 2026

The plan has been implemented as an initial public alpha build. Remaining first-release gaps are recorded below rather than advertised as completed.

| Milestone | Delivered and checked | Remaining work |
|---|---|---|
| 0 Setup/contracts | Java 25 / Spring Boot 4.1.1 CLI build (Java 21 analysis), policy with an explicit four-space Java convention, nine-rule catalog, strict finding/report schemas, business context | Owner review of curated rule wording/business scope; usable live Gemini authentication |
| 1 Collector | Immutable Git diff/blob reads, bounded JavaParser syntax index, policy hashes and reports | Semantic/classpath symbol solving and deterministic caching |
| 2 Agent loop | Official Gemini adapter, native function declarations with application dispatch disabled, bounded loop/retry/repair, offline end-to-end review | Live end-to-end provider validation blocked by HTTP 401 |
| 3 Risk/regressions | Five risks, distinct categories/confidence/status, pinned source/diff evidence checks, critical scope restrictions, proposed test specs | Human assessment of reasoning/test usefulness on live output |
| 4 Dependencies | Direct literal comparison, optional resolved transitive trees, source registry, official-source retrieval, exact OSV query boundary | Complete version-interval research and JAR API comparison; Jackson pinned-tag notes and OSV retrieval verified; broader remote coverage depends on source availability |
| 5 GitHub | Read-only ordinary CI verified on GitHub, default-branch maintainer workflow, separate same-run publisher, mocked stale/idempotency/injection checks, reusable action | Hosted agent-review and live comment verification |
| 6 Evaluation/resilience | 40 labeled cases (10 tuning, 30 held out), compiled Java pairs, three-mode authored replay; boundary/failure-path tests | Live held-out evaluation and repeated-run metrics; full imported static-analyzer baseline; human grading |
| 7 Release | Public repository, packaged alpha CLI, quickstart, example artifacts, architecture, security/contribution docs, MIT license, reusable action | Live-model quality evidence, hosted agent-review verification and a live-provider screencast before a stable release (offline terminal recording is included) |

The configured credentials did not authorize the Gemini smoke request. A key subsequently posted to chat was not copied into files or commands; rotate it and provide the replacement through the process environment. No model-quality detection percentages are claimed.

Public release can be a clearly labeled prerelease while these items remain open. Do not mark the original multiweek first-release learning plan fully complete based on replay fixtures.

Pre-migration local validation: 44 harness tests and 7 demo tests passed, including 3 real PostgreSQL integration tests. Both modules passed automated formatting and SpotBugs with zero high-priority findings. Official Jackson endpoint notes and OSV retrieval succeeded; excerpts were truncated explicitly.

Subsequent [Java 25 / Spring Boot 4.1.1 migration validation](spring-java25-migration.md#validation) passed 55 harness tests, formatting, SpotBugs and all 120 authored replay runs. The separate Java 21 / Spring Boot 3.4.4 demo retained its toolchain; its Docker integration tests were not rerun for that migration.

[Hosted CI](https://github.com/alpercandemir/undertow/actions/runs/36755090644) passed on commit `03ab8b25308bf210918a3a790cf5930da4a5387c`, running both Maven verifications and all 40 authored cases in three modes (120 replay runs). These replay results validate the harness, not live-model detection accuracy.
