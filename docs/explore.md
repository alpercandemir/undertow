# Find, replay and adopt a behavior regression

For engineer review, start with the [Actions summary](ci-behavior-review.md). The commands below are optional implementation-agent and maintainer tools.

Use `undertow explore` to search an owned, reviewed Java component for a violation of an explicit expectation. The local path is: select the reviewed adapter, execute a bounded search, inspect a reduced failure, replay its snapshot, and explicitly adopt a JUnit draft.

The source checkout launcher is `./scripts/undertow explore`. The packaged equivalent is `java -jar target/undertow.jar explore`. Both use the same bundled/source runner and profiles; the JAR additionally needs JDK 25 to launch. A checkout can run `explore` with Python 3.10+, Maven 3.9+ and a component JDK 21+ on Linux/macOS, without building the harness. No model key is needed.

## First example

Run from the repository root. Select a real local JDK path and prepare the owned fixture's Maven cache:

```sh
export JAVA_HOME="/path/to/jdk-21"
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test

./scripts/undertow explore --example refund-retry \
  --java-home "$JAVA_HOME" --output .undertow/first-example
```

Exit **1** means a counterexample reproduced. Open `index.html` or `report.md` in the output directory. This example intentionally introduces a partial-refund retry defect into an isolated source copy. Search discovers the input; it does not read a fixed answer. The command also runs the exported test against the candidate and correction, retaining its relevant assertion failure and corrected pass. The source fixture stays unchanged.

Other examples are `--example refund-cap` and `--example ticket-transfer`. Supply a new output directory each time. If a dependency is unavailable offline, read `build.log` and prepare the reviewed fixture's dependencies through your normal build setup. A dependency problem is an incomplete run.

## Select a component and expectation

```sh
./scripts/undertow explore --subject /path/to/owned-component \
  --behavior refund-retry --java-home "$JAVA_HOME" \
  --seed 17 --scenarios 100 --max-length 12 --runtime 30 \
  --output .undertow/component-search
```

The current adapter contracts are intentionally narrow:

| Profile | Required Java interface | Reviewable definition |
|---|---|---|
| `refund-cap` | `example.refunds.RefundService(long)`, `refund(String,long)`, `refunded()`, `effects()` | [Cap expectation](../experiments/behavior-search/scenario.json) and [oracle](../experiments/behavior-search/oracle.py) |
| `refund-retry` | The same refund API; settled keys persist across calls within one scenario | [Retry expectation](../experiments/behavior-search/retry-scenario.json) and [oracle](../experiments/behavior-search/retry_behavior.py) |
| `ticket-transfer` | `example.tickets.TicketTransfer(String,String)`, `preview()`, `accept(String)`, `owner()`, `accepted()` | [Ownership expectation](../experiments/behavior-search/ticket-scenario.json) and [oracle](../experiments/behavior-search/ticket_behavior.py) |

The selected Maven descriptor and implementation file are copied into a fresh execution project with the reviewed adapter. Existing tests and `target` directories do not enter the search. The descriptor must support that isolated component; these profiles do not collect arbitrary source dependencies or Maven modules. A new component requires authoring/reviewing its adapter and expectation, then validating it separately. The two current profiles' raw observations come directly from actual Java state, not an adapter's computed expected state.

Review monetary bounds, key/rejection rules and state observations in the selected definition. Search uses sequential in-memory state and resets between scenarios. It does not infer business expectations, model distributed effects or run arbitrary repositories safely. Maven and Java execute as your host user. Use only owned, reviewed code; offline builds and deadlines do not provide a security sandbox.

## Interpret the output

| Artifact | Use |
|---|---|
| `index.html`, `report.md` | Failed expectation, reduced steps, first violation, expected/observed state, scope and problems |
| `report.json`, `execution.md` | Full search observations, original/reduced replay checks, identities, counts, budgets and raw report |
| `replay.json`, `original-replay.json` | Exact reduced/original input, expectation and subject/oracle/adapter identities |
| `Generated…Test.java` | Standalone JUnit draft derived from the expectation |
| `contract-draft.json` | Proposed test binding and assumptions, explicitly unreviewed; not an approved contract manifest |
| `review.md` | Empty independent review record; no review or timing is inferred |
| `explore.json` | Local packaging metadata and any extra example-execution problems |
| `subject/` | Exact snapshotted component inputs and compiled classes |

`counterexample_found` exits **1** when required work completes. `no_counterexample_within_budget` exits **0**, establishing only the observed bounded result. Execution/setup/usage problems exit **2**. A later problem may retain a verified original finding and report unfinished work separately; inspect the report rather than treating exit 2 as a new discovery. Successful explicit adoption exits **0**.

Default limits are 100 generated scenarios, 12 operations, 30 seconds for search/reduction/replay, 2 seconds per scenario response, and 120 seconds per Maven build/test command. Reduction has its own 200-trial/5-second scheduling budget. Full bounds and actual counts are retained in JSON. Reduction is not a global-minimality guarantee. The [experimental guide](../experiments/behavior-search/README.md) documents execution details.

## Replay the exact failure

```sh
./scripts/undertow explore --replay .undertow/first-example/replay.json \
  --java-home "$JAVA_HOME" --output .undertow/replayed-example
```

This infers the profile and uses the adjacent `subject/` snapshot. If you moved the snapshot separately, add `--subject /path/to/exact-snapshot`. Replay refuses mismatched subject, adapter or oracle identities and requires the declared expectation to reproduce. Use the recorded tool version for older artifacts. To evaluate a changed implementation, perform a new search or run the reviewed generated regression against it.

## Adopt a reviewed draft

Read the Java test, expectation, assumptions and reduced sequence. Then explicitly choose the owned component receiving it:

```sh
./scripts/undertow explore --adopt .undertow/first-example \
  --subject /path/to/owned-component --output .undertow/adoption
mvn --batch-mode --no-transfer-progress -f /path/to/owned-component/pom.xml test
```

Adoption verifies the replay/profile identities, regenerates the expected draft from the scenario, checks its source/hash, refuses symlink traversal and an existing destination test, and copies one Java test into the selected profile's test package. It permits the receiving implementation to differ: the intended use is to keep the regression after fixing it. Adoption does not run tests automatically or approve business policy. `adoption.json` records the copied draft and leaves owner approval unrecorded.

An edited test should follow your normal source-review process; this draft copier accepts only the unchanged generated source. Avoid running adoption against an example's `subject/` or `correction/subject/` after the paired test exercise: those snapshots already contain the temporary generated test. Use your owned component or a fresh copy instead.

## Approve a contract separately

An adopted test can remain a normal JUnit regression. If its owner also wants it in Undertow's standing obligations, review `contract-draft.json` and use the existing contract workflow. For example, with the harness built and JDK 25 selected for `UNDERTOW_JAVA_HOME`:

```sh
./scripts/undertow contracts init \
  --repo /path/to/owned-component --repository example/shop --owner 'payments team' \
  --id REFUND-RETRY --expectation 'A settled refund retry adds no effects' \
  --scope src/main/java/example/refunds/RefundService.java \
  --test-id example.refunds.GeneratedRefundRetryTest.settledRefundRetriesHaveNoAdditionalEffects \
  --test-source src/test/java/example/refunds/GeneratedRefundRetryTest.java \
  --oracle pom.xml
```

Use your actual repository identity, owner and complete execution-control inventory. `init` creates a draft manifest without overwriting existing policy; existing manifests need normal reviewed edits. Commit approved policy through the trusted target branch. Later CI execution and the [trusted importer/verifier](contracts.md) establish the existing evidence path. Search results, test adoption and local receipts grant no CI provenance and do not change `satisfied` semantics or existing contract schemas.

## Independent adoption review

The primary product path is now [GitHub Actions review](ci-behavior-review.md). CI performs setup, baseline testing, search, replay and generated-test execution. The authored calibration additionally verifies disposable adoption against a correction. No unfamiliar-engineer terminal exercise is required.

Use the [review criteria](independent-review.md) and [decision record](templates/independent-review-record.md) to record the owner's rule assessment, finding/test decision and concrete value. This CLI guide remains for implementation agents and maintainers diagnosing a run. Automated success does not imply independent validation, owner approval or measured time savings.
