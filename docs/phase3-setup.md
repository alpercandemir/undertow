# Phase 3 internal service setup

The repository now includes an executable central control API, encrypted transactional state, an immutable-policy worker boundary, a separately credentialed publisher, and a customer-only application template. This is an **internal implementation**, not a completed external hosted pilot. See [delivery status](phase3-status.md) for unverified gates and implementation limits.

## Run the credential-free worker demonstration

With JDK 25 and Maven, run:

```sh
mvn --no-transfer-progress verify
python3 scripts/phase3-demo.py --output .undertow/phase3-demo
```

Open `.undertow/phase3-demo/review/report.md` and `service-envelope.json`. The script creates a customer-only Git repository, introduces a payment-key defect, and exercises the packaged worker with authored model responses. It calls neither GitHub nor Gemini and makes no live-quality claim. Use a new output directory for each run.

The original CLI, report v1/v2 contracts, and Phase 2 workflow remain available. The new envelope has schema version 1 independently of the engine report. It binds review/tenant, pinned source/target/merge-base/policy/CI, release digest, execution settings, prompt identity, artifact hashes, mode, and usage. Engine findings remain advisory; customer CI controls its own merge policy.

## Configure the control API

Register a GitHub App for GitHub.com with metadata/contents read and pull requests write. Add Actions read only when importing CI evidence. Configure the callback as `https://YOUR_ORIGIN/auth/callback`, webhook as `https://YOUR_ORIGIN/v1/webhooks/github`, and subscribe to pull request, installation, installation repositories, repository, and push events. Verify the App's actual bot login and numeric bot user ID. The hosted publisher rejects `github-actions[bot]`.

Copy `deploy/phase3/service.example.json` to protected operator configuration. Set the registered IDs, public HTTPS origin, PKCS#8 App key path, actual bot identity, and **SHA-256 of the executable JAR**. The example rates are arbitrary internal demonstration units, not model prices or an approved tariff. Approve charging, data processing, retention, capacity, and Phase 2 acceptance before setting `liveApproved: true`.

Supply these credentials through a protected process environment or secret manager; never commit them:

| Control credential | Purpose |
|---|---|
| `UNDERTOW_DATABASE_KEY` | Base64 encoding of a cryptographically random 32-byte AES key |
| `UNDERTOW_GITHUB_CLIENT_SECRET` | GitHub App user sign-in exchange |
| `UNDERTOW_WEBHOOK_SECRET` | Strong webhook HMAC secret, at least 32 characters |
| `UNDERTOW_OPERATOR_TOKEN` | Strong separate operator credential, at least 32 characters |
| PKCS#8 App key file | Installation-token minting in control/preparation |

Start the packaged application behind a TLS reverse proxy:

```sh
java -jar target/undertow.jar service --config /protected/service.json \
  --data /protected/undertow-data --bind 127.0.0.1 --port 8080
```

Use a protected local filesystem for the embedded H2 database and workspaces. Service records are AES-256-GCM encrypted with identity-bound authenticated data. The database transaction lock serializes admission, reservations, leases, and settlements. This is a low-volume embedded deployment, not an implemented distributed database topology. Do not share an H2 file over network storage or expose H2 TCP/console access.

The JAR must match the pinned release. Keep prior worker images/JARs and drain or explicitly cancel pending work when changing releases; an incompatible runner fails digest validation rather than silently substituting another engine. Immutable manifests preserve the original settings and policy.

Configure proxy request/body/time/concurrency limits and authentication rate controls. Browser cookies are Secure, HttpOnly, SameSite=Lax, bounded to eight hours or token expiry. OAuth state is cookie-bound, expires after ten minutes, and is single use. Mutations require the exact public origin and session CSRF token. Membership and current GitHub repository permission are checked server-side; installation access does not substitute for user access.

## Isolate workers and publishers

The control process does not require a model credential. It prepares a bare Git object store, immutable manifest, and optional trusted CI receipt. No customer checkout/build/test runs in the worker.

Build `deploy/phase3/Dockerfile` with an operator-verified digest-pinned Ubuntu-compatible JRE 25 base, then pin the resulting image by digest. This build recipe requires Git and Python only for trusted object reads and preparation helpers. Do not use a floating image tag in the runner configuration.

`workerCommand` and `publisherCommand` are trusted arrays of executable/arguments, not shell strings or customer settings. Example shapes:

```json
{
  "workerCommand": ["/protected/worker-container.sh", "registry/undertow@sha256:IMAGE_DIGEST", "collect", "-", "-"],
  "publisherCommand": ["/protected/publisher-container.sh", "registry/undertow@sha256:IMAGE_DIGEST"]
}
```

For live mode, select `executionMode: "live"`, set the worker runner's mode to `live`, and pass a protected env-file containing **only** `GEMINI_API_KEY`. For replay, pass a trusted fixture path as the fourth runner argument and use `executionMode: "replay"`. The service defaults to collection and no runner/publication. An empty command leaves durable reviews queued; it does not invent a review.

Create and restrict the named `undertow-worker` and `undertow-publisher` container networks through the hosting environment. Worker egress should permit only the approved model and host-selected evidence services; publisher egress should permit GitHub and the control origin. Mount only the job's read-only source/manifest and writable output. No database, App key, global environment, host Docker socket, or another customer's workspace belongs in a worker.

The publisher receives only a repository-scoped installation token and a short-lived publication permit. Before every GitHub mutation it calls the control API to revalidate its lease, current snapshot, tenant/repository access, initiating user's authority, and service kill switch. Existing Phase 2 pending/stale/post-write checks and numeric bot ownership remain in force. Publication attempts/receipts and inference accounting are independent. Recovery avoids another model call and refuses blind duplicate comment creation.

The supplied container recipes have not been exercised against a registered App or a deployed runtime. The hosting supervisor must terminate orphaned containers/processes on timeout or control-process loss, enforce egress and disk limits, and clean crash-left workspaces within the declared retention window. OS subprocess separation alone is not an external pilot isolation guarantee.

## Customer administration and API

The same-origin interface at `/` provides GitHub sign-in, tenant selection, installation association, repository registration/settings, immutable package validation/selection, credits/usage, review detail/evidence, and feedback. Install first, then connect using an authorized account. Numeric IDs select resources; they never prove authority. A repository starts disabled. Enabling requires explicit consent; sample settings expose this as `consent: true`.

Source-bearing endpoints require a tenant source-reading role **and** live GitHub read permission. Reruns additionally require reviewer authority and GitHub write/maintain/admin permission. Billing views omit repository paths, snapshots, policy contents, findings, and evidence. Administrators can assign/revoke combined roles for users who have already signed in. Operators have a separate API credential and no browser source-download route.

| Route | Contract |
|---|---|
| `GET /v1/session` | Signed-in user, CSRF token, authorized tenant contexts |
| `POST /v1/installations` | Verify account administration, associate one account/installation to one tenant |
| `GET/POST /v1/repositories` | List authorized repositories / register a selected numeric repository ID |
| `PATCH /v1/repositories/{id}/settings` | Review opt-in/consent, trusted branches, source mode/package, review/month limits, optional `ciWorkflowId` |
| `POST /v1/rule-packages` | Validate immutable `.undertow/` files; activation is a separate settings change |
| `POST /v1/repositories/{id}/pull-requests/{pr}/reviews` | Intentional rerun with `Idempotency-Key` |
| `GET /v1/repositories/{id}/reviews` | Authorized bounded history |
| `GET /v1/reviews/{id}` / `/artifacts` | Separate orchestration, review, publication, metering and private evidence |
| `POST /v1/reviews/{id}/feedback` | Append assessment/correction against a validated finding ID |
| `GET /v1/credits` / `/usage` | Source-free balances, ledger and usage |
| `PATCH /v1/memberships/{user}` | Administrator role changes; empty roles revoke membership |
| `POST /operator/grants` | Operator allocation with reason, expiry and idempotency key |
| `POST /operator/reconciliation` | Verified usage settlement or explicit service-absorbed release |
| `POST /operator/control` | Tenant suspend/resume/delete or service disable/enable with reason |

Customer requests use the session cookie, `X-Undertow-Tenant`, and `X-CSRF-Token` for mutations. Stable public errors include `sign_in_required`, `tenant_access_denied`, `role_denied`, `github_access_denied`, `invalid_policy_files`, `idempotency_conflict`, and actionable admission reasons. Malformed/oversized requests fail before scheduling. No machine-token API, BYOK or payment provider is included in P0.

Operator grants require `amount`, millisecond UTC `expires`, `tenant`, `reason`, and `Idempotency-Key`. Reconciliation requires `tenant`, `review`, `reason`, and `usage` with `known`, `inputTokens`, `outputTokens`. Unknown usage is never called free. Integer tariffs, immutable grant allocations, expiry entries, reservations, settlements and releases explain balances. Holds are released with an audited service-absorbed reason at the 24-hour reconciliation deadline. Deliberate new reviews may consume credits; transport retries/publication-only recovery do not duplicate inference charges.

## Rules and CI

Repository mode reads `.undertow/rules.yaml`, `.undertow/guidelines.md`, optional `.undertow/business-context.yaml`, and package-local referenced files at the **live target commit**. Head-side changes cannot suppress current policy. Central packages carry the same files with immutable tenant-owned IDs/hashes. Existing v1/v2 rule schema validation, registered executors, references, examples, scoped exceptions and expiration checks are reused.

The host's execution/model/budget and dependency-source allowlists have separate `@service/` hashes. Customer policy cannot select a model key environment, provider, executable, host allowlist, or tenant. Uploaded package paths must remain within `.undertow/`; external mutable references and unsafe paths are rejected.

Configure `ciWorkflowId` only for an administrator-approved application workflow. Preparation selects completed push/dispatch evidence for the exact source-head SHA, pins workflow/run/artifact/digest identity, and invokes the existing trusted importer. API/archive provenance, receipt/content hashes, archive limits and XML protections remain enforced. Merge-ref PR runs, caller-uploaded bundles, missing artifacts and mismatched commits cannot establish executed-test success. Hosted CI payloads are additionally bounded to 180 KB after normalization.

## Operations, deletion and rollback

Disable admissions through the global kill switch or suspend a tenant/repository before an incident response. Preserve ledger/job/publication state. Lost pre-inference leases have at most three attempts; potential inference use is held for reconciliation rather than blindly rerun. Webhook processing uses bounded delivery retries and source-free terminal causes. Raw provider bodies/credentials are not logged by the service boundaries.

Successful/failing orchestrator executions clean their transient workspaces immediately. Immutable preparation rows expire one hour after terminal completion; artifacts expire after seven days. Tenant deletion disables access immediately and removes active artifact/policy/preparation/feedback rows. Physical encrypted-database page compaction, crash-left workspace cleanup, provider retention and backup expiry need an operator-approved deployment procedure; application row deletion does not promise deletion from backups or provider systems.

Back up the stopped/quiesced encrypted H2 file and protect the AES key separately. Restore into an admission-disabled environment, restore current revocation/suspension state before exposing endpoints, recover expired leases, and inspect reconciliation/publication ambiguity. Do not roll back to a database snapshot that erases settlements or duplicate a live control database. Reconcile from retained receipts before resuming. A real restore/retention drill is an external pilot gate, not claimed by the local encryption/restart test.

Deployment, source-free 90-day archival, alerts/metrics/export, canary operation, orphan cleanup, actual private-App onboarding, live provider terms and the Section 10 measurement population remain outstanding. [Phase 3 status](phase3-status.md) records these separately from the code delivered here.
