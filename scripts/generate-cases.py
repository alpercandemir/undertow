#!/usr/bin/env python3
"""Author small, independent synthetic changes. Labels are never given to a live reviewer."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
# name, rule, risk, category, before body, after body, trigger, consequence, regression
PAIRS=[
 ('retry-key','JAVA-RETRY-001','CRITICAL','DATA_INTEGRITY',
  'return gateway.charge(operationKey, money);','return gateway.charge(java.util.UUID.randomUUID().toString(), money);',
  'Gateway commits a payment, loses the response and the caller retries.', 'A new key represents a new charge and can duplicate customer debit.', 'Drop the first response; assert equal keys and one charge.'),
 ('retry-loop-key','JAVA-RETRY-001','CRITICAL','DATA_INTEGRITY',
  'for (int i=0;i<2;i++) { gateway.charge(operationKey,money); }','for (int i=0;i<2;i++) { gateway.charge("attempt-"+i,money); }',
  'Two attempts reach a gateway that deduplicates only by operation key.', 'Each attempt is a distinct payment, violating at-most-one charge.', 'Assert both attempts use the caller key and a single debit.'),
 ('retry-scope-key','JAVA-RETRY-001','CRITICAL','DATA_INTEGRITY',
  'String key=operationKey; return retry(()->gateway.charge(key,money));','return retry(()->gateway.charge(java.util.UUID.randomUUID().toString(),money));',
  'Retry callback runs after an accepted charge with a lost response.', 'Key generation inside the callback bypasses payment deduplication.', 'Execute callback twice after an ambiguous response and count debits.'),
 ('money-equality','JAVA-MONEY-001','HIGH','CORRECTNESS',
  'return amount.compareTo(expected)==0;','return amount.equals(expected);',
  'A supported amount 1.0 is compared to the expected price 1.00.', 'Scale changes equality and rejects an otherwise valid order price.', 'Compare 1.0 and 1.00; expect numeric equality.'),
 ('money-double','JAVA-MONEY-001','HIGH','DATA_INTEGRITY',
  'return new java.math.BigDecimal("0.1");','return new java.math.BigDecimal(0.1);',
  'Price is constructed from a binary floating-point literal.', 'The persisted or compared monetary value contains an unintended binary conversion tail.', 'Construct 0.1 and assert exact decimal 0.10 after explicit rounding.'),
 ('money-rounding','JAVA-MONEY-001','HIGH','CORRECTNESS',
  'return amount.setScale(2,java.math.RoundingMode.HALF_UP);','return amount.setScale(2,java.math.RoundingMode.DOWN);',
  'A documented HALF_UP price such as 1.005 reaches checkout.', 'The new rounding produces 1.00 instead of 1.01 and undercharges.', 'Use 1.005 and 1.015; expect documented HALF_UP cents.'),
 ('money-division','JAVA-MONEY-001','HIGH','RELIABILITY',
  'return amount.divide(java.math.BigDecimal.valueOf(3),2,java.math.RoundingMode.HALF_UP);','return amount.divide(java.math.BigDecimal.valueOf(3));',
  'A payment of 1.00 is split into three parts.', 'Nonterminating decimal expansion throws instead of producing the documented rounded result.', 'Split 1.00 into thirds and assert explicit scale and rounding.'),
 ('transaction-this','JAVA-TX-001','HIGH','TRANSACTIONS',
  'writer.persist(order);','this.persist(order);',
  'A nontransactional entry point invokes an annotated helper and the second database write fails.', 'Self-invocation bypasses the proxy and leaves the first write committed.', 'Invoke the Spring proxy entry point, fail the second write and assert no partial commit.'),
 ('transaction-unqualified','JAVA-TX-001','HIGH','TRANSACTIONS',
  'writer.persist(order);','persist(order);',
  'An unqualified same-instance helper call follows a nontransactional entry point.', 'Calling the annotated method on the same bean bypasses transaction interception.', 'Fail after the first write through the real proxied entry point and assert rollback.'),
 ('transaction-private','JAVA-TX-001','HIGH','TRANSACTIONS',
  'writer.persist(order);','privatePersist(order);',
  'Private annotated helper is called from the nontransactional use case.', 'The private helper cannot establish the intended proxy transaction.', 'Verify both writes roll back on second-write failure.'),
 ('log-api-key','JAVA-LOG-001','HIGH','SECURITY',
  'log.info("Payment completed: {}",operationKey);','log.info("Gateway API key: {}",apiKey);',
  'Payment handling writes logs containing the configured gateway credential.', 'A reader of operational logs can obtain the API key.', 'Capture logs with a sentinel key and assert it never appears.'),
 ('log-card','JAVA-LOG-001','HIGH','SECURITY',
  'log.info("Payment completed: {}",operationKey);','log.info("Card: {}",cardNumber);',
  'The adapter processes a customer card number.', 'Raw payment credentials are retained in logs.', 'Capture logs and assert the card number is absent.'),
 ('log-token','JAVA-LOG-001','HIGH','SECURITY',
  'log.info("Payment completed: {}",operationKey);','log.info("Token: {}",accessToken);',
  'Authenticated payment handling emits the access token.', 'Operational logs expose a reusable bearer credential.', 'Capture logs containing a sentinel token and verify redaction.'),
 ('http-timeout','JAVA-HTTP-001','HIGH','RELIABILITY',
  'return java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).build();','return java.net.http.HttpRequest.newBuilder(uri).build();',
  'Upstream accepts a connection but never returns a response.', 'Request has no explicit response deadline and can hold work indefinitely.', 'Stall a controlled upstream and assert the operation terminates within the deadline.'),
 ('http-connect-timeout','JAVA-HTTP-001','HIGH','RELIABILITY',
  'return java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();','return java.net.http.HttpClient.newBuilder().build();',
  'A new connection to the payment upstream cannot complete.', 'Removing the explicit connection budget makes completion depend on unspecified defaults.', 'Exercise a controlled connection stall and assert the configured bound.'),
]
for i,(name,rule,risk,category,before,after,trigger,consequence,regression) in enumerate(PAIRS):
 for unsafe in (True,False):
  case_id=name+('-unsafe' if unsafe else '-corrected')
  # Corrected cases reverse the harmful edit, testing restraint as well as detection.
  a,b=(before,after) if unsafe else (after,before)
  if name.startswith('retry'):
   declaration='boolean place(String operationKey, Money money)'
  elif name=='money-equality':declaration='boolean place(java.math.BigDecimal amount, java.math.BigDecimal expected)'
  elif name=='money-double':declaration='java.math.BigDecimal place()'
  elif name.startswith('money'):declaration='java.math.BigDecimal place(java.math.BigDecimal amount)'
  elif name.startswith('transaction'):declaration='void place(Object order)'
  elif name.startswith('log'):declaration='void place(String operationKey, String apiKey, String cardNumber, String accessToken)'
  elif name=='http-timeout':declaration='java.net.http.HttpRequest place(java.net.URI uri)'
  else:declaration='java.net.http.HttpClient place()'
  common="""package fixtures;
// Public demo contract: stable operation keys deduplicate charges; HALF_UP cents; no secrets in logs.
// OrderWriter is a separate Spring proxy. place() is not transactional. persist writes order then audit.
class Subject {
  DECLARATION {
    BODY
  }
  Gateway gateway; Writer writer; Repository repository; Log log;
  record Money(java.math.BigDecimal amount) {}
  interface Gateway { boolean charge(String key, Money money); }
  interface Writer { void persist(Object order); }
  interface Repository { void save(Object order); void audit(Object order); }
  interface Log { void info(String format, Object value); }
  boolean retry(java.util.function.BooleanSupplier callback) { try { return callback.getAsBoolean(); } catch (IllegalStateException e) { return callback.getAsBoolean(); } }
  @org.springframework.transaction.annotation.Transactional
  void persist(Object order) { repository.save(order); repository.audit(order); }
  @org.springframework.transaction.annotation.Transactional
  private void privatePersist(Object order) { repository.save(order); repository.audit(order); }
}
""".replace('DECLARATION',declaration)
  if name=='retry-loop-key':
   a+=' return true;';b+=' return true;'
  path=ROOT/'evals/cases'/case_id;path.mkdir(parents=True,exist_ok=True)
  (path/'before.java').write_text(common.replace('BODY',a))
  (path/'after.java').write_text(common.replace('BODY',b))
  label={'id':case_id,'split':'tuning' if i<5 else 'heldout','unsafe':unsafe,'language':'java','rule_id':rule,
   'severity':risk,'category':category,'trigger':trigger,'consequence':consequence,'regression':regression,
   'kind':'synthetic source fixture; collaborator behavior declared in contract; not a production service'}
  (ROOT/'evals/expected'/f'{case_id}.json').write_text(json.dumps(label,indent=2)+'\n')
# Additional benign modern Java cases and dependency inventory/unsupported evidence cases are test resources.
EXTRA=[
 ('database-key-removal',True,'JAVA-DATA-001','CRITICAL','CONCURRENCY','db/migration/V1__orders.sql',
  'create table orders (\n  operation_key varchar(100) primary key,\n  amount numeric(19,2) not null\n);\n',
  'create table orders (\n  operation_key varchar(100),\n  amount numeric(19,2) not null\n);\n',2),
 ('database-key-restored',False,'JAVA-DATA-001','CRITICAL','CONCURRENCY','db/migration/V1__orders.sql',
  'create table orders (\n  operation_key varchar(100),\n  amount numeric(19,2) not null\n);\n',
  'create table orders (\n  operation_key varchar(100) primary key,\n  amount numeric(19,2) not null\n);\n',2),
 ('database-unique-removal',True,'JAVA-DATA-001','CRITICAL','DATA_INTEGRITY','db/migration/V1__orders.sql',
  'create table orders (\n  id bigint primary key,\n  operation_key varchar(100) not null unique,\n  amount numeric(19,2) not null\n);\n',
  'create table orders (\n  id bigint primary key,\n  operation_key varchar(100) not null,\n  amount numeric(19,2) not null\n);\n',3),
 ('database-unique-restored',False,'JAVA-DATA-001','CRITICAL','DATA_INTEGRITY','db/migration/V1__orders.sql',
  'create table orders (\n  id bigint primary key,\n  operation_key varchar(100) not null,\n  amount numeric(19,2) not null\n);\n',
  'create table orders (\n  id bigint primary key,\n  operation_key varchar(100) not null unique,\n  amount numeric(19,2) not null\n);\n',3),
 ('record-benign',False,'JAVA-MODERN-001','LOW','STYLE','src/Subject.java',
  'class Subject {\n  private final int value;\n  Subject(int value) { this.value=value; }\n  int value() { return value; }\n}\n',
  'record Subject(int value) {}\n',1),
 ('switch-benign',False,'JAVA-MODERN-001','LOW','STYLE','src/Subject.java',
  'class Subject { int price(int tier) { switch(tier) { case 1:return 10; default:return 20; } } }\n',
  'class Subject { int price(int tier) { return switch(tier) { case 1 -> 10; default -> 20; }; } }\n',1),
 ('virtual-thread-benign',False,'JAVA-MODERN-001','LOW','STYLE','src/Subject.java',
  'class Subject { void io(Runnable request) { try(var e=java.util.concurrent.Executors.newCachedThreadPool()) { e.submit(request); } } }\n',
  'class Subject { void io(Runnable request) { try(var e=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) { e.submit(request); } } }\n',1),
 ('rename-benign',False,'JAVA-MODERN-001','LOW','STYLE','src/Subject.java',
  'class Subject { int read() { return amount(); } int amount() { return 1; } }\n',
  'class Subject { int read() { return price(); } int price() { return 1; } }\n',1),
 ('dependency-major-inventory',False,'JAVA-DEPENDENCY-001','MEDIUM','DEPENDENCY_COMPATIBILITY','pom.xml',
  '<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.18.3</version></dependency></dependencies></project>\n',
  '<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>3.0.0</version></dependency></dependencies></project>\n',1),
 ('dependency-property-inventory',False,'JAVA-DEPENDENCY-001','MEDIUM','DEPENDENCY_COMPATIBILITY','pom.xml',
  '<project><properties><jackson.version>2.17.2</jackson.version></properties><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency></dependencies></project>\n',
  '<project><properties><jackson.version>2.18.3</jackson.version></properties><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency></dependencies></project>\n',1),
]
for name,unsafe,rule,risk,category,source_path,before,after,line in EXTRA:
 path=ROOT/'evals/cases'/name;path.mkdir(parents=True,exist_ok=True)
 suffix=Path(source_path).suffix
 (path/('before'+suffix)).write_text(before);(path/('after'+suffix)).write_text(after)
 label=dict(id=name,split='heldout',unsafe=unsafe,language='java',rule_id=rule,severity=risk,category=category,source_path=source_path,finding_line=line,
  trigger='Concurrent requests reuse an operation key without a database uniqueness boundary.' if unsafe else 'Apply a benign refactor or investigate a dependency version change.',
  consequence='Duplicate operation records can commit, violating the declared payment operation invariant.' if unsafe else 'No verified regression is asserted.',
  regression='Synchronize two inserts with the same key and assert only one commits.' if unsafe else 'Preserve the existing behavior; do not invent migration claims.',
  kind='synthetic schema/Java/dependency fixture; dependency versions are investigation inputs, not migration claims')
 (ROOT/'evals/expected'/f'{name}.json').write_text(json.dumps(label,indent=2)+'\n')
