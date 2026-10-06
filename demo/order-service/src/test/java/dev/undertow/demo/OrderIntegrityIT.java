package dev.undertow.demo;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
class OrderIntegrityIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.8-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired OrderService service;
  @Autowired OrderWriter writer;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.execute("truncate order_events, orders");
  }

  @Test
  void rejectedPaymentCannotCreateSuccessfulOrder() {
    assertThat(service.place("zero", new Money(BigDecimal.ZERO)))
        .isEqualTo(OrderService.Result.REJECTED);
    assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
  }

  @Test
  void failedSecondWriteRollsBackThroughTheRealProxy() {
    jdbc.execute(
        "alter table order_events add constraint reject_failed check (operation_key <> 'failed')");
    try {
      assertThatThrownBy(() -> writer.persist("failed", new Money(BigDecimal.ONE)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from orders where operation_key='failed'", Integer.class))
          .isZero();
    } finally {
      jdbc.execute("alter table order_events drop constraint reject_failed");
    }
  }

  @Test
  void concurrentRequestsCommitOnlyOneOperation() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<OrderService.Result> request =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return service.place("concurrent", new Money(new BigDecimal("10.00")));
          };
      var first = executor.submit(request);
      var second = executor.submit(request);
      assertThat(
              java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(OrderService.Result.PLACED, OrderService.Result.DUPLICATE);
      assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from order_events", Integer.class))
          .isEqualTo(1);
    }
  }
}
