# Contract verification

Undertow can verify declared contracts without a model, API key, repository build, or old review configuration. The default verifier runs in shadow mode and produces `contracts.json` and PR-ready `contracts.md`. Separate [assurance commands](assurance.md) implement local previews, MCP, reproduction, optional governed release checks, artifact attestations, and monitoring-export evaluation. Verification itself does not publish or authorize release. Existing AI `report.json` versions and publication behavior remain compatible.

Contract verification, AI review execution, and release decisions are separate. A satisfied contract establishes only that the declared approved checks passed under the recorded assumptions. It does not certify production harmlessness.

## Start with one contract

Create `.undertow/contracts.yaml`, adapt this example, and approve/commit it on the trusted target branch before assessing a candidate. The repository identity must match the GitHub repository imported by CI.

```yaml
schema_version: 1
repository: example/shop
oracle_paths:
  - pom.xml
  - .github/workflows/ci.yml
contracts:
  - id: PAYMENT-RETRY
    version: '1'
    owner: payments team
    expectation: An ambiguous retry preserves the logical payment key.
    assumptions:
      - The gateway deduplicates that key throughout the retry window.
    scope: [src/main/java/example/PaymentGatewayAdapter.java]
    verifier: junit
    tests:
      - id: example.PaymentTest.retryPreservesKey
        sources: [src/test/java/example/PaymentTest.java]
```

Owners approve the expectation, exact JUnit identity, source binding, and complete oracle inventory. Include test helpers, fixtures, build settings, runner scripts, and workflow definitions that can change what the verifier executes or asserts. Source paths are exact repository-relative regular Git blobs. Optional `oracle_roots` capture complete directory inventories, including approved absence. Maven `.mvn` controls at the repository and declared project roots are always inventoried; adding new controls requires review. Globs, shell commands, credentials, executor registration, and budgets are unsupported. The only registered verifier is `junit`. Empty contracts/tests, duplicate IDs, unknown fields/verifiers, malformed scopes, missing sources, and unsafe paths are rejected. Schema version 1 is bundled in the JAR as `schemas/contracts.json`.

All declared contracts are checked on every candidate. `scope` describes the business implementation; it cannot suppress a mandatory test based on inferred change impact. Verification has no impact skipping or optional checks. Separately pinned release policy can select required contracts and exact-subject expiring exceptions; incomplete evidence always holds release. See [release policies](assurance.md#release-policies). Existing violations can therefore be unsatisfied even when both revisions contain the defect.

Read approved intent before coding:

```sh
java -jar target/undertow.jar contracts validate --repo /path/to/shop --base main
java -jar target/undertow.jar contracts list --repo /path/to/shop --base main
java -jar target/undertow.jar contracts validate \
  --repo /path/to/shop --base main --candidate feature
```

Candidate validation does not approve the candidate or replace current policy. `--base` is required to avoid silently trusting feature `HEAD`. The operator must choose a genuinely trusted commit; Undertow cannot infer organizational approval from a branch name. `--policy-commit` can separate that authority from the comparison baseline; `--target-head` records the target tip. Recheck current target and merge-candidate identities in trusted CI before any delivery decision.

## Import independently produced evidence

Use a trusted harness/importer checkout and a separately pinned application repository. Keep the exported policy identity and importer receipt outside candidate-controlled paths and artifacts. Export from the same approved commit used by verification:

```sh
java -jar target/undertow.jar contracts export \
  --repo /path/to/shop --base BASE_SHA --head HEAD_SHA \
  --policy-commit APPROVED_POLICY_SHA --output /tmp/approved-contract-policy.json

python3 scripts/import-ci.py \
  --repository example/shop --run-id RUN_ID --workflow-id APPROVED_WORKFLOW_ID \
  --base BASE_SHA --head HEAD_SHA \
  --contract-policy /tmp/approved-contract-policy.json \
  --output /tmp/ci-bundle.json --receipt /tmp/trusted-ci-receipt.json

java -jar target/undertow.jar verify \
  --repo /path/to/shop --base BASE_SHA --head HEAD_SHA \
  --policy-commit APPROVED_POLICY_SHA --target-head TARGET_SHA \
  --ci-bundle /tmp/ci-bundle.json --ci-receipt /tmp/trusted-ci-receipt.json \
  --output .undertow/shop-verification
```

Replace uppercase identities with full immutable commits and numeric administrator-approved run/workflow IDs. Import makes explicitly requested GitHub API requests using the existing `GITHUB_TOKEN` integration. Verification itself performs no network requests or model calls. Do not use the mixed `deterministic-evidence` artifact: select `single-module-evidence` for Undertow's own contracts, or `--artifact-name order-contract-evidence` for the bundled order/payment contracts. A single import supports one selected manifest and test inventory.

The trusted importer validates the completed workflow/run/head/artifact origin and archive digest. It binds normalized results and run conclusion to the approved policy commit, manifest path/content hash, and sorted oracle-source digest. `CiEvidence` checks the matching receipt and bundle bytes, then the evaluator checks the exact policy binding. Legacy bundles remain usable by AI review but cannot satisfy contracts without the new binding.

The receipt is a trusted local-runner assertion, not a digital signature. A receipt or exported binding supplied by a candidate is not an authority. An administrator must review the workflow, its immutable dependencies, artifact creation, test execution, and clean-run behavior. Pin all files that influence oracle behavior in `oracle_paths`/`sources`. GitHub artifact origin alone cannot prove that a malicious or incorrectly configured workflow executed the intended oracle. This increment does not provision isolation or attest the runner environment.

## Interpret results

| Required execution | Contract check |
|---|---|
| Exactly one approved head-side test reports `passed` | `satisfied` |
| Exactly one approved head-side test reports an assertion `failed` | `unsatisfied` |
| Test absent, unrelated, skipped, errored, duplicated, conflicting, or recorded with flaky retries | `incomplete` |
| Empty suite; missing, stale, tampered, wrong-subject, or receiptless evidence | `incomplete` |
| Different policy/oracle identity; changed/deleted/renamed oracle; candidate manifest weakening | `incomplete`, with an ownership/remapping action |
| Cancelled, timed-out, or unsupported run conclusion | `incomplete` |

An error, compilation failure, or infrastructure failure is not a reproduced contract violation. Clean execution and retention are responsibilities of the approved CI workflow. Aggregate verification is `unsatisfied` if any valid required assertion fails, otherwise `incomplete` if any required check lacks valid evidence, otherwise `satisfied`. Individual checks preserve gaps even when an assertion failure determines the aggregate. Both successful and failed completed workflows can contain meaningful individual executions.

No evidence means explicit incomplete output. In shadow mode exit `0` means reports were produced, including incomplete/unsatisfied outcomes. Setup/schema errors exit nonzero. Consumers must inspect `verification`, `contracts[].checks[]`, and `evidence_gaps`. `mode` is always `shadow` and `release_decision` is always `not_evaluated`. The versioned result schema is bundled as `schemas/contract-verification.json`.

If an oracle or manifest changes, review it and approve a new pinned policy before re-importing/re-verifying. The tool detects a structural change; it does not assert that an equivalent rename weakens behavior. Working-tree edits are excluded because this interface reads committed Git objects. The separate `contracts preview`/`freshness` commands capture working-tree or staged identities without CI/release authority. See [local preview examples](assurance.md#local-previews).

## Bundled pilot and regression evidence

The initial internal pilot is the order/payment demo. Its [manifest](../demo/order-service/.undertow/contracts.yaml) separates gateway effects from order/event database state. Use `--manifest demo/order-service/.undertow/contracts.yaml` consistently for validate/list/export/verify. A payment unit-test pass cannot satisfy the PostgreSQL order-state contract. The latter requires the three exact `OrderIntegrityIT` executions; a machine without Docker cannot manufacture that evidence.

The [dogfood manifest](../.undertow/contracts.yaml) binds Undertow's own policy, evidence/publication, credential-permission separation, and no-paid-fallback boundaries to existing individual tests. These declarations still need repository-owner review before operational adoption. Workflow permission tests are static checks, not proof of deployed isolation.

Run approved-verifier sensitivity separately from harness regression and live AI evaluation:

```sh
# JDK 21, after preparing the bundled demo's Maven dependency cache
python3 scripts/payment-mutation.py --output .undertow/payment-mutation
```

This developer/CI runner executes only the bundled reviewed mutation fixture. It creates four independent copies, runs the unchanged payment oracle twice on the correction and twice with a changed retry key, and requires the exact relevant assertion to fail for the mutant. Errors/missing reports cannot count as detection. `mutation.json` records oracle and subject hashes and explicitly disclaims trusted import and live-model evaluation. CI retains that result separately. Offline Maven and stripped credential variables reduce accidental access; they do not constitute a sandbox. Execute CI builds in a disposable, externally constrained environment without model, publisher, or production credentials.

Migration is additive: existing `config/` CLI policies and hosted `.undertow/rules.yaml` packages continue working. The contract command needs only the new manifest and its references. It does not synthesize business intent from old contextual rules, silently bundle organization-specific contracts, or change hosted execution settings. Formatter/build checks handle mechanical style; the coding guide remains readable while style rules are excluded from model assessment.

## Additional implemented stages

The [advanced assurance guide](assurance.md) covers external approved regression artifacts, independent repeated reproductions, negative tenant checks, SARIF, pilot measurements, exact-subject exceptions, tested artifact provenance, and operational halt/reconciliation decisions. Legacy manifests remain valid; external oracle references and full oracle-root inventories are additive fields in schema version 1. Any changed binding requires fresh trusted import.
