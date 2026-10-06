package dev.undertow.demo;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentBehaviorTest {
  @Test
  void lostResponseRetriesTheSameLogicalPayment() {
    Set<String> effects = new HashSet<>();
    List<String> keys = new ArrayList<>();
    PaymentGateway gateway =
        (key, money) -> {
          keys.add(key);
          if (effects.add(key)) {
            throw new PaymentGateway.LostResponse();
          }
          return true;
        };
    assertThat(
            new PaymentGatewayAdapter(gateway)
                .charge("operation-42", new Money(new BigDecimal("1.00"))))
        .isTrue();
    assertThat(keys).containsExactly("operation-42", "operation-42");
    assertThat(effects).hasSize(1);
  }

  @Test
  void rejectedPaymentIsNotRetried() {
    List<String> keys = new ArrayList<>();
    var adapter =
        new PaymentGatewayAdapter(
            (key, money) -> {
              keys.add(key);
              return false;
            });
    assertThat(adapter.charge("rejected", new Money(BigDecimal.ZERO))).isFalse();
    assertThat(keys).hasSize(1);
  }

  @Test
  void secondLostResponseIsSurfaced() {
    var adapter =
        new PaymentGatewayAdapter(
            (key, money) -> {
              throw new PaymentGateway.LostResponse();
            });
    assertThatThrownBy(() -> adapter.charge("ambiguous", new Money(BigDecimal.ONE)))
        .isInstanceOf(PaymentGateway.LostResponse.class);
  }

  @Test
  void decimalBoundariesAreExplicit() {
    assertThat(new Money(new BigDecimal("1.005")).amount()).isEqualByComparingTo("1.01");
    assertThat(new Money(new BigDecimal("1.0"))).isEqualTo(new Money(new BigDecimal("1.00")));
    assertThatThrownBy(() -> new Money(new BigDecimal("-0.001")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
