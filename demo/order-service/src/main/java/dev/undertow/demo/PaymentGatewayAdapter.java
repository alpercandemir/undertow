package dev.undertow.demo;

public final class PaymentGatewayAdapter {
  private final PaymentGateway gateway;

  public PaymentGatewayAdapter(PaymentGateway gateway) {
    this.gateway = gateway;
  }

  public boolean charge(String operationKey, Money money) {
    try {
      return gateway.charge(operationKey, money);
    } catch (PaymentGateway.LostResponse e) {
      // One bounded retry of an ambiguous write preserves the logical operation identity.
      return gateway.charge(operationKey, money);
    }
  }
}
