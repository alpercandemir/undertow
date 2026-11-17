# Milestone 3: retry behavior and a second component

Historical experiment record. The manual onboarding criteria below were superseded by the [current CI-first review criteria](../../docs/independent-review.md): CI executes search, replay and regression checks; engineers review the resulting evidence and decide. The recorded measurements and frozen strategy remain unchanged.


This records the milestone 3 experiment using the same bounded process host, reset protocol, oracle boundary, reducer loop, replay artifacts and JUnit execution. The later [public explore wrapper](../../docs/explore.md) packages that unchanged core. This experiment remains limited to the two owned fixtures selected explicitly below; it adds no arbitrary-repository runner, hosted features or new application UI.

## Run the validation

With the Java 21 home selected and fixture dependencies cached as described in the [main guide](README.md):

```sh
python3 experiments/behavior-search/run_generalization.py \
  --java-home "$JAVA_HOME" --output .undertow/behavior-milestone-3
```

Choose a new output directory. Open its `examples.md`; `validation.json` retains candidate identities, exact existing-test outcomes, search counts, reduced cases, repeatability counts, paired JUnit observations, exclusions, runtime and unmeasured review fields. Every discovered candidate's first-seed draft is run against both that candidate and the pristine correction. Other seeds independently search, reduce and replay. The original cap acceptance remains executable through `run_slice.py`.

The default validation has five authored defects, three controls, and three excluded inputs: an uncompilable candidate, a reject-all candidate that fails suitability, and a concurrency request outside the sequential scope. The concurrency row is an excluded request, not a claim that a real concurrent mutant was executed. Detection counts include only executable, suitable, in-scope defects. No seeded mutation result is presented as general detection accuracy.

Existing tests are copied from the fixture into separate fresh Maven projects and executed without adding the generated draft. The expected test identities come from a pristine execution. Assertions, skips/errors, missing executions and Maven status are checked separately with the existing JUnit parser. An error in an existing test makes that comparison incomplete; it is not counted as an assertion-level detection. The generated search may still establish a behavior failure through its independently approved expectation and paired assertion export. Build logs and full Surefire XML remain local.

## Expectations and actual observations

For refunds, [retry-scenario.json](retry-scenario.json) and [retry_behavior.py](retry_behavior.py) define:

- A new valid refund settles once.
- Repeating a settled key with the same amount succeeds with no further amount or settled effect, even when capture is exhausted.
- Reusing that key with another amount rejects without changing state.
- Invalid fresh requests do not reserve their keys or change state.

The existing refund adapter now accepts explicit bounded keys. It reads the actual refunded counter and settled-map size. Suitability checks contain no retries, so retry findings come from generated sequences rather than a fixed retry probe. Unexpected runtime failures remain execution problems; declared `IllegalArgumentException` business rejections are compared with the acceptance expectation.

For the second component, the owned [ticket-transfer fixture](../../demo/ticket-transfer/README.md) was selected after the strategy freeze. [ticket-scenario.json](ticket-scenario.json), [ticket_behavior.py](ticket_behavior.py) and [TicketAdapter.java](java/TicketAdapter.java) define:

- Preview never changes owner or invitation state, before or after settlement.
- Only the recipient can accept; other actors reject without effects, including after settlement.
- Initial acceptance by the recipient succeeds and transfers ownership.
- A duplicate recipient acceptance may return successfully or reject, while preserving settled state. The original implementation's duplicate return semantics are deliberately not adopted as a business requirement.

The adapter reads `TicketTransfer.owner()` and `accepted()` directly. Wire owner codes are 0 sender, 1 recipient, 2 other and 3 any unrecognized owner; invitation state is 0/1. This is an encoding of observed state, not an expected-owner ledger. Security rejections are classified only for nonrecipients, and already-settled rejections only after an observed accepted invitation. Unexpected exceptions, crashes, missing output and timeouts remain execution problems.

Each profile supplies a grammar, expectation, small Java adapter/entry point and standalone JUnit assertions. Ticket execution uses the same `ScenarioProtocol`, Python `Runner`, bounded search, reduction loop and replay logic. No ticket implementation is embedded in the generator.

## Frozen strategy and validation provenance

[strategy-lock.json](strategy-lock.json) records SHA-256 hashes of the shared generator/reduction candidates, Python execution/search core, Java transport and execution interface. It fixes seeds 211, 307 and 401, 100 scenarios per run and at most 12 operations. The lock is checked before candidate preparation, between candidates and at completion. A changed strategy prevents retaining the original freeze claim.

[sequence_strategy.py](sequence_strategy.py) samples only the declared initial range, action names, bounded fields and enumerations. It biases integers toward domain/initial boundaries, reuses prior field values, and sometimes repeats an earlier operation exactly. It does not inspect subject code, labels, expected results or test metadata. The reducer removes operations and simplifies individual/equal fields, preserving repeated-call relations when useful. The execution oracle, not the generator, decides whether the same expectation still fails.

After the lock, the host validation driver authored mutations and selected the second adapter. New profile grammars provide component-specific inputs; the frozen sampling/reduction algorithm and execution core remain unchanged. All fixed-seed search observations are hashed and repeated in a fresh JVM. Reduced cases also replay on that fresh JVM, in addition to the original/reduced fresh-state checks inside the search.

These are **internal, self-authored validation cases**. Repository fixtures were already visible during initial inspection. No independent engineer authored the defects, selected a hidden component, reviewed these expectations or measured understanding time. The lock establishes ordering and guards against later tuning; it does not create blind-evaluation provenance or trusted CI evidence. If a future strategy bug requires changes, invalidate this lock and separate later cases rather than silently refreshing hashes and retaining held-out claims.

## Replay and interpretation

Use the matching explicit profile and exact subject snapshot:

```sh
python3 experiments/behavior-search/search.py --behavior refund-retry \
  --subject .undertow/behavior-milestone-3/refund-partial-retry-charged/seed-211/subject \
  --java-home "$JAVA_HOME" \
  --replay .undertow/behavior-milestone-3/refund-partial-retry-charged/seed-211/replay.json \
  --output .undertow/refund-retry-replay

python3 experiments/behavior-search/search.py --behavior ticket-transfer \
  --subject .undertow/behavior-milestone-3/ticket-preview-after-settlement/seed-211/subject \
  --java-home "$JAVA_HOME" \
  --replay .undertow/behavior-milestone-3/ticket-preview-after-settlement/seed-211/replay.json \
  --output .undertow/ticket-preview-replay
```

Replay checks profile, subject, adapter and oracle identity and requires the declared expectation to reproduce. Original artifacts from older adapter versions deliberately fail identity checks against changed code; regenerate them with their recorded code version rather than silently overriding the check.

Compare `additional_failure`, `restates_existing_failure`, `existing_suite_incomplete`, and `missed_within_budget` separately. A shorter sequence is an observation, not proof of a clearer explanation. Adapter footprint is recorded in lines per file; active engineering time and independent time to understand are left unmeasured. Reduced tests remain drafts for owner review.

Before declaring milestones 4–5 accepted, have an independent engineer review the expectation/adapter, reproduce a case unaided, and record setup help, time to understand, and whether the reproduction saves effort. For stronger detection evidence, have that engineer prepare additional defects or a component without tuning the frozen strategy to those inputs. This external validation remains open. The later local wrapper/documentation work is implemented provisionally; local success alone does not establish independent adoption or product-value acceptance.
