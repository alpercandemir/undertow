package example.refunds;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proposed behavior for the extraction, separate from established contracts. */
class PartialRefundContractTest {
    @Test
    @DisplayName("A positive partial refund within the remaining budget is accepted.")
    void acceptsPartialRefund() {
        var service = new RefundService(10000);
        assertDoesNotThrow(() -> service.refund("r-1", 2500));
        assertEquals(2500, service.refunded());
    }

    @Test
    @DisplayName("Partial refunds share the payment's remaining refund budget.")
    void partialRefundsShareOneBudget() {
        var service = new RefundService(10000);
        assertDoesNotThrow(() -> service.refund("r-1", 8000));
        assertThrows(IllegalArgumentException.class, () -> service.refund("r-2", 3000));
        assertEquals(8000, service.refunded());
    }
}
