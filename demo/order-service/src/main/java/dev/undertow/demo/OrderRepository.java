package dev.undertow.demo;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
  private final JdbcTemplate jdbc;

  public OrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Money> find(String key) {
    return jdbc
        .query(
            "select amount from orders where operation_key = ?",
            (rs, row) -> new Money(rs.getBigDecimal(1)),
            key)
        .stream()
        .findFirst();
  }

  public void save(String key, Money amount) {
    jdbc.update(
        "insert into orders(operation_key, amount, status) values (?, ?, 'SUCCESS')",
        key,
        amount.amount());
  }

  public void recordEvent(String key) {
    jdbc.update("insert into order_events(operation_key, event_type) values (?, 'PLACED')", key);
  }
}
