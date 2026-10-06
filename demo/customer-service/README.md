# Customer-only order application

This template contains application code, ordinary CI, and company policy. It has no Undertow dependency, engine build, privileged review workflow, or model credential. Copy this directory into a dedicated GitHub repository for the internal hosted-service exercise.

Run `mvn verify` with JDK 21 or newer. The tests verify scoped tenant access and retry identity. They assume gateway deduplication; they do not prove a real payment provider deduplicates.

An authorized administrator installs the Undertow App on this repository, signs into the hosted control interface, connects the installation, registers this repository's numeric ID, and explicitly enables reviews with consent and limits. Select repository policy or upload these `.undertow/` files as an immutable central package. An operator supplies pilot credits. Set `ciWorkflowId` to this application's trusted workflow ID to import completed push/dispatch evidence; PR merge-ref runs are not source-head evidence.

Exercise two independently installed private copies under separate tenant accounts. A pair of copies in one installation does not prove tenant isolation. This template has not been installed or exercised against a live hosted service in this implementation.

For unsafe/corrected PRs, replace the payment key with a fresh UUID and restore it; remove tenant scoping and restore it. The inline rule examples describe those changes. Also exercise benign formatting, unsupported input, missing CI, unavailable model, insufficient credits, and installation removal. Record live outcomes and human feedback separately from authored replay.
