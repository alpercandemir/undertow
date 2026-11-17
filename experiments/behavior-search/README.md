# Local behavior search experiment

This is an experimental execution core for owned Java fixtures. Start with the public [explore and adoption guide](../../docs/explore.md) for `undertow explore`. The first sections below document the original refund-cap slice from milestones 1–2. [Milestone 3](MILESTONE-3.md) adds retry idempotency, a second component, strategy freezing, and comparison with existing suites. The wrapper provides the adoption command without changing the frozen search core; adapter extension interfaces remain experimental.

## Run the first examples

Use Python 3.10+, Maven 3.9+, and an explicitly selected JDK 21. The runner and fixture target Java 21; neither depends on the Java 25 harness. A persistent small JVM executes scenarios, so Maven compilation happens once per subject per search run. The acceptance exercise reuses those classes for repeatability checks, then Maven executes the exported JUnit test on both subjects.

First populate Maven's cache for this reviewed fixture if necessary:

```sh
export JAVA_HOME="/path/to/jdk-21"
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test
```

Then, from the repository root, choose a new output directory:

```sh
python3 experiments/behavior-search/run_slice.py \
  --java-home "$JAVA_HOME" --output .undertow/behavior-examples
```

Open `.undertow/behavior-examples/examples.md`. The default seeds are 17, 29 and 101. Each runs against two separately snapshotted subjects and is repeated with fresh scenario state. The acceptance driver requires a real aggregate-cap counterexample on the defective subject, a completed bounded result on the corrected subject, and matching hashes of **all** repeated search observations. It also requires the same generated JUnit test to fail specifically on its cap assertion for the defect and pass for the correction. A build error, test error, skip, unrelated assertion failure, timeout or missing observation fails acceptance.

`run_slice.py` prepares the known guard mutation and checks labeled outcomes. `search.py`, `oracle.py`, and the Java execution code never read `cases.json`, the showcase script or its fixed probe, variant names, or expected-result labels. The generator receives a seeded random source and operation-count bound, not subject source, identity or observations. It samples capture/amount boundaries and its own accumulated expected balance. The reducer sees execution results and preserves the same failed expectation.

Generated artifacts are ignored under `.undertow/`. Keep them local; build logs and Surefire XML can contain workstation paths. No generated observations become trusted CI evidence or approved contracts automatically.

## Expectation and adapter

Read [scenario.json](scenario.json), [oracle.py](oracle.py), and [RefundAdapter.java](java/RefundAdapter.java) together as the reviewable definition. The initial expectation is the refund-cap obligation authorized for this experiment, with explicit acceptance/effect obligations to exclude vacuous implementations. Adopting a generated test as business policy still requires the component owner's review.

- Initial capture: integer minor units from 2 through 10,000.
- Each scenario contains 1–16 refund requests, each from −1 through 10,001; generated searches default to at most 12 requests.
- Every request gets a fresh nonblank operation key based on its step. Retry behavior is outside this slice.
- A request is valid when its amount is positive and no greater than the observed remaining capture. Valid requests must be accepted and add exactly their amount and one settled effect.
- Invalid requests must throw `IllegalArgumentException`, classified as a rejection, and leave refunded total and settled-effect count unchanged. Other exceptions, invalid/missing output and process exits are execution problems.
- After every step, the refunded total must remain between zero and capture. The first failed obligation is reported, with cap overflow checked before acceptance/effect obligations.

The adapter constructs the actual fixture `RefundService`, calls `refund`, and reads its public `refunded()` and `effects()` methods. Those methods read the fixture's actual counter and settled-operation map. The adapter never adds requested amounts or returns its own expected totals. The independent Python oracle computes expected behavior from the input and preceding observations. The exported test expresses the same obligations directly against `RefundService` and carries no Python/runner dependency.

Before searching, three fixed suitability scenarios verify positive partial acceptance, split refunds reaching the exact cap, a full refund reaching the cap, and rejection without effects before and after an accepted refund. Each request starts with a newly constructed service and empty map/counter; there is no static fixture state. Suitability failures produce `incomplete` and retain their observations instead of claiming a cap discovery.

## Execution protocol and runtime decision

The small [ScenarioRunner](java/ScenarioRunner.java) uses only the JDK and the subject. Its bounded transport now lives in the shared [ScenarioProtocol](java/ScenarioProtocol.java). For the original cap profile, standard input receives one tab-separated line containing `capture`, then refund amounts. Standard output returns `ok`, initial `refunded,effects`, then `accepted|rejected,refunded,effects` for every operation. A new adapter/service is created for every input line, including original replays, reducer trials, and reduced replays. Protocol requests and responses have hard limits. The Python host validates observation shape and completeness before invoking the oracle. Unexpected runtime exceptions return `execution_error` and a class name, never a behavioral rejection. A JVM error/crash or hang is a host execution problem.

Only the selected subject's Maven descriptor and `RefundService.java` are copied into a new execution directory, along with the reviewed runner/adapter. Existing fixture tests, labels and previous `target` directories are excluded. Offline Maven compiles that snapshot; an unsuccessful build prevents Java execution. The Java 21 runtime, source and compiled-class hashes are retained. This avoids a repository-wide Maven module change or dependence on the Java 25 CLI.

The subprocess environment keeps only basic runtime/path/locale variables and the selected Java home. Provider/publisher variables, Maven/Java option variables and injected agent options are excluded. Default bounds: 100 generated scenarios, 30 seconds for scenario execution/reduction/replay together, 2 seconds per response, 120 seconds for compilation, 200 reducer trials and 5 seconds for reduction. Combined build/test command output is capped at 1 MiB; protocol responses at 4 KiB; Java heap at 64 MiB. Maven execution of an exported test has a separate 120-second limit. Attempted/completed scenario counts are recorded per phase, with requested operations distinguished from fully observed operations.

The reduction time limit stops scheduling trials; an in-flight trial can use up to one additional per-scenario timeout, within the overall execution deadline. These controls and an offline build are **not a sandbox**. Maven and Java execute code as the host user. This experiment supports only explicitly selected, owned, reviewed fixture copies; it does not accept untrusted repositories or claim filesystem/network isolation. External candidate isolation is a later acceptance gate.

## Outcomes, reduction and replay

Reports use `counterexample_found`, `no_counterexample_within_budget`, and `incomplete`. A found result requires an identical fresh-state original replay. A later execution problem preserves that verified original finding and separately reports unfinished work. A completed search with no finding means only that no violation was observed in its recorded scope. Search terminates at the first violation or the scenario budget; the termination reason is explicit.

Reduction greedily removes operations, shrinks capture together with amounts, then simplifies individual values. Every trial remains in the approved domain and must violate the original expectation. The reducer has a trial/time budget and reports whether it reached its local fixed point. “Reduced” does not mean globally minimal. Both the original and reduced cases are replayed in fresh state; the reduced case must reproduce twice. The normal full path emits:

- `report.md` and `report.json`: expectation, initial conditions, original/reduced traces, first violating step, expected/observed state, budgets, actual counts, runtime versions and execution problems.
- `original-replay.json` and `replay.json`: the verified original and reduced inputs with immutable subject/oracle/adapter identities. If reduction encounters an execution problem, `replay.json` and the draft test retain the verified original.
- `GeneratedRefundBehaviorTest.java`: a standalone draft whose assertions enforce the expectation.
- `subject/`: snapshotted inputs and compiled classes; build/runtime logs stay alongside the report.
- The acceptance driver additionally emits `acceptance.json`, `examples.md`, and per-subject `junit.json`, `junit.log`, and Surefire XML for the same exported test.

To run a search directly, explicitly select an owned reviewed refund fixture:

```sh
python3 experiments/behavior-search/search.py \
  --subject demo/refund-extraction --java-home "$JAVA_HOME" \
  --seed 17 --scenarios 100 --output .undertow/single-search
```

To replay a discovered case, use its exact subject snapshot and a new output directory:

```sh
python3 experiments/behavior-search/search.py \
  --subject .undertow/behavior-examples/subject-a-seed-17/subject \
  --java-home "$JAVA_HOME" \
  --replay .undertow/behavior-examples/subject-a-seed-17/replay.json \
  --output .undertow/replay-example
```

Replay refuses changed subject, adapter or oracle identities. It does not silently replay against a different correction. The acceptance driver deliberately runs the standalone exported test against the correction as a separate check. Record the Python version as well as seed/generator version; repeatability is established on the recorded runtime, not promised across all future random-library implementations.

## Verification and review boundary

```sh
python3 -m unittest discover -s scripts/tests -p test_behavior_search.py -v
python3 -m unittest discover -s scripts/tests -v
```

The boundary tests compile and execute actual Java for isolation, non-vacuity, discovery, unexpected exceptions and timeouts. They require a Java 21+ toolchain. The acceptance command above separately exercises Maven, reduction/replay, and exported JUnit on both implementations.

Milestone 3 is documented separately, including its internal-validation limits and remaining independent review. The later public wrapper and primary README now provide the local adoption path; arbitrary adapter packaging and a new application UI remain deferred. Passing the original owned-fixture checks establishes the cap slice, not product validation or new failures beyond existing CI: the existing refund suite already contains cap checks.
