package dev.undertow.demo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
  private final PaymentGatewayAdapter gateway;
  private final OrderWriter writer;
  private final OrderRepository orders;

  public OrderService(PaymentGatewayAdapter gateway, OrderWriter writer, OrderRepository orders) {
    this.gateway = gateway;
    this.writer = writer;
    this.orders = orders;
  }

  public Result place(String operationKey, Money money) {
    if (operationKey == null || !operationKey.matches("[A-Za-z0-9_-]{1,100}")) {
      throw new IllegalArgumentException("Invalid operation key");
    }
    var existing = orders.find(operationKey);
    if (existing.isPresent()) {
      if (!existing.orElseThrow().equals(money)) {
        throw new IllegalArgumentException("Key reused with a different amount");
      }
      return Result.DUPLICATE;
    }
    if (!gateway.charge(operationKey, money)) {
      return Result.REJECTED;
    }
    try {
      writer.persist(operationKey, money);
      return Result.PLACED;
    } catch (DuplicateKeyException e) {
      return Result.DUPLICATE;
    }
  }

  public enum Result {
    PLACED,
    DUPLICATE,
    REJECTED
  }
}
