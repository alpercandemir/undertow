# Contract tools for implementation agents

Engineers review the Actions report and decide. Implementation agents can read approved intent, inspect verification results, prepare accepted source changes and check whether a local snapshot is still current. These tools require no model-specific integration.

## Read and verify approved intent

The CLI supplies structured JSON through `contracts list` and verification artifacts through `verify`. Use a trusted policy commit selected by the owner; branch names do not create authority. See [contract verification](contracts.md) for the evidence and receipt requirements.

The read-only MCP server exposes two tools:

| Tool | Result |
|---|---|
| `contracts_read` | Host-pinned policy binding and manifest |
| `contracts_verify` | Verification using the host-fixed candidate, policy, bundle and receipt |

Example host launch configuration:

```sh
java -jar target/undertow.jar contracts mcp \
  --repo /path/to/owned-component --base APPROVED_POLICY_SHA --head CANDIDATE_SHA \
  --ci-bundle /path/to/ci.json --ci-receipt /path/to/trusted-receipt.json
```

The host supplies these paths and identities. MCP requests cannot replace them, provide credentials, execute commands, write source or publish messages. A read-only result is not approval to adopt a test or change business policy.

## Local previews and freshness

`contracts preview` captures an immutable identity for tracked working-tree or staged files. Explicit untracked files require deliberate inclusion; secret paths and symlink traversal are rejected. `contracts freshness` returns exit 3 if captured content, scope, HEAD or policy changes.

```sh
java -jar target/undertow.jar contracts preview \
  --repo /path/to/owned-component --base APPROVED_POLICY_SHA \
  --staged --output /tmp/undertow-preview.json

java -jar target/undertow.jar contracts freshness \
  --repo /path/to/owned-component --base APPROVED_POLICY_SHA \
  --preview /tmp/undertow-preview.json
```

Local previews record identity; they do not grant CI provenance. An implementation agent must refresh stale evidence before using it to explain a candidate.

## Contract initialization and regression evidence

`contracts init` creates one explicit contract draft from an owner, expectation, scope, exact test identity and source/oracle paths. It refuses an existing manifest. The resulting policy still needs owner review and a trusted commit.

`contracts regression-plan` prepares an approved external-test artifact plan for the separate `scripts/run-approved-regression.py` runner. That runner requires a reviewed digest-pinned container image and external execution configuration; it does not run through MCP. `contracts reproduce` compares repeated trusted executions and requires relevant, consistent assertion failures. Runtime errors or differing results cannot establish reproduction.

`verify --sarif FILE` optionally exports the same verification evidence as SARIF 2.1.0. SARIF preserves source/commit identity and does not invent line numbers.

## Applying an accepted test

The behavior pipeline produces a patch when a new generated test can be proposed. After a reviewer accepts the rule and test, the implementation agent applies it through the ordinary change PR and runs the component suite. Existing generated-test paths must be reconciled through source review rather than overwritten. The pipeline does not automatically create commits, repair code or merge changes.
