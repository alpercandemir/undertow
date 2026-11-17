# Refund behavior fixture

A standalone Java/JUnit component with sequential in-memory refunds. It supports the `refund-cap` and `refund-retry` adapters. Refunds cannot exceed capture; retries settle once; conflicting operation-key amounts reject without effects.

The six existing JUnit checks and separate generated regressions are compared automatically by the [behavior pipeline](../../docs/ci-behavior-review.md). The [eight-case gallery](../../docs/cases/README.md) includes a valid refund lifecycle, double-refund retries, aggregate overflow, conflicting keys and a compilation error.

`contracts.json` contains standing example contracts; `change-intent.md` and `change-contracts.json` preserve the partial-refund example's proposed behavior. Paths in these manifests are relative to this standalone component, not the enclosing repository. The example identity is not production policy or customer approval.

For maintainer verification with JDK 21+:

```sh
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test
```

The previous browser demo and fixed-probe capture script are preserved in the [archive](../../archive/README.md). Current execution evidence comes from the behavior workflows. Provider integration, persistence and concurrency are outside this fixture's scope.
