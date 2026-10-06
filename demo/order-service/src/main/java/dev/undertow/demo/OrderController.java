package dev.undertow.demo;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  public record Request(String operationKey, BigDecimal amount) {}

  @PostMapping("/orders")
  public OrderService.Result place(@RequestBody Request request) {
    return service.place(request.operationKey(), new Money(request.amount()));
  }
}
