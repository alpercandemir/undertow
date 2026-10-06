# Report v2 migration (P2-05, P2-06)

Generated `report.json` now has `schema_version: 2`. All v1 review fields retain their meaning; `base_sha` is the **analysis diff base**, and `head_sha` is the reviewed source commit. The strict report schema accepts v1 without `details` and v2 with `details`; the checked-in v1 sample remains a compatibility fixture.

`details` adds execution `mode` (`live/replay/collect`), sanitized `failure_cause`, JDK runtime/analyzer identity, declared dependency versions, `policy_commit`, `target_head`, per-rule coverage, and imported CI execution/analyzer provenance. For ordinary local review, diff base, target head, and trusted policy commit default to base. PR snapshots explicitly use merge base for analysis and current target tip for policy/staleness. Existing consumers must negotiate v2 before assuming those commits are interchangeable. `prompt_version` is now `undertow-review-v2`.

Model `finish_review` output may contain `rule_assessments`; that does not give the model authority over report metadata. Evaluated contextual assessments require registered evidence kinds and source evidence for the declared path scope. Missing assessments remain unevaluated. Deterministic coverage comes only from verified registered CI imports.

`publication.json` is a separate receipt: `status`, `comment_id`, `reviewed_head`, `run_id`, `finding_fingerprints`, and `reason`. Review states remain `complete/partial/failed/skipped`; publication states are `published/stale/failed/not_requested`. A complete review means its declared process finished, not universal defect detection. Partial/skipped/failed results are never approvals. Evidence hash/report policy hashes continue to bind review inputs, and the trusted workflow artifact manifest binds file content and runner origin separately.

CI v1 remains hash-checked but origin-unverified. CI v2 additionally needs a separate trusted importer receipt binding repository, head, run, workflow, artifact, and the full bundle hash. Do not migrate unverified v1 evidence by merely adding a v2 field.

Finding IDs from a model remain display/run-local identifiers. Publications reconcile findings using provider/repository/change/rule and source-location context fingerprints. Different repositories, PRs, rules, source contexts, and repeated code occurrences do not share identities. This is conservative reconciliation; a changed context may create a new fingerprint and must not be claimed automatically resolved by an incomplete review.
