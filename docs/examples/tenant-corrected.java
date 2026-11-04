// Authenticated tenant IDs are trusted inputs; order IDs are attacker-controlled.
class TenantOrders {
    private final Orders orders;
    TenantOrders(Orders orders) { this.orders = orders; }
    Order read(String tenantId, String orderId) {
        return orders.find(tenantId, orderId); // Repository enforces the tenant predicate.
    }
}
