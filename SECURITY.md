# Security and execution boundaries

## Behavior execution

The behavior runner executes explicitly selected, owned Java/Maven components using reviewed adapters. The supplied workflows use ephemeral GitHub-hosted runners, read-only repository permissions, pinned actions and no model/provider secrets. Candidate execution does not run under `pull_request_target`.

Java and Maven still execute component and build code as the runner user. Time, memory and output limits plus offline search builds are resource controls, not a general-purpose untrusted-code sandbox. Integrate only components appropriate for that execution environment. A new repository or component needs reviewed build inputs and adapter scope.

Generated tests are drafts. Adoption refuses an existing destination and verifies the recorded source/expectation identities; it does not approve policy. Replay requires the recorded source and tool identities. Incomplete execution must remain visible even when an earlier finding was reproduced.

## Contract authority and evidence

Contract verification reads immutable Git objects and requires a separately pinned approved policy. Candidate changes cannot grant themselves authority. Trusted CI import validates repository, workflow, run, commit and artifact identities and binds normalized results to approved test/oracle identities.

Keep importer receipts and exported approved bindings outside candidate-controlled files. A receipt is a trusted-runner assertion, not a cryptographic signature or an independently authenticated proof of test quality. Origin checks do not replace workflow review. Missing, stale, tampered, skipped, errored or mismatched evidence cannot satisfy a contract.

The verifier performs no repository build or model call. Read-only MCP exposes only `contracts_read` and `contracts_verify` with host-fixed inputs. It does not execute commands, modify source or publish messages. The CLI ignores working-directory Spring configuration.

## Credentials and source publication

The active product needs no model credential. GitHub import uses `GITHUB_TOKEN` only for requested API access. Never commit tokens, private keys, environment files, private customer data or generated reports. Logs and artifacts can contain reviewed source; choose repository access and retention accordingly.

Before publishing:

```sh
python3 scripts/public-check.py
python3 scripts/public-check.py --export /path/to/new-audit-directory
gitleaks dir /path/to/new-audit-directory --redact --no-banner
gitleaks git . --log-opts=--all --redact --no-banner
```

The content check rejects private/runtime paths, credential matches and non-English source policy violations. Gitleaks complements it; neither guarantees that arbitrary confidential information is absent. Scan archive payloads before compression. The archived platform was scanned before creation, and CI verifies its immutable digest and file inventory without executing it. The active archive is not included in the application JAR.

If a real credential entered history, revoke or rotate it before cleanup. Ignore rules and deleting files do not remove historical data. Changes to public hosting, external secrets or deployments are separate operational actions from archiving source.

Report discovered security issues privately to the repository maintainer through their published contact channel.
