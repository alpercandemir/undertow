package example;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Customer application code. Reviews are supplied by an installed GitHub App. */
public final class OrderService {
    public record Order(UUID tenant, UUID id, String operationKey, BigDecimal amount) {
        public Order {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(id);
            if (operationKey == null || operationKey.isBlank() || amount.signum() <= 0) {
                throw new IllegalArgumentException("Invalid order");
            }
        }
    }

    @FunctionalInterface
    public interface PaymentGateway {
        void charge(String operationKey, BigDecimal amount);
    }

    private record ScopedId(UUID tenant, UUID order) {}

    private final Map<ScopedId, Order> orders = new ConcurrentHashMap<>();

    public void save(Order order) {
        orders.put(new ScopedId(order.tenant(), order.id()), order);
    }

    public Order read(UUID authenticatedTenant, UUID orderId) {
        Order result = orders.get(new ScopedId(authenticatedTenant, orderId));
        if (result == null) {
            throw new IllegalArgumentException("Order not found");
        }
        return result;
    }

    public void pay(UUID authenticatedTenant, UUID orderId, PaymentGateway gateway) {
        Order order = read(authenticatedTenant, orderId);
        gateway.charge(order.operationKey(), order.amount());
    }
}
