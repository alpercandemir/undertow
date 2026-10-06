package fixtures;
// Public demo contract: stable operation keys deduplicate charges; HALF_UP cents; no secrets in logs.
// OrderWriter is a separate Spring proxy. place() is not transactional. persist writes order then audit.
class Subject {
  boolean place(String operationKey, Money money) {
    return gateway.charge(operationKey, money);
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
