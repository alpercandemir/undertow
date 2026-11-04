package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderServiceTest {
    @Test
    void separateTenantsMayUseTheSameOrderId() {
        var service = new OrderService();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var id = UUID.randomUUID();
        service.save(new OrderService.Order(first, id, "first-key", new BigDecimal("10.00")));
        service.save(new OrderService.Order(second, id, "second-key", new BigDecimal("20.00")));
        assertEquals("first-key", service.read(first, id).operationKey());
        assertEquals("second-key", service.read(second, id).operationKey());
        assertThrows(IllegalArgumentException.class, () -> service.read(UUID.randomUUID(), id));
    }

    @Test
    void retryAfterLostResponseUsesTheSamePaymentIdentity() {
        var service = new OrderService();
        var tenant = UUID.randomUUID();
        var id = UUID.randomUUID();
        service.save(new OrderService.Order(tenant, id, "order-key", new BigDecimal("10.00")));
        var calls = new ArrayList<String>();
        OrderService.PaymentGateway gateway = (key, amount) -> {
            calls.add(key);
            if (calls.size() == 1) {
                throw new IllegalStateException("Response lost after charge");
            }
        };
        assertThrows(IllegalStateException.class, () -> service.pay(tenant, id, gateway));
        service.pay(tenant, id, gateway);
        assertEquals(java.util.List.of("order-key", "order-key"), calls);
    }

    @Test
    void tenantCannotTriggerAnotherTenantsPayment() {
        var service = new OrderService();
        var owner = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var id = UUID.randomUUID();
        service.save(new OrderService.Order(owner, id, "private-payment", BigDecimal.TEN));
        var effects = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> service.pay(stranger, id, (key, amount) -> effects.add(key)));
        assertEquals(java.util.List.of(), effects);
    }

    @Test
    void tenantLookupRemainsScopedAcrossManySharedIdentifiers() {
        var service = new OrderService();
        var orderId = new UUID(0, 42);
        for (int tenant = 1; tenant <= 32; tenant++) {
            service.save(new OrderService.Order(new UUID(0, tenant), orderId, "payment-" + tenant, BigDecimal.valueOf(tenant)));
        }
        for (int tenant = 1; tenant <= 32; tenant++) {
            assertEquals("payment-" + tenant, service.read(new UUID(0, tenant), orderId).operationKey());
        }
        assertThrows(IllegalArgumentException.class, () -> service.read(new UUID(0, 1000), orderId));
    }

    @Test
    void publishedOrderApiRetainsItsDeclaredShape() throws Exception {
        assertEquals(OrderService.Order.class, OrderService.class.getMethod("read", UUID.class, UUID.class).getReturnType());
        assertEquals(void.class, OrderService.class.getMethod("pay", UUID.class, UUID.class, OrderService.PaymentGateway.class).getReturnType());
        assertEquals(java.util.List.of("tenant", "id", "operationKey", "amount"),
                java.util.Arrays.stream(OrderService.Order.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }
}
