package dev.undertow.demo;

public interface PaymentGateway {
  /** A stable key denotes one payment; reusing a key with a different amount is rejected. */
  boolean charge(String operationKey, Money money);

  final class LostResponse extends RuntimeException {
    public LostResponse() {
      super("Payment outcome is ambiguous; response lost");
    }
  }
}
