package dev.undertow.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount) {
  public Money {
    if (amount == null || amount.signum() < 0) {
      throw new IllegalArgumentException("Amount must be nonnegative");
    }
    amount = amount.setScale(2, RoundingMode.HALF_UP);
  }
}
