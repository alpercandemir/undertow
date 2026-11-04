# Code quality review

Reviewed on 30 September 2026 against `CODING-SKILL.md` and `CONTRIBUTING.md`.
The harness has a useful separation between model calls, bounded tools, validation,
reporting and publication. The following gaps were corrected. This review does not
certify production readiness or live-model accuracy.

The validation below records the original Java 21 cleanup. The subsequent
user-requested Spring Boot 4 / Java 25 migration, including dependency changes and
new validation, is documented in [migration notes](spring-java25-migration.md).

## Findings and applied changes

**Finding:** Cancellation was handled as an ordinary integration failure.  
**Severity:** MAJOR.  
**Location:** `Reviewer` model/tool boundaries and `Undertow.Investigate`.  
**Why it matters:** An interrupted caller could continue into retries or further
investigation instead of stopping.  
**Recommended change:** Propagate interruption separately, preserve the interrupt
flag and cancel outstanding work. Applied.  
**Example:** Cancelling a waiting provider now throws `InterruptedException`, makes
one model request and produces no completed report. Policy sections 10, 16 and 45.

**Finding:** Retry attempts bypassed cumulative token-budget checks.  
**Severity:** MAJOR.  
**Location:** `Reviewer.ReviewRun.requestTurn`.  
**Why it matters:** Each failed request adds estimated input usage. Checking only
the outer loop allowed retries after the estimate had consumed the run budget.  
**Recommended change:** Check deadline, call and token limits before every attempt.
Applied.  
**Example:** A transient HTTP 503 under a small token budget stops before exhausting
all configured retries. Policy sections 24 and 30. Estimates remain distinct from
provider billing caps.

**Finding:** Record fields exposed mutable JSON.  
**Severity:** MAJOR.  
**Location:** `EvidenceStore.Evidence`, `ModelClient` records, `ReplayClient` and
`ToolRegistry` CI input.  
**Why it matters:** Copying a list alone does not protect the JSON inside it. A
caller could change stored evidence without updating its content hash, or alter a
future replay response.  
**Recommended change:** Copy JSON at construction and accessor boundaries; snapshot
incoming replay responses and CI data. Applied.  
**Example:** Mutating the original JSON, a tool response or an evidence accessor
leaves the stored value and its hash unchanged. Policy sections 5, 8 and 30.

**Finding:** A project-wide static-analysis exclusion hid ownership warnings.  
**Severity:** MINOR.  
**Location:** `config/spotbugs-exclude.xml`.  
**Why it matters:** The exclusion applied to every Undertow class, including future
code, while its comment assumed JSON trees were immutable by convention.  
**Recommended change:** Correct ownership and remove the exclusion. Applied; the
filter contains no suppressions.  
**Example:** New representation-exposure warnings are no longer excluded by class
name. The build still uses its existing High threshold. Policy section 32.

**Finding:** Several operations were difficult to follow despite passing Java formatting.  
**Severity:** MINOR.  
**Location:** `Reviewer`, `ReviewValidator`, Java/dependency analysis, `pom.xml` and
non-demo workflow/evaluation helpers.  
**Why it matters:** Formatting cannot explain a method that mixes collection,
retry accounting, tool dispatch, repair and reporting, or name the intent of a
boolean parameter.  
**Recommended change:** Use a private run object with focused methods, descriptive
names, explicit imports, named limits, separate version/version-pair validation
and a typed dependency identity. Expand the POM and helper scripts into readable
blocks. Applied.  
**Example:** The review entry point now collects, investigates and writes a report;
`checkVersionPair` and `checkVersion` express their distinct contracts. Evaluation
separates execution, scoring and aggregation, and uses `importlib.util` for loading.
Policy sections 3, 4, 14, 28, 33, 41, 45 and 47.

## Follow-up: conditional readability

The first pass missed nested ternaries in CLI model creation, dependency-tool
arguments and model-instruction selection. Those decisions now use named methods
with explicit branches or a switch. Report defaults and publication decisions were
also moved out of constructor/call arguments. CONTRIBUTING.md now states that
formatter compliance must be followed by a separate review of decision clarity.
Simple two-value selections remain where both choices are immediately clear.

Follow-up validation passed: all 50 Java tests, Spotless and SpotBugs at High,
plus CLI replay and collector checks. A JavaParser scan found zero nested conditional
expressions in production Java. Demo sources and fixtures remain unchanged.

## Four-space Java convention

Java sources under `src/` now use four-space block indentation through Spotless's
pinned google-java-format AOSP style. The Java-specific `.editorconfig` setting
matches it. CODING-SKILL.md section 33 states the convention and exempts existing
demo and labeled fixture sources, along with whitespace inside comments, strings
and text blocks. `JAVA-FORMAT-001` exposes the trusted convention to the model and
`get_rule`; introduced violations are LOW-risk STYLE findings with a MINOR label.
Spotless continues to enforce formatting in ordinary CI. Four-space validation
passed with all 51 Java tests, Spotless and SpotBugs at High, including trusted-base
policy delivery to the model. Demo and fixture sources have no changes.

## Approach and tradeoffs

The applied alternative is a small private run object that owns review accounting.
It keeps state transitions visible without introducing a generic workflow framework
or changing the public CLI, tool names, report schema or prompts. Broad catches
remain only at provider and tool integration boundaries, where diagnostics are
sanitized and incomplete coverage is recorded.

Mutable JSON copies provide enforceable ownership while preserving existing wire
formats. They add allocations; existing size limits bound payloads. Evidence hashes
and restore validation remain part of the publication contract.

`.editorconfig` records editor conventions. Spotless uses Google Java Format in AOSP style for four-space Java block
indentation and checks trailing whitespace and final newlines for the POM, XML configuration
and Python helpers, following the [official Spotless configuration guide](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#quickstart).
This is whitespace enforcement for Python/XML, not a full Python/XML formatter.
Workflow-helper behavior tests are included in ordinary CI.

The `demo/` directory and labeled `evals/` fixtures are unchanged. Demo preparation,
fixture generation and recording helpers retain their existing content. No dependency
or action versions were changed, and no live model or publication was invoked.

## Validation

- JDK 21: `mvn --offline spotless:apply verify` passed. All 50 Java tests passed;
  Spotless and SpotBugs at the High threshold reported no violations.
- `python3 -m unittest discover -s scripts/tests -v`: seven tests passed, including
  tampered content, manifest path traversal, symlinks, stale commits, cross-run
  provenance, repeat verification and evaluation scoring.
- `python3 -m compileall -q scripts`: all Python helpers compiled.
- Offline evaluation: all 40 cases completed in each of collect, diff and tools
  modes, producing 120 reports with no failed reports. Diff/tool replay retained
  every authored expected finding and produced no additional findings. Collector
  mode performed no semantic review. These results validate replay and harness
  behavior, not model accuracy. Results are in
  `.undertow/quality-review-20260930/evaluation.json`.
- `git diff --check` passed. `demo/` and `evals/` have no changes. The demo's
  Docker integration tests and live-provider requests were not run.

The initial baseline used the machine's default Maven JDK 27, which the pinned
SpotBugs engine could not analyze. Validation used the project's documented JDK 21.
Existing shaded dependency metadata-overlap warnings remain; they are separate
from source-analysis findings.

## Remaining limits

Semantic/classpath resolution, complete dependency migration coverage and live-model
quality remain the explicit limitations already described in the README. This pass
addresses implementation quality; it does not remove those capability limits.
SpotBugs retains the project's High threshold. A full Python formatter and stricter
structural Java rules are possible future tooling improvements; they should be
introduced with an agreed scope that excludes demonstration and fixture sources.
