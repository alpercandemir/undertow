# Refund extraction

A reviewed Java/JUnit fixture for extracting refund processing from a monolith while adding partial refunds. The aggregate refund cap and operation-key semantics are standing obligations. The model is sequential and in memory, without real HTTP, provider, persistence, concurrency, or deployment behavior.

`contracts.json` holds standing version 1 contracts. `change-intent.md` explains the change. `change-contracts.json` holds proposed acceptance checks. The existing CLI reads JSON/YAML; pass `--manifest contracts.json` for this file.

Manifest paths are relative to the standalone fixture root. For committed-source CLI validation, copy this directory into its own Git checkout; it is not a separate Git root inside the Undertow checkout. The `example/refund-extraction` identity is illustrative, not a configured public repository or approved customer policy.

With JDK 21 selected:

```sh
mvn --batch-mode --no-transfer-progress -f demo/refund-extraction/pom.xml test
python3 scripts/refund-showcase.py --output .undertow/refund-run
```

The script executes the same six tests in independent legacy, broken, and corrected source copies. Legacy rejects partial refunds. The broken extraction accepts them but allows cumulative overflow. The correction checks the remaining balance. Existing and proposed outcomes stay separate. Expected assertion failures are retained; errors/missing/duplicate executions reject the exercise.

A temporary counterexample test is generated into the ignored broken/corrected copies and executed separately. It tries 8,000 plus 3,000 against a 10,000 capture. It fails on the broken source and passes on the correction, without becoming an approved oracle. This reviewed fixture runner strips provider/publisher variables and uses offline Maven; it is not a generic sandbox or model tool and does not claim host isolation.

The [workspace](../pr-workbench/README.md) reads a snapshot produced with:

```sh
python3 scripts/refund-showcase.py --output .undertow/refund-capture \
  --snapshot demo/pr-workbench/dist/evidence/refund-fixture.json
```

These are actual local JUnit observations, not trusted GitHub receipts. The snapshot retains source hashes, exact checks, the candidate probe, and a narrow inventory of test descriptions. It is not general monolith rule extraction. See the [product/discovery design](../../docs/behavior-preservation-and-monoliths.md).
