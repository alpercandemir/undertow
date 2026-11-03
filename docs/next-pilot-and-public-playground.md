# Next: a behavior contract workspace and an owner-backed pilot

Date: November 3, 2026. This follows the [consolidated roadmap](ai-era-consolidated-roadmap.md) and its [implementation/acceptance status](ai-era-implementation-status.md). Engineering across its stages is implemented; independent adoption and production acceptance remain open.

The product direction is contract management, discovery, and verification across implementation changes. The [behavior-preservation and monolith design](behavior-preservation-and-monoliths.md) connects the user's two conceptual inputs to standing invariants, proposed behavior, candidate extraction, temporary tests, and bounded replacement.

## What exists now

The [local engineering workspace](../demo/pr-workbench/README.md) contains editable version 1 contract JSON, free-text change intention, proposed checks, replay input, source/verifier inspection, and legacy test inventory. The primary example extracts refunds while adding partial refunds. Standing refund limits and retry obligations remain independent of that new behavior.

The same six JUnit checks run against legacy, broken, and corrected implementations. Two temporary candidate-probe executions expose cumulative partial-refund overflow. The UI reads these recorded local results and runs edited traces in a separate browser model. It does not compile submitted Java, infer general monolith semantics, authenticate fresh receipts, or approve drafts.

The original owned public ticket PRs and trusted-imported GitHub evidence remain a secondary reference below. Optional public PR lookup fetches metadata only. The site is local, not a deployed public service.

## The owned public example: NIGHT / SHIFT

The [public showcase repository](https://github.com/alpercandemir/undertow-showcase) models a ticket-transfer invitation. Mira sends a ticket to Leo. Before Leo opens the link, a chat app fetches its preview.

The business intention is that **a preview leaves ownership unchanged and the invitation usable**. Accepting the invitation is a separate action restricted to Leo. The event, accounts, and model are fictional. The code, PRs, tests, and CI runs are real.

We own this fixture to keep its branches, policy, and explanation maintainable. Ownership reduces dependency on someone else's PR; it does not guarantee indefinite GitHub availability. Pinned source and archived reports keep the lesson reproducible offline. GitHub artifacts still expire.

The approved base is [`b73b50a67ea9efcdf83658cd897ca83c34a4819a`](https://github.com/alpercandemir/undertow-showcase/tree/b73b50a67ea9efcdf83658cd897ca83c34a4819a). Both contracts, the tests, Maven settings, and pinned-action workflow are present there **before** either candidate. The showcase maintainer is the fixture owner; that label is not an independent customer endorsement.

| Example | Change | Actual evidence | Undertow result |
|---|---|---|---|
| [PR #1: regression](https://github.com/alpercandemir/undertow-showcase/pull/1) | Reuses `accept(recipient)` to prepare a richer preview. Ownership changes during preview. | [Run 41628184609](https://github.com/alpercandemir/undertow-showcase/actions/runs/41628184609): one assertion failure, one pass. Head `04b896ba3a6eab70371e5ad266e4288f652a9507`. | `PREVIEW-IS-READ-ONLY`: unsatisfied. `RECIPIENT-BOUND`: satisfied. |
| [PR #2: correction](https://github.com/alpercandemir/undertow-showcase/pull/2) | Renders improved copy without accepting the invitation. | [Run 41628213752](https://github.com/alpercandemir/undertow-showcase/actions/runs/41628213752): both unchanged tests pass. Head `59903f7b13a4ef5fe6164d38d34e6c302e7ec72c`. | Both declared contracts satisfied. |
| No-evidence exercise | Same regression head, evaluated without the CI bundle. | No imported execution supplied to this assessment. | Both incomplete. A plausible source explanation cannot substitute for execution. |

These two PRs are parallel candidates against the same approved base. PR #2 is the passing alternative, not a claim that PR #1 was merged and repaired in production. Keep the showcase PRs open, especially the deliberately failing one. Merge the actual playground implementation through Undertow's normal validation process.

### Rules, intent, checks, and limits

| Contract | Owner-declared intent | Exact JUnit identity | What it does not establish |
|---|---|---|---|
| `PREVIEW-IS-READ-ONLY` v1 | Opening a preview preserves ownership and leaves acceptance usable. | `example.tickets.TransferContractTest.linkPreviewDoesNotAcceptTransfer` | Real HTTP routing, email scanners, provider integration, persistence, or concurrent delivery behavior. |
| `RECIPIENT-BOUND` v1 | Another account cannot accept or change ownership. | `example.tickets.TransferContractTest.anotherAccountCannotAcceptTransfer` | Production authentication or proof that a supplied account identity is genuine. |

Inspect the [approved policy](https://github.com/alpercandemir/undertow-showcase/blob/b73b50a67ea9efcdf83658cd897ca83c34a4819a/.undertow/contracts.yaml), [unchanged test source](https://github.com/alpercandemir/undertow-showcase/blob/b73b50a67ea9efcdf83658cd897ca83c34a4819a/src/test/java/example/tickets/TransferContractTest.java), and [approved workflow](https://github.com/alpercandemir/undertow-showcase/blob/b73b50a67ea9efcdf83658cd897ca83c34a4819a/.github/workflows/contracts.yml). `on: push` records the exact candidate branch head, rather than silently substituting a PR merge-ref subject. No candidate changes the manifest, tests, build, or workflow.

The importer checked repository, administrator-selected workflow ID `405623956`, completed run, exact head, artifact origin, archive digest, and policy/oracle binding. Verification used Undertow source commit `4dff3a5bb0b283580ba4f31b1cf5e2179b0790e7`. The [archived catalog](../demo/pr-workbench/dist/evidence/catalog.json), [regression report](../demo/pr-workbench/dist/evidence/broken/contracts.json), and [correction report](../demo/pr-workbench/dist/evidence/fixed/contracts.json) retain those identities.

The archived page is a presentation of that observation. It does not perform fresh API-origin validation. Its committed JSON is not an independently signed receipt, and a visitor must not submit those files as trusted CI evidence for another subject. GitHub source origin alone does not prove a workflow is an adequate oracle; the owner-reviewed source bindings and limited assumptions matter.

### Workspace use

Inspect the standing contracts first, then the change intention and proposed checks. Compare legacy and candidate results separately. A successful new partial refund does not excuse a failed existing refund cap. Inspect the generated counterexample probe and its recorded outcomes; it remains a candidate verifier.

Edit structured contract drafts or free-text intention without rewriting historical observations. Edit replay-cases.json for bounded browser experiments. The discovery pane links existing test metadata to exact sources and can copy a candidate into an unapproved draft. General semantic extraction and production migration are later acceptance targets, described in the [monolith design](behavior-preservation-and-monoliths.md).

## Public-page intentions and rules

The page should let a visitor answer: “What behavior matters here? Who declared it? What independent observation supports it? What remains unknown?” It should teach the feedback loop and help an engineer adopt one contract in their repository.

| Area | Rule for the first public version |
|---|---|
| Editable intent | Versioned YAML/JSON contracts and free-text change intention remain drafts until reviewed. Passing old checks does not establish a new intention. |
| Editable business logic | Inspect recorded fixture source and edit bounded replay data. Temporary candidate tests run through reviewed local fixtures or the separate isolated CI path; the browser executes no submitted Java. |
| Policy authority | Read the approved target-side policy from a pinned commit. A visitor edit or PR-side weakening cannot replace it. |
| Result vocabulary | `satisfied`, `unsatisfied`, and `incomplete` describe declared checks; “not evaluated” describes unsupported submissions. Never produce a general safety score or “safe to merge.” |
| Evidence | Bind exact test identity, sources, policy, workflow, candidate head, run, and artifact. Missing, stale, skipped, errored, duplicated, or tampered evidence stays incomplete. |
| Moving PRs | Show full subject identities and capture time. A changed head, policy, target, or oracle needs a new assessment. Browser metadata checks do not refresh test evidence. |
| Public input | Initially accept only canonical `https://github.com/owner/repo/pull/number` URLs. Do not accept arbitrary hosts, credentials, repository archives, shell commands, or user-supplied trusted receipts. |
| Anonymous access | Allow curated exploration and bounded metadata reads. Do not expose the existing hosted tenant endpoints as an anonymous execution service. |
| Permissions | Read-only by default. Commenting, installation access, or other writes need a separate owner-authorized flow. |
| Cost and abuse | No model calls in the initial playground. For a hosted explorer, cap request size, diff/files, queue depth, execution time, per-client usage, and global spend before opening submissions. |
| Privacy and sharing | Do not persist prototype inputs. A future hosted report is unlisted by default, with explicit sharing and deletion/expiry controls; public source is not blanket consent to permanently republish analysis. Do not retain tokens or raw logs in public reports. |
| Failure | Display unavailable, expired, rate-limited, unsupported, or incomplete states. A server error cannot become an empty green result. |

GitHub documents a [60-request/hour unauthenticated REST limit](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api), so direct browser requests are suitable for optional exploration, not an unlimited public verifier. Its [artifact API](https://docs.github.com/en/rest/actions/artifacts) exposes digest/expiry metadata and download redirects; availability and access must be handled explicitly. The current local prototype requests only PR metadata and uses no token. Hosting requires its own server-side credential and quota design; putting a service token in static JavaScript is not an option.

## Execution sequence after this prototype

### 1. Validate the lesson with a few engineers

Observe whether people can distinguish intent, source, assertion evidence, and approval without coaching. Have them distinguish standing invariants from proposed behavior, find the refund regression, and review a source-linked discovery candidate. Record misunderstandings, time to first meaningful contract, and whether they can explain the remaining gap. This checks usefulness; it is not a statistically representative product validation.

Exit when another engineer can connect one business expectation to an exact check, explain why missing evidence is incomplete, and distinguish a draft from approved policy. If people only see a generic PR reviewer, simplify the experience before adding more controls.

### 2. Package contract-only CI adoption

Create a small trusted workflow/action for the existing `contracts export` → independent test run → `import-ci.py` → `verify` path. The current root `action.yml` is the older AI-review adapter; do not advertise it as the contract-only integration.

Keep policy/export/import authority outside candidate-controlled input. Review complete oracle/build/workflow inventories. Initially support the Java/Maven/JUnit path already implemented. Verify subject handling for forks, pushed heads, and merge candidates. Report shadow results before enabling any required gate. A missing integration should return setup instructions, not an invented result.

### 3. Add a public read-only PR explorer

Deploy the curated playground first. Then allow bounded public metadata/diff inspection, show detected policy and exact subject, and distinguish available source from accepted evidence. Reuse the Java verification engine on the server for approved imports; do not build a second JavaScript verifier. Unsupported repositories get a concrete contract template and integration path.

Before accepting arbitrary builds, add owner authorization, disposable isolation, egress/credential separation, concurrency limits, cleanup, retention, revocation, and operational ownership. Those are separate acceptance requirements in the [hosted service status](phase3-status.md). Public PR text is untrusted input, including instructions embedded in comments or files. Optional model explanations must have explicit consent and budgets, remain advisory, and cannot promote a proposal or manufacture evidence.

### 4. Run a real owner-backed shadow pilot

Choose one real Java/Maven repository and an accountable owner, approve two or three business contracts, and observe actual PRs. Have another engineer do the setup. Keep actual human judgments, verifier outcomes, infrastructure gaps, and any optional AI assessment separate. Review disagreements and oracle sensitivity before choosing which mature contracts can become required. The owned demo is a reproducibility fixture, not that independent pilot.

Deployment/monitoring acceptance remains a later environment-specific exercise. The playground neither deploys a candidate nor satisfies the roadmap's real production recovery requirements.

## Reproduce the public evidence

Use a trusted Undertow checkout with JDK 25, built `target/undertow.jar`, Python 3, a public fixture clone, and GitHub API access. The following records the regression; for the correction replace head/run/output with the second row of the table above. `GITHUB_TOKEN`, if used, belongs to the trusted importer environment, never the browser or public JSON.

```sh
git clone https://github.com/alpercandemir/undertow-showcase.git /tmp/ticket-showcase

scripts/undertow contracts export \
  --repo /tmp/ticket-showcase \
  --base b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --head 04b896ba3a6eab70371e5ad266e4288f652a9507 \
  --policy-commit b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --output /tmp/ticket-policy.json

python3 scripts/import-ci.py \
  --repository alpercandemir/undertow-showcase \
  --workflow-id 405623956 --run-id 41628184609 \
  --artifact-name ticket-contract-evidence \
  --base b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --head 04b896ba3a6eab70371e5ad266e4288f652a9507 \
  --contract-policy /tmp/ticket-policy.json \
  --output /tmp/ticket-bundle.json --receipt /tmp/ticket-receipt.json

scripts/undertow verify \
  --repo /tmp/ticket-showcase \
  --base b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --head 04b896ba3a6eab70371e5ad266e4288f652a9507 \
  --policy-commit b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --target-head b73b50a67ea9efcdf83658cd897ca83c34a4819a \
  --ci-bundle /tmp/ticket-bundle.json --ci-receipt /tmp/ticket-receipt.json \
  --output /tmp/ticket-result
```

The fixture's artifacts retain for 90 days, subject to GitHub repository settings. After expiry, dispatch the reviewed workflow at the pinned fixture branch, confirm the same SHA/workflow/oracle inventory, and import the new run/artifact IDs. Do not pretend the old artifact remains downloadable. Keep capture dates on archived reports; if source or oracle changes, review a new policy and publish a new case version.

An optional external reference is [Spring Framework PR #37403](https://github.com/spring-projects/spring-framework/pull/37403), a focused reactive-cache/retry fix. A [maintainer reported fail-before/pass-after checks](https://github.com/spring-projects/spring-framework/pull/37403#pullrequestreview-5426850688). We did not retrieve its JUnit artifact or an upstream-approved Undertow contract, so it must not appear as a preverified result. The owned ticket example is the primary runnable demonstration.
