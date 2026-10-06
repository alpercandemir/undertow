# Client showcase and test guide

Run commands from the repository root. The harness requires JDK 25, Maven 3.9+, Git and Python 3. Check `java -version` and `mvn -version`; both should use JDK 25.

## Build and showcase

```sh
mvn verify
python3 scripts/showcase.py --output .undertow/client-showcase
```

Open `.undertow/client-showcase/index.md`. The default tour runs ten scenarios and links their review and evidence reports. Choose a fresh output directory on each run.

| Scenario | Client story |
|---|---|
| retry-key-unsafe / retry-key-corrected | A lost payment response can cause a second charge; the corrected change preserves the operation key. |
| money-rounding-unsafe / money-rounding-corrected | A rounding change alters the charged amount; explicit cents restore the contract. |
| transaction-this-unsafe | A self-call bypasses the transaction proxy, risking partial writes. |
| database-unique-removal | Removing database uniqueness allows duplicate operations. |
| log-token-unsafe | Logging a token exposes a credential. |
| http-timeout-unsafe | Removing a request timeout can leave a downstream call waiting. |
| dependency-major-inventory | Dependency changes need investigation; no authored finding does not establish compatibility. |
| record-benign | A benign modern Java change produces no authored finding. |

For a 10–15 minute presentation, show the payment diff, then its finding, trigger, evidence references and proposed regression test. Show the corrected payment report next. Repeat with money, then pick two risks relevant to the client's domain. Finish with the benign case and dependency coverage notes. Explain that partial coverage is visible and must not be interpreted as a safety verdict.

These responses are authored fixtures dispatched through the real harness. They demonstrate tool dispatch, pinned snapshots, validation and reporting. They do not establish live-model accuracy or execute the proposed regression tests.

Run every scenario or select a custom tour:

```sh
python3 scripts/showcase.py --all --output .undertow/showcase-all
python3 scripts/showcase.py --case log-token-unsafe --case log-token-corrected \
  --output .undertow/security-tour
```

There are 40 fixtures: 17 unsafe changes, 17 corresponding corrections (including two restored database constraints), four benign Java changes and two dependency inventory cases. They cover retry identity (including scope and loops), decimal construction/rounding/division/equality, private/self-invoked transactions, primary-key/unique constraints, token/card/API-key logging, request/connect timeouts, dependency versions/properties, and records/switches/virtual threads/renames. Ten cases are tuning inputs and thirty are held out.

## Run tests and evaluations

```sh
# JDK 25: Java behavior tests, formatting and static analysis
mvn verify
# Python helper tests
python3 -m unittest discover -s scripts/tests -v
# All fixtures in collect, diff and tools modes (120 reviews)
python3 scripts/evaluate.py --split all --output .undertow/eval-all
```

Evaluation writes `evaluation.json` with per-case results and mode summaries. Replay metrics validate authored harness behavior. For a real provider comparison, configure the Gemini credentials and model as described in the root README, then run:

```sh
python3 scripts/evaluate.py --provider live --split heldout --repeats 3 \
  --modes collect,diff,tools --output .undertow/eval-live
```

Live evaluation makes provider requests and can incur charges. It sends public synthetic fixture excerpts. Current live-model accuracy is unmeasured.

## Separate executable order service

Switch to JDK 21 (`java -version` and `mvn -version` should agree):

```sh
# Four controlled payment/money behavior tests; no Docker required
mvn -f demo/order-service/pom.xml test
# Also exercise real PostgreSQL rollback, rejection and concurrent uniqueness
# Start Docker first; Testcontainers creates a disposable database
mvn -f demo/order-service/pom.xml verify
```

To run the service, supply `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` for a disposable PostgreSQL database, then:

```sh
mvn -f demo/order-service/pom.xml spring-boot:run
```

In another terminal:

```sh
curl -sS -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"operationKey":"client-order-1","amount":10.005}'
```

Repeat the same request to demonstrate duplicate handling. Use a fresh operation key for a new order. See the service README for its controlled gateway contract and distributed transaction limits. Restore JDK 25 before running Undertow.
