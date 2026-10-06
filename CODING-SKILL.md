# Java Engineering Skill

## Purpose

Use this skill whenever generating, reviewing, refactoring, or designing Java backend code.

The goal is to produce:

- correct
- readable
- maintainable
- testable
- secure
- idiomatic
- modern Java

Prefer correctness and simplicity over cleverness.

Target runtime for the harness unless explicitly stated otherwise (the demo and reviewed Java 21 fixtures retain their own toolchains):

```text
Java 25
Spring Boot 4
JUnit Jupiter (Boot-managed)
```

---

# 1. Priority of Rules

When rules conflict, use this priority:

```text
1. Correctness
2. Security
3. Data integrity
4. Concurrency safety
5. Maintainability
6. API clarity
7. Performance
8. Style
```

Never sacrifice correctness for conciseness.

---

# 2. Rule Severity

Interpret guideline keywords as follows:

```text
MUST
Violation normally requires correction.

SHOULD
Preferred default. Deviate only with a concrete reason.

MAY
Optional technique depending on context.

AVOID
Usually harmful or misleading.

NEVER
Do not generate this pattern except when interacting with unavoidable legacy code.
```

---

# 3. General Coding Principles

## MUST — Optimize for readability

Prefer code whose intent is immediately visible.

Bad:

```java
var x = a.stream()
        .filter(i -> i.s() == 1)
        .map(i -> f(i))
        .toList();
```

Better:

```java
List<Order> activeOrders = orders.stream()
        .filter(Order::isActive)
        .map(this::enrichOrder)
        .toList();
```

Use domain terminology rather than implementation terminology.

---

## MUST — Avoid unnecessary cleverness

Do not compress business logic merely to reduce line count.

Bad:

```java
return user != null && user.getAccount() != null
        && user.getAccount().isActive()
        ? user.getAccount().getBalance()
        : BigDecimal.ZERO;
```

Prefer explicit logic if it improves readability.

---

## MUST — Keep methods focused

A method should normally perform one conceptual operation.

If a method:

- validates input
- queries the database
- performs calculations
- sends notifications
- updates state

it probably has too many responsibilities.

Extract meaningful operations.

---

## SHOULD — Keep nesting shallow

Prefer:

```java
if (!order.isPayable()) {
    return PaymentResult.rejected();
}

Payment payment = createPayment(order);
return PaymentResult.success(payment);
```

instead of:

```java
if (order.isPayable()) {
    Payment payment = createPayment(order);
    if (payment != null) {
        return PaymentResult.success(payment);
    }
}

return PaymentResult.rejected();
```

Use guard clauses where appropriate.

---

# 4. Naming

## MUST — Names communicate intent

Bad:

```java
int d;
String str;
List<User> data;
boolean flag;
```

Better:

```java
int retryCount;
String customerEmail;
List<User> activeUsers;
boolean paymentCompleted;
```

---

## MUST — Boolean names describe predicates

Prefer:

```java
isActive
hasPermission
canRetry
shouldPersist
```

Avoid:

```java
activeFlag
permissionStatus
retryBoolean
```

---

## SHOULD — Avoid meaningless suffixes

Avoid names such as:

```text
UserInfo
UserData
UserObj
UserBean
UserManager
CommonUtil
Helper
Processor
```

unless they accurately describe the abstraction.

Prefer domain-specific names.

---

# 5. Types and Domain Modeling

## MUST — Model concepts explicitly

Avoid primitive obsession.

Instead of:

```java
void transfer(String source, String target, BigDecimal amount)
```

consider:

```java
void transfer(
        AccountId source,
        AccountId target,
        Money amount
)
```

when the domain warrants it.

---

## SHOULD — Prefer records for immutable data carriers

Use:

```java
public record CustomerDto(
        UUID id,
        String name,
        String email
) {}
```

when the object:

- primarily carries data
- is immutable
- has value semantics
- does not require complex inheritance

Do not automatically replace rich domain entities with records.

---

## SHOULD — Prefer immutable objects

Prefer:

```java
public final class Money {

    private final BigDecimal amount;
    private final Currency currency;

    // ...
}
```

over mutable value objects.

---

## MUST — Avoid exposing mutable internal state

Bad:

```java
public List<Order> getOrders() {
    return orders;
}
```

Better:

```java
public List<Order> getOrders() {
    return List.copyOf(orders);
}
```

or maintain immutability throughout the model.

---

# 6. null and Optional

## MUST — Define nullability deliberately

Do not use `null` when absence is part of normal API semantics without making that behavior clear.

---

## SHOULD — Use Optional for return values representing absence

Prefer:

```java
Optional<User> findUser(UserId id);
```

instead of:

```java
User findUser(UserId id); // may secretly return null
```

---

## AVOID — Optional as fields

Avoid:

```java
class User {
    private Optional<Address> address;
}
```

Prefer:

```java
class User {
    private Address address;
}
```

with explicit nullability semantics where required.

---

## AVOID — Optional parameters

Avoid:

```java
void sendEmail(Optional<String> subject)
```

Prefer overloaded methods, nullable contracts where justified, or request objects.

---

## NEVER — Call Optional.get() without proving presence

Bad:

```java
User user = optionalUser.get();
```

Prefer:

```java
User user = optionalUser.orElseThrow(
        () -> new UserNotFoundException(userId)
);
```

---

# 7. equals, hashCode and Object Contracts

## MUST — Override hashCode when overriding equals

Bad:

```java
@Override
public boolean equals(Object obj) {
    // custom equality
}
```

without a matching `hashCode()`.

This causes incorrect behavior in:

```text
HashMap
HashSet
ConcurrentHashMap
```

---

## MUST — Equality semantics must be stable

Do not base `hashCode()` on mutable values if instances are used as hash keys.

---

## SHOULD — Prefer records for straightforward value equality

Example:

```java
public record CustomerId(UUID value) {}
```

---

# 8. Collections

## MUST — Program to interfaces

Prefer:

```java
List<User> users;
Map<UserId, User> usersById;
Set<Role> roles;
```

instead of:

```java
ArrayList<User> users;
HashMap<UserId, User> usersById;
HashSet<Role> roles;
```

unless implementation behavior is required.

---

## MUST — Do not modify collections during enhanced iteration

Bad:

```java
for (User user : users) {
    if (!user.isActive()) {
        users.remove(user);
    }
}
```

Prefer:

```java
users.removeIf(user -> !user.isActive());
```

---

## SHOULD — Return empty collections instead of null

Bad:

```java
return null;
```

Better:

```java
return List.of();
```

---

## SHOULD — Use immutable collection factories where applicable

Prefer:

```java
List.of(...)
Set.of(...)
Map.of(...)
```

for fixed collections.

---

## SHOULD — Use copyOf when crossing ownership boundaries

Example:

```java
this.roles = Set.copyOf(roles);
```

---

# 9. Streams

## SHOULD — Use streams for transformations

Good:

```java
List<String> emails = users.stream()
        .filter(User::isActive)
        .map(User::email)
        .toList();
```

---

## AVOID — Streams for complex imperative workflows

Bad:

```java
users.stream()
        .map(...)
        .peek(...)
        .filter(...)
        .peek(...)
        .forEach(...);
```

If the pipeline performs stateful business operations, use explicit control flow.

---

## NEVER — Use peek for business side effects

`peek()` should primarily be used for debugging/inspection.

Bad:

```java
orders.stream()
        .peek(this::persist)
        .toList();
```

---

## SHOULD — Avoid excessively long stream chains

If a stream requires substantial mental parsing, extract named methods or use ordinary loops.

---

# 10. Exceptions

## MUST — Never silently swallow exceptions

Never:

```java
try {
    execute();
} catch (Exception e) {
}
```

---

## MUST — Preserve meaningful context

Bad:

```java
catch (IOException e) {
    throw new RuntimeException("Failed");
}
```

Better:

```java
catch (IOException e) {
    throw new CustomerImportException(
            "Failed to import customer file: " + fileName,
            e
    );
}
```

---

## MUST — Preserve the original cause

Prefer:

```java
throw new OrderProcessingException(message, e);
```

---

## SHOULD — Use domain-specific exceptions

Prefer:

```text
CustomerNotFoundException
PaymentRejectedException
InventoryUnavailableException
```

over generic:

```text
RuntimeException
IllegalStateException
Exception
```

when domain meaning matters.

---

## AVOID — Catching Exception broadly

Do not write:

```java
catch (Exception e)
```

unless implementing a deliberate application boundary such as:

- request boundary
- worker boundary
- scheduler boundary
- message consumer boundary

Even then, log and classify the failure appropriately.

---

# 11. Resources

## MUST — Use try-with-resources

Bad:

```java
InputStream input = new FileInputStream(file);

try {
    process(input);
} finally {
    input.close();
}
```

Prefer:

```java
try (InputStream input = Files.newInputStream(path)) {
    process(input);
}
```

Apply to:

```text
streams
readers
writers
JDBC resources
closeable clients
```

where ownership belongs to the method.

---

# 12. BigDecimal and Money

## MUST — Do not use double or float for monetary values

Never:

```java
double price = 19.99;
```

Prefer:

```java
BigDecimal price = new BigDecimal("19.99");
```

or:

```java
BigDecimal price = BigDecimal.valueOf(19.99);
```

---

## MUST — Define rounding explicitly

Example:

```java
amount.setScale(2, RoundingMode.HALF_UP);
```

Do not allow accidental arithmetic semantics.

---

## MUST — Compare BigDecimal values intentionally

Be aware:

```java
new BigDecimal("1.0").equals(new BigDecimal("1.00"))
```

is false.

For numeric comparison use:

```java
a.compareTo(b) == 0
```

when scale should not affect equality.

---

# 13. Date and Time

## MUST — Use java.time

Prefer:

```text
Instant
LocalDate
LocalDateTime
OffsetDateTime
ZonedDateTime
Duration
Period
```

Avoid legacy:

```text
Date
Calendar
SimpleDateFormat
```

unless required for legacy interoperability.

---

## MUST — Be explicit about timezone semantics

For globally meaningful timestamps prefer:

```java
Instant
```

or:

```java
OffsetDateTime
```

Do not assume server local timezone.

---

# 14. Enums

## MUST — Avoid magic constants

Bad:

```java
if (status == 3) {
}
```

Prefer:

```java
if (status == OrderStatus.COMPLETED) {
}
```

---

## SHOULD — Put behavior near enum values when appropriate

Example:

```java
public enum PaymentType {

    CREDIT_CARD {
        @Override
        Fee calculateFee(Money amount) {
            // ...
        }
    },

    BANK_TRANSFER {
        @Override
        Fee calculateFee(Money amount) {
            // ...
        }
    };

    abstract Fee calculateFee(Money amount);
}
```

Do not force this pattern if strategy classes are clearer.

---

# 15. Modern Java

## SHOULD — Use switch expressions when they improve clarity

Prefer:

```java
return switch (status) {
    case ACTIVE -> processActive();
    case SUSPENDED -> processSuspended();
    case CLOSED -> processClosed();
};
```

over mutation-heavy switch statements.

---

## SHOULD — Use pattern matching where appropriate

Example:

```java
if (value instanceof Customer customer) {
    process(customer);
}
```

instead of explicit casting.

---

## SHOULD — Use sealed hierarchies for closed domain models

Example:

```java
sealed interface PaymentResult
        permits PaymentSuccess, PaymentFailure {
}
```

when all permitted implementations are controlled.

---

# 16. Concurrency

## MUST — Do not assume ordinary collections are thread-safe

Never use:

```java
HashMap
ArrayList
HashSet
```

for concurrent mutation without synchronization or confinement.

---

## MUST — Prefer high-level concurrency utilities

Prefer:

```text
ExecutorService
CompletableFuture
ConcurrentHashMap
Semaphore
CountDownLatch
BlockingQueue
Atomic*
```

over manual wait/notify coordination.

---

## SHOULD — Minimize shared mutable state

Prefer:

```text
immutability
message passing
request confinement
thread confinement
```

before introducing locks.

---

## MUST — Avoid locking on externally accessible objects

Never:

```java
synchronized (request) {
}
```

or:

```java
synchronized ("LOCK") {
}
```

Prefer private lock ownership when explicit locking is required.

---

## SHOULD — Use virtual threads for blocking I/O workloads

For large numbers of blocking tasks:

```java
try (ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor()) {

    executor.submit(this::performBlockingOperation);
}
```

---

## NEVER — Pool virtual threads

Do not create an artificial pool of virtual threads.

Concurrency limits should instead reflect scarce resources.

Example:

```java
Semaphore databaseLimit = new Semaphore(50);
```

Use actual capacity constraints such as:

```text
database connections
external API limits
rate limits
memory
CPU
```

---

## SHOULD — Use platform thread pools for CPU-bound bounded work

Concurrency strategy must match the workload.

Do not automatically use virtual threads for CPU-intensive parallel computation.

---

# 17. ThreadLocal

## AVOID — Unnecessary ThreadLocal state

ThreadLocal complicates:

- testing
- request propagation
- virtual thread migration
- debugging
- memory management

Prefer explicit context passing when reasonable.

---

# 18. Synchronization

## MUST — Keep critical sections small

Do not perform:

```text
database calls
HTTP calls
file I/O
slow serialization
unbounded computations
```

while holding a lock unless unavoidable.

---

## MUST — Document non-obvious concurrency invariants

Example:

```java
// balance and version must be updated atomically.
```

Concurrency assumptions must not remain implicit.

---

# 19. Spring Dependency Injection

## MUST — Prefer constructor injection

Prefer:

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
}
```

Avoid field injection:

```java
@Autowired
private OrderRepository orderRepository;
```

---

## SHOULD — Dependencies be final

Constructor-injected dependencies should usually be:

```java
private final
```

---

# 20. Spring Components

## MUST — Do not use Spring components as global utility containers

Avoid:

```java
@Component
public class CommonUtils {
}
```

Design cohesive services with clear responsibilities.

---

## SHOULD — Keep controllers thin

Controllers should normally perform:

```text
request parsing
validation trigger
authorization integration
delegation
response mapping
```

Business logic belongs elsewhere.

---

# 21. Transactions

## MUST — Define transaction boundaries at use-case level

Prefer:

```java
@Transactional
public void placeOrder(...) {
}
```

at application/service boundaries.

Avoid scattering transactions across arbitrary helper methods.

---

## MUST — Understand proxy limitations

Do not assume:

```java
this.transactionalMethod();
```

will trigger Spring proxy-based transaction interception.

---

## SHOULD — Keep transactions short

Do not perform slow external HTTP calls inside database transactions unless consistency requirements explicitly require it.

---

## MUST — Avoid hidden transactional behavior

Transaction boundaries should be obvious during code review.

---

# 22. Persistence / JPA

## MUST — Avoid uncontrolled N+1 queries

Review entity relationships and generated SQL.

Use appropriate:

```text
fetch joins
entity graphs
projection queries
batch fetching
```

depending on the use case.

---

## SHOULD — Avoid exposing JPA entities through API boundaries

Prefer:

```text
Entity
   ↓ mapping
Domain / DTO
   ↓
API
```

Do not bind public APIs directly to persistence models.

---

## SHOULD — Prefer explicit query intent

For performance-sensitive operations, do not rely blindly on ORM-generated behavior.

Inspect SQL.

---

## AVOID — EAGER relationships by default

Prefer explicit loading based on use case.

---

# 23. Database Integrity

## MUST — Enforce critical invariants in the database too

Application validation is insufficient for constraints such as:

```text
unique email
unique external ID
foreign keys
non-null domain invariants
```

where the database can enforce them.

---

## MUST — Assume concurrent requests exist

Patterns like:

```java
if (!repository.exists(...)) {
    repository.save(...);
}
```

are not sufficient protection against races.

Use database constraints and handle constraint violations.

---

# 24. HTTP and External APIs

## MUST — Set timeouts

Every external network call needs bounded:

```text
connection timeout
request timeout
read/response timeout
```

where relevant.

Never rely indefinitely on defaults.

---

## MUST — Retry only retryable failures

Potential candidates:

```text
connection reset
temporary unavailable
HTTP 429
selected HTTP 5xx
```

Do not blindly retry:

```text
validation errors
authentication failures
permanent 4xx responses
non-idempotent operations
```

---

## MUST — Use bounded retries

Use:

```text
maximum attempts
backoff
jitter
```

where appropriate.

Never implement infinite retry loops.

---

## SHOULD — Make retryable operations idempotent

For externally visible writes consider:

```text
idempotency keys
unique operation IDs
deduplication
```

---

# 25. Logging

## MUST — Use structured, meaningful logs

Prefer:

```java
log.info(
        "Payment completed. paymentId={}, orderId={}",
        paymentId,
        orderId
);
```

Avoid:

```java
log.info("Here 1");
log.info("Processing");
```

---

## NEVER — Log secrets

Never log:

```text
passwords
access tokens
refresh tokens
API keys
private keys
session secrets
credit card numbers
```

---

## SHOULD — Include identifiers rather than entire objects

Prefer:

```text
requestId
customerId
orderId
jobId
```

rather than serializing whole domain objects.

---

## AVOID — Logging and throwing the same exception at every layer

This causes duplicate stack traces.

Log primarily at the boundary responsible for handling the failure.

---

# 26. Security

## MUST — Treat external input as untrusted

Validate:

```text
HTTP request data
message payloads
files
database content from external systems
third-party API responses
```

according to the applicable trust boundary.

---

## NEVER — Build SQL by concatenating external values

Never:

```java
String sql = "SELECT * FROM users WHERE id = " + input;
```

Use parameter binding.

---

## NEVER — Store secrets in source code

Bad:

```java
private static final String API_KEY = "abc123";
```

Use external secret management.

---

## MUST — Minimize exposed API surface

Prefer:

```java
private
package-private
protected
public
```

in that order unless wider visibility is required.

Do not make members public by default.

---

# 27. Serialization

## AVOID — Native Java serialization

Avoid:

```java
implements Serializable
```

for application-level persistence or transport unless required by a specific framework or protocol.

Prefer explicit serialization formats.

---

## MUST — Do not deserialize untrusted Java object streams

---

# 28. API Design

## SHOULD — Prefer static factories when they improve semantics

Instead of:

```java
new User(...)
```

consider:

```java
User.registered(...)
User.guest(...)
User.from(...)
```

when creation mode carries meaning.

---

## SHOULD — Use builders for complex construction

Especially when constructors contain many optional parameters.

Example:

```java
HttpRequest request = HttpRequest.builder()
        .url(url)
        .timeout(timeout)
        .header("Authorization", token)
        .build();
```

Do not introduce builders for trivial two-field objects.

---

## SHOULD — Prefer composition over inheritance

Do not use inheritance solely for code reuse.

Ask:

```text
Is this truly an "is-a" relationship?
```

If not, use composition.

---

# 29. Method Parameters

## SHOULD — Avoid long parameter lists

Bad:

```java
createOrder(
    customerId,
    address,
    currency,
    discount,
    campaign,
    source,
    locale,
    timestamp
);
```

Consider:

```java
CreateOrderCommand command
```

when parameters form a conceptual request.

---

# 30. Validation

## MUST — Validate at appropriate boundaries

Examples:

```text
HTTP input
message consumption
file imports
external integrations
domain construction
```

---

## MUST — Preserve domain invariants inside the domain model where possible

Bad:

```java
Money money = new Money(new BigDecimal("-100"));
```

if negative money is invalid for that abstraction.

Prefer:

```java
public Money {
    if (amount.signum() < 0) {
        throw new IllegalArgumentException(
                "Amount must not be negative"
        );
    }
}
```

---

# 31. Testing

## MUST — Test behavior, not implementation details

Prefer:

```java
shouldRejectPaymentWhenBalanceIsInsufficient()
```

instead of tests tightly coupled to private method structure.

---

## MUST — Tests must be deterministic

Avoid dependencies on:

```text
real clock
randomness
network
execution order
shared mutable state
```

unless explicitly controlled.

---

## SHOULD — Inject Clock for time-dependent logic

Prefer:

```java
public TokenService(Clock clock) {
    this.clock = clock;
}
```

instead of repeatedly calling:

```java
Instant.now();
```

inside domain logic.

---

## SHOULD — Prefer realistic collaborators over excessive mocking

Mock boundaries where appropriate.

Do not mock simple value objects or everything in the call graph.

---

# 32. Static Analysis

Generated code SHOULD be compatible with:

```text
javac
Error Prone
Sonar Java
SpotBugs
Checkstyle
```

Treat compiler and static-analysis warnings as design feedback rather than cosmetic noise.

Do not suppress warnings unless:

1. the warning is understood,
2. the code is correct,
3. suppression is narrowly scoped,
4. justification is documented where non-obvious.

Bad:

```java
@SuppressWarnings("all")
```

---

# 33. Formatting

## MUST — Use four spaces for Java block indentation

The project's Java convention is four spaces per block indentation level.
Do not use two-space block indentation, tabs, or mixed indentation in maintained
Java sources. Continuation lines and wrapping follow google-java-format's AOSP
style, configured in Spotless. Match the editor settings to the formatter.

Existing demonstration and labeled fixture sources retain their own formatting
unless explicitly included in a formatting change. Do not interpret whitespace
inside comments, string literals, or text blocks as Java block indentation.

New indentation violations may be reported as category STYLE, risk LOW and
guideline label MINOR, with pinned source and diff evidence. Recommend running
the configured formatter. Do not infer runtime or business failures from spacing.

Formatting should be automated.

Prefer a deterministic formatter such as:

```text
google-java-format
Palantir Java Format
```

Do not spend code review time debating:

```text
line wrapping
spaces
braces
indentation
```

when tooling can decide automatically.

---

# 34. Comments

## SHOULD — Explain why, not what

Bad:

```java
// Increment retry count
retryCount++;
```

Useful:

```java
// First retry is immediate because upstream commonly
// closes idle connections after deployment.
retryCount++;
```

---

## MUST — Keep comments synchronized with code

Outdated comments are worse than no comments.

---

## AVOID — Author/date comments

Do not add:

```java
/**
 * @author ...
 * @date ...
 */
```

solely to track code ownership.

Use version-control history.

This intentionally differs from older enterprise Java conventions.

---

# 35. Lombok

## MAY — Use Lombok selectively

Reasonable:

```text
@Getter
@Builder
@RequiredArgsConstructor
```

depending on team convention.

---

## AVOID — @Data on domain entities by default

`@Data` implicitly creates:

```text
setters
equals
hashCode
toString
```

which may violate domain, persistence, or security semantics.

Be explicit when behavior matters.

---

# 36. toString

## MUST — Do not expose sensitive values

Be careful with generated `toString()` implementations containing:

```text
tokens
passwords
PII
financial information
credentials
```

---

# 37. Caching

## MUST — Define cache semantics explicitly

Before caching, determine:

```text
key
TTL
eviction
consistency
staleness tolerance
failure behavior
```

Do not add caching solely because an operation is expensive.

---

# 38. Distributed Systems

## MUST — Assume requests may be retried

Design write APIs with duplicate execution in mind.

---

## MUST — Do not rely on distributed exactly-once behavior unless the underlying system truly provides the required guarantees

Prefer:

```text
at-least-once delivery
+
idempotent consumer
+
deduplication
```

when applicable.

---

## SHOULD — Use transactional outbox when consistency between DB state and event publication matters

Conceptually:

```text
Database transaction
    ├── update domain state
    └── insert outbox record

Outbox publisher
    ↓
Message broker
```

Avoid naive dual writes:

```text
UPDATE DATABASE
SEND KAFKA MESSAGE
```

without failure recovery.

---

# 39. Kafka / Messaging

## MUST — Consumers tolerate duplicate delivery

Handlers should be idempotent where side effects matter.

---

## MUST — Do not perform unlimited retries inside consumers

Use deliberate retry and dead-letter strategies.

---

## SHOULD — Keep event contracts backward-compatible

Prefer additive evolution.

Avoid renaming or removing fields without migration strategy.

---

# 40. Performance

## MUST — Measure before optimizing

Do not replace readable code with complex code based solely on assumed performance improvements.

---

## SHOULD — Optimize architectural bottlenecks before micro-optimizations

Typical high-impact areas:

```text
database round trips
query plans
network calls
serialization
cache strategy
batching
contention
algorithmic complexity
```

---

# 41. Alibaba-Inspired Rules Worth Keeping

The following principles from enterprise Java guidelines remain useful.

## MUST — Use constants instead of unexplained literal values

Bad:

```java
if (retryCount > 5) {
}
```

Better:

```java
private static final int MAX_RETRY_COUNT = 5;
```

---

## MUST — Use braces even for single-statement control structures

Prefer:

```java
if (valid) {
    execute();
}
```

instead of:

```java
if (valid)
    execute();
```

---

## MUST — Never modify collection structure during foreach iteration

Use iterator operations or collection APIs designed for mutation.

---

## MUST — Define comparison semantics deliberately

Be careful with:

```text
Integer identity
String identity
BigDecimal equality
boxed primitive equality
```

Never compare strings using:

```java
==
```

Use:

```java
Objects.equals(a, b)
```

or:

```java
a.equals(b)
```

where null semantics are known.

---

## MUST — Do not use magic database values

Bad:

```java
if (status == 4) {
}
```

Prefer explicit domain mappings.

---

## SHOULD — Treat exceptions according to their meaning

Do not use exceptions for ordinary control flow.

Bad:

```java
try {
    list.get(index);
} catch (IndexOutOfBoundsException e) {
    return null;
}
```

---

## MUST — Release resources deterministically

Use structured lifecycle management.

---

# 42. Alibaba Rules That Should NOT Be Applied Blindly

Do not inherit older rules without evaluating modern Java behavior.

Examples:

## Do not require author/date tags

Version control provides authoritative history.

---

## Do not state that all threads must come from thread pools

This rule predates modern virtual threads.

For virtual-thread workloads:

```java
Executors.newVirtualThreadPerTaskExecutor()
```

is valid and often preferred.

---

## Do not ban modern language features merely for historical compatibility

Java 21 projects may intentionally use:

```text
records
sealed classes
switch expressions
pattern matching
text blocks
virtual threads
```

when they improve code.

---

# 43. Code Review Mode

When reviewing Java code, classify findings as:

```text
BLOCKER
MAJOR
MINOR
SUGGESTION
```

Use these meanings:

### BLOCKER

Likely to cause:

```text
data corruption
security vulnerability
deadlock
critical race condition
incorrect transactions
severe production failure
```

### MAJOR

Likely bug or serious maintainability issue.

### MINOR

Local quality problem with limited impact.

### SUGGESTION

Optional improvement.

---

# 44. Review Output Format

When reviewing code, respond using:

```text
Finding:
Severity:
Location:
Why it matters:
Recommended change:
Example:
```

Example:

```text
Finding:
External HTTP request executes while a database transaction
is open.

Severity:
MAJOR

Location:
OrderService.placeOrder()

Why it matters:
The transaction may remain open while waiting on an
unbounded external dependency, increasing lock duration
and exhausting the DB connection pool.

Recommended change:
Move the external interaction outside the DB transaction
or redesign the workflow using an outbox/event boundary.

Example:
...
```

---

# 45. Refactoring Rules

When asked to refactor:

1. Preserve externally observable behavior unless explicitly asked otherwise.
2. Fix correctness issues before stylistic issues.
3. Avoid introducing abstractions without demonstrated value.
4. Prefer small cohesive methods.
5. Preserve useful domain terminology.
6. Remove duplication only when duplicated logic represents the same concept.
7. Do not create generic utility layers merely to reduce repeated lines.
8. Keep API contracts backward-compatible unless breaking changes are allowed.
9. Update tests when behavior or contracts change.
10. Explain meaningful architectural tradeoffs.

---

# 46. Agent Decision Heuristics

Before generating code, ask internally:

```text
What are the invariants?

Who owns this mutable state?

Can this value be immutable?

What happens if this executes twice?

What happens if two requests execute concurrently?

What happens if the dependency times out?

What happens if the process crashes between operations?

What transaction owns this operation?

Could this return null?

Could this expose sensitive information?

Could this operation be retried?

Could this collection be modified concurrently?

Is the abstraction simpler than the code it replaces?
```

---

# 47. Avoid Premature Abstractions

Do not automatically generate:

```text
AbstractBaseService
BaseRepository
GenericManager
CommonHelper
GenericProcessor<T>
StrategyFactory
BuilderFactory
HandlerFactory
```

without a demonstrated need.

Three clear implementations are often better than one premature framework.

---

# 48. Dependency Rules

Prefer architecture where dependencies point inward:

```text
Controller
    ↓
Application Service
    ↓
Domain
    ↓
Ports
    ↑
Infrastructure adapters
```

Domain logic should not unnecessarily depend on:

```text
HTTP
Spring MVC
JPA
Kafka
AWS SDK
serialization frameworks
```

---

# 49. DTO / Domain / Persistence Separation

Do not assume a single model should represent:

```text
HTTP request
domain entity
database entity
Kafka event
HTTP response
```

Separate these when their lifecycle or contracts differ.

Example:

```text
CreateCustomerRequest
        ↓
CreateCustomerCommand
        ↓
Customer
        ↓
CustomerEntity
```

Use pragmatism: do not create mapping layers when they add no meaningful boundary.

---

# 50. Final Agent Principle

When uncertain between:

```text
clever vs obvious
generic vs explicit
mutable vs immutable
implicit vs explicit
magic vs named
large abstraction vs small cohesive code
```

prefer:

```text
obvious
explicit
immutable
named
small
```

unless concrete requirements justify otherwise.

---

# Automated Tooling Recommendation

A Java project using this skill SHOULD consider:

```text
Formatter
    ↓
google-java-format
or
palantir-java-format

Compilation
    ↓
javac

Correctness Analysis
    ↓
Error Prone

Static Analysis
    ↓
Sonar Java
SpotBugs

Style / Structural Checks
    ↓
Checkstyle

Tests
    ↓
JUnit 5
AssertJ
Testcontainers where useful
```

Suggested CI order:

```text
compile
   ↓
format verification
   ↓
static analysis
   ↓
unit tests
   ↓
integration tests
   ↓
package
```

---

# References

Use these as conceptual authorities when evaluating Java code:

```text
Effective Java — Joshua Bloch

Google Java Style Guide
https://google.github.io/styleguide/javaguide.html

Google Error Prone
https://errorprone.info/

SonarSource Java Rules
https://rules.sonarsource.com/java/

Modern Java documentation
https://dev.java/

Oracle Secure Coding Guidelines for Java SE
https://www.oracle.com/java/technologies/javase/seccodeguide.html

SEI CERT Oracle Coding Standard for Java
https://wiki.sei.cmu.edu/confluence/display/java

Alibaba Java Coding Guidelines / P3C
https://github.com/alibaba/p3c
```

Treat these sources as guidance rather than blindly applying every historical rule.

Modern Java language/runtime behavior and project-specific architectural requirements take precedence over outdated conventions.

---

# Coding Agent Summary

When generating Java code:

```text
Use modern Java 21+.

Prefer immutable data.

Use records for suitable value/data carriers.

Use constructor injection.

Keep controllers thin.

Make transaction boundaries explicit.

Do not hide null semantics.

Use Optional primarily as return values.

Never swallow exceptions.

Use try-with-resources.

Use BigDecimal for money.

Use java.time.

Avoid uncontrolled shared mutable state.

Use virtual threads for appropriate blocking I/O workloads.

Do not pool virtual threads.

Avoid N+1 queries.

Set HTTP timeouts.

Retry only safely retryable operations.

Design retries around idempotency.

Assume message duplication.

Avoid distributed dual writes.

Prefer outbox when DB + event consistency matters.

Never log secrets.

Use database constraints for critical invariants.

Prefer composition over inheritance.

Avoid premature frameworks and abstractions.

Make code obvious before making it clever.
```

When reviewing code, prioritize:

```text
Correctness
→ Security
→ Concurrency
→ Transactions
→ Data integrity
→ Failure behavior
→ API design
→ Maintainability
→ Performance
→ Style
```

Do not spend human review effort on formatting problems that automated tooling can solve.
