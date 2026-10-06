# Order/payment demo

This small Java 21 / Spring Boot 3.4.4 service exists to exercise Undertow's business-risk review. It uses PostgreSQL, Spring JDBC, Flyway, explicit HALF_UP cents and a stable payment operation key. It is a separate Maven module from the Java 25 / Spring Boot 4.1.1 Undertow harness.

The declared contract is at most one payment per operation key, no successful order on payment rejection, and atomic order/audit writes. The gateway is an in-memory **controlled demo collaborator**, not a production payment provider: a reused key must carry the same amount and deduplicates the payment.

`PaymentGatewayAdapter` retries one ambiguous lost response with the same key. `OrderService` calls the gateway outside the database transaction, then invokes a separate proxied `OrderWriter`. PostgreSQL enforces operation uniqueness and the service deliberately reports duplicate placement. External payment and database commit cannot be one atomic transaction: a database failure after accepted payment requires reconciliation/retry with the same key, not a guarantee of distributed exactly-once execution. The demo does not implement production reconciliation, authentication or a real gateway.

Use JDK 21 and Maven 3.9+ for this module. Run `mvn -f demo/order-service/pom.xml test` for four controlled behavior tests. Run `mvn -f demo/order-service/pom.xml verify` with Docker for PostgreSQL rejection, real-proxy rollback and concurrent uniqueness tests. Testcontainers 1.21.4 supports the newer Docker API encountered during implementation. Restore JDK 25 before building or running the Undertow harness.

To run interactively, supply DATABASE_URL/USER/PASSWORD for a disposable PostgreSQL database and use `mvn -f demo/order-service/pom.xml spring-boot:run`. POST `/orders` with `{"operationKey":"order-1","amount":10.005}`. Returned outcomes are PLACED, DUPLICATE or REJECTED. Never deploy this unauthenticated educational service as a production payment platform.
