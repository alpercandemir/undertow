# NIGHT / SHIFT — ticket-transfer fixture

A chat app previews a ticket-transfer invitation before its recipient opens it. A preview must leave the ticket with its sender and the invitation usable. Only the named recipient can accept it.

This is a deliberately small, fictional event and in-memory Java model. It tests observable state transitions, without a real ticketing provider, authentication server, HTTP endpoint, or deployment. It is not a production ticket service.

Run with JDK 21 and Maven:

```sh
mvn --batch-mode --no-transfer-progress clean test
```

Two owner-declared contracts bind unchanged, exact JUnit methods. The dedicated public repository is `alpercandemir/undertow-showcase`; its `main` contains the approved contracts, tests, and workflow before either example PR. The broken PR deliberately accepts a transfer during preview. The correction changes preview copy without accepting it. Both remain open for demonstration; the broken change must not be merged.

`on: push` produces evidence for the actual branch head, avoiding a pull-request merge-ref/head mismatch. The workflow has read-only repository permissions, no provider or publishing credentials, pinned actions, a clean test invocation, and retained assertion failures. Repository maintainers control these fixture branches; this workflow is not an anonymous code runner.

The assertions are intentionally limited. A passing fixture does not establish real identity, concurrency, provider integration, or production safety. GitHub artifacts have finite retention; archived demo reports are historical observations, not a fresh evaluation of a moving PR.
