package dev.undertow.demo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderWriter {
  private final OrderRepository orders;

  public OrderWriter(OrderRepository orders) {
    this.orders = orders;
  }

  @Transactional
  public void persist(String key, Money money) {
    orders.save(key, money);
    orders.recordEvent(key);
  }
}
