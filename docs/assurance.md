# Human, agent, release, and operational assurance

These capabilities extend deterministic contract verification. Every authority-bearing source remains pinned by the host. Model output cannot change policy, run code, supply a trusted receipt, or enable a gate. The [README cookbook](../README.md#examples) and `scripts/assurance-showcase.py` provide a complete synthetic exercise. The latter uses real JUnit results with explicitly simulated external receipts; it is not a real customer pilot or production exercise.

## Local previews

`contracts preview` requires the same explicit trusted `--base` and optional separate `--policy-commit` as verification. It captures all tracked index paths by default, or the exact tracked files selected with repeated `--path`. `--staged` hashes the index; otherwise it hashes working files, records tracked deletions, and accepts untracked files only through repeated `--include-untracked`.

The result records scope, head/base identities, approved policy/oracle identity, per-file SHA-256/size, executable state for working files, index modes, and an aggregate candidate hash. It stores no source content. Captures are bounded to 1,000 files, 256 KB per text file, and 4 MB total. Sensitive files, symlinks/ancestor symlinks, submodules, unmerged paths, binary/invalid UTF-8 content, and unsafe paths are rejected. Standard environment example templates are permitted. Choose explicit text-file scope if your repository includes unsupported binaries.

`freshness --preview FILE` recaptures the same scope and exits `3` when it differs. Local edits, staged changes, file removal/addition within tracked scope, changed explicit untracked content, or a different policy/head invalidate the old identity. Unselected paths are outside an explicitly narrowed preview. Working-file capture checks consistency twice; files can still change after a successful freshness result. Neither a preview nor its freshness grants release authority, and final merge candidates still require CI evidence.

## MCP

Start the stdio server from an MCP client's process configuration:

```json
{
  "command": "java",
  "args": ["-jar", "/path/to/undertow/target/undertow.jar", "contracts", "mcp",
           "--repo", "/path/to/shop", "--base", "main", "--head", "feature",
           "--policy-commit", "main"]
}
```

Optional `--ci-bundle` and `--ci-receipt` are host startup arguments. `contracts_read` and `contracts_verify` take empty argument objects. Requests cannot change any ref, manifest, path, receipt, credentials, execution budget, or write destination. There are no shell, initialization, release, publication, or arbitrary HTTP tools.

The small transport supports JSON-RPC initialization, ping, tools/list, and tools/call over bounded newline-delimited stdio. It negotiates its supported protocol versions (2024-11-05, 2025-03-26, 2025-06-18); unsupported newer versions negotiate the latest supported version. It provides text plus structured results. A client must initialize before calls. Notifications produce no reply. The parser rejects messages over 64 KB. Cancellation notifications are not a separate execution capability; source reads retain the existing bounded Git timeouts. Cross-client compatibility needs verification in your selected MCP client; the packaged stdio handshake/read/verify flow is exercised by the showcase.

## Approved regression artifacts

A proposed test is not authoritative merely because a model generated it or CI ran it. An owner must review both its expected behavior and archive/source identity. Declare an artifact in trusted `.undertow/contracts.yaml`:

```yaml
regression_artifacts:
  - id: payment-regression
    sha256: REPLACE_WITH_REVIEWED_ARCHIVE_SHA256
# In a contract's tests array:
# - id: example.PaymentRegressionTest.retry
#   sources: [src/test/java/example/PaymentRegressionTest.java]
#   artifact: payment-regression
```

The digest placeholder must be replaced by 64 lowercase hexadecimal characters. Keep source files in the approved policy commit. Exact per-test `sources` remain hashed in the oracle identity. Artifact references must resolve to registered manifest entries. Without matching trusted external execution evidence, changes to the candidate's test sources remain incomplete. Only explicitly external test-source paths may use approved external execution in place of matching candidate bytes. Shared ordinary verifier/build sources remain frozen. A declared `oracle_root` intentionally freezes its complete inventory as well.

Prepare archives and an inert plan in a trusted control stage:

```sh
git -C /path/to/shop archive --format=tar HEAD > /tmp/application.tar
# Create /tmp/payment-oracle.tar from the exact reviewed test sources, preserving repository-relative names.
java -jar target/undertow.jar contracts regression-plan \
  --repo /path/to/shop --base BASE_SHA --head HEAD_SHA --policy-commit POLICY_SHA \
  --oracle-artifact payment-regression \
  --image maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 \
  --output /tmp/trusted-regression-plan.json
```

Use `--project-prefix demo/customer-service` for a supported Maven project below the Git root. The plan binds all application `src/main` files, the unchanged approved POM, exact approved test files, application head, contract policy, and image. The executor's application scope is deliberately limited to one Maven POM and `src/main`; candidate build hooks, test sources, and arbitrary root files are not copied into the executable project. Declare needed test resources as oracle sources. Unsupported build layouts require an adapter rather than silent scope expansion.

On a disposable CI machine, prepare the image and dependency repository through a controlled stage, then execute:

```sh
python3 scripts/run-approved-regression.py \
  --plan /tmp/trusted-regression-plan.json \
  --subject-archive /tmp/application.tar --oracle-archive /tmp/payment-oracle.tar \
  --dependency-cache /path/to/prepared/maven-repository --output /tmp/regression-output
```

The runner checks source hashes and exact oracle archive inventory before starting Docker. Links, traversal, duplicate entries, oversized archives, unknown files in the oracle, and unpinned images are rejected. It uses `--pull=never`, no network, read-only root/input/cache, an unprivileged user, dropped capabilities, no new privileges, one CPU, 1 GB memory, 128 PIDs, bounded tmpfs, and a 120-second timeout with container cleanup. Only the result directory is writable on the host. Provider, publisher, and production environment variables are not forwarded, and the Docker socket is never mounted. These are a concrete execution configuration, not universal attestation of every Docker host's isolation.

Collect `TEST-*.xml` and `oracle-execution.json` in the administrator-approved CI artifact. The trusted importer binds the external artifact digests and subject to the policy. Nonzero process exit alone never establishes a defect: missing reports, compiler/infra errors, skipped checks, and flaky/rerun reports cannot grant verified success or count as reproduced assertion failures.

`contracts reproduce` consumes a versioned file with `baseline` and `candidate` arrays of `{bundle, receipt}` paths. It requires 2–10 independent run identities per side and an exact `--contract`/`--test-id`. It recomputes every verification against the corresponding pinned subject and approved policy. Duplicate run identities, inconsistent repetitions, missing evidence, or errors produce `incomplete`. Conclusions distinguish reproduced regression, verified correction, existing violation, and no reproduced regression. Existing violations can independently fail required contracts without being newly introduced regressions.

## Release policies

`contracts release` recomputes verification directly from CI inputs and loads `.undertow/release.yaml` from `--policy-commit`. A supplied green JSON report cannot authorize release. Schema version 1 requires `enabled`, named `owner`, and a nonempty unique list of `required_contracts`. Default adoption examples use `enabled: false`; no command automatically edits that policy or enables hosting/merges.

```yaml
schema_version: 1
enabled: true
owner: payments release owners
required_contracts: [PAYMENT-RETRY]
exceptions:
  - id: incident-42-mitigation
    contract_id: PAYMENT-RETRY
    head_sha: REPLACE_WITH_EXACT_CANDIDATE_SHA
    owner: payments incident owner
    reason: Reviewed temporary mitigation with reconciliation underway
    expires: '2026-12-01T00:00:00Z'
```

Review and replace the SHA and expiry; do not treat the example as approval. Exceptions are loaded only from pinned trusted policy, must reference a required contract, have unique IDs, and apply only to the exact candidate until their expiry. They may waive an observed unsatisfied check, not missing/incomplete evidence. Raw verification remains unchanged. Expired/wrong-subject exceptions do not grant eligibility. Disabled policies return `not_evaluated`; enabled unmet requirements return `held` and exit `3`.

`contracts pilot --study FILE --output FILE` measures operator-supplied shadow cases containing a full verification result, contract ID, and separate named reviewer verdicts (`benign`/`violation`). Two distinct agreeing reviewers are required for an assessed case. Duplicate repository/head/contract cases are rejected; multiple contracts for the same change do not inflate independent-change counts. Missing/incomplete/unresolved observations remain in the result. Optional nonnegative `latency_ms` is aggregated; unknown cost remains null. Counts, false holds, escapes, and Wilson uncertainty are separate from model quality. Owner tolerances require meaningful positive/benign sample counts and cannot authorize a gate automatically. Mark fixture studies `dataset_kind: synthetic`; independently authorized shadow samples use their own explicit dataset kind and ownership process.

## Artifact provenance

A trusted build job runs:

```sh
python3 scripts/capture-build.py --artifact target/shop.jar --head HEAD_SHA --output target/build-provenance.json
```

Upload that record alongside its exact-module test results and retain the tested binary. The importer validates build records and their exact head before binding the archive origin. `contracts attest` recomputes release policy and verification, requires a candidate containing the pinned target tip, and hashes the supplied binary. It issues a record only when the enabled policy is eligible and the binary matches the trusted CI build digest. Rebuilding or substituting a different binary loses that relationship, even if the source looks equivalent.

The record includes artifact, candidate/target, contract policy, release policy, verification/release identity, and CI source receipt. The importer/runner boundary remains a trusted administrator-controlled assertion; these local records are not cryptographic signatures or a complete software supply-chain attestation standard.

## Operational feedback

`contracts rollout` takes the same source/CI/artifact arguments as `attest`, plus `--observations` and `--monitor-receipt`. It recomputes eligibility and artifact identity rather than trusting an edited saved attestation. Configure a separately pinned `.undertow/operations.yaml`:

```yaml
schema_version: 1
owner: payments operations
environment: staging
contract_id: PAYMENT-RETRY
minimum_events: 100
minimum_window_seconds: 60
maximum_age_seconds: 120
maximum_detection_seconds: 15
reconciliation_required: true
```

An existing deployment/monitoring adapter must export a JSON observation with `environment`, `contract_id`, `deployment_id`, exact `head_sha`/`artifact_sha256`, `started_at`, `ended_at`, integral `events`, and integral `violations`. The approved metric must meaningfully observe the contract: database row counts cannot establish absence of duplicate gateway charges. Operators own the metric semantics, relevant traffic, thresholds, freshness, and mitigation.

Keep a receipt from the trusted delivery/monitoring control stage outside candidate inputs. It has `verified_source: delivery_monitor_export`, `observation_sha256` over the exact UTF-8 observation bytes, and `attestation_sha256` over compact JSON serialization of the recomputed attestation. The receiver trusts that control stage's protected receipt, not caller-generated hashes or the source of an arbitrary local file. No generic local script self-authenticates a monitoring export. A production adapter must verify its actual exporter/environment identity before emitting this receipt.

| Situation | Decision |
|---|---|
| Wrong artifact/head/environment/contract; invalid, future, or stale window; bad/missing trusted receipt | `held` |
| Quiet canary or insufficient observation time | `held` |
| Adequate relevant traffic and no observed violations | `accepted` for the declared observation scope |
| Observed violation without timely confirmed mitigation | `halt_required` |
| Halt confirmed within the owner window, reconciliation still required | `contained` |
| Halt and required reconciliation confirmed with evidence | `recovered` |

For violations, include `first_violation_at` and, after the existing delivery system halts further effects, `halt_confirmed_at`. When reconciliation is required, include `reconciled_at` and an HTTPS `reconciliation_evidence` link from the incident workflow. Invalid/missing recovery evidence cannot claim recovery. Undertow executes no production action. A recovered incident is not a claim that the bad rollout was safe, nor permission to promote another artifact. Rollback alone cannot undo payments/messages/destructive data effects.

The synthetic showcase exercises every state above. Real canary, halt, key revocation, crash cleanup, backup/restore, and hosted isolation acceptance remain environment-specific exercises; the [service operational gates](phase3-status.md) are retained.
