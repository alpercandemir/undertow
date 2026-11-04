package dev.undertow.demo;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoPaymentGateway {
  @Bean
  PaymentGatewayAdapter paymentGatewayAdapter() {
    var accepted = new ConcurrentHashMap<String, Money>();
    return new PaymentGatewayAdapter(
        (key, money) -> {
          if (money.amount().signum() == 0) {
            return false;
          }
          Money prior = accepted.putIfAbsent(key, money);
          if (prior != null && !prior.equals(money)) {
            throw new IllegalArgumentException("Idempotency key reused with a different amount");
          }
          return true;
        });
  }
}
