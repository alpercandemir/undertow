package example.refunds;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExistingRefundContractTest {
    @Test
    @DisplayName("A refund larger than the captured payment is rejected.")
    void rejectsRefundAboveCapture() {
        var service = new RefundService(10000);
        assertThrows(IllegalArgumentException.class, () -> service.refund("r-1", 10001));
        assertEquals(0, service.refunded());
    }

    @Test
    @DisplayName("Total settled refunds must not exceed the captured payment.")
    void rejectsASecondFullRefund() {
        var service = new RefundService(10000);
        service.refund("r-1", 10000);
        assertThrows(IllegalArgumentException.class, () -> service.refund("r-2", 10000));
        assertEquals(10000, service.refunded());
    }

    @Test
    @DisplayName("Retrying the same refund operation produces one settled effect.")
    void retryDoesNotRefundTwice() {
        var service = new RefundService(10000);
        service.refund("r-1", 10000);
        service.refund("r-1", 10000);
        assertEquals(10000, service.refunded());
        assertEquals(1, service.effects());
    }

    @Test
    @DisplayName("A refund operation key cannot be reused with another amount.")
    void operationKeyCannotChangeAmount() {
        var service = new RefundService(10000);
        service.refund("r-1", 10000);
        assertThrows(IllegalArgumentException.class, () -> service.refund("r-1", 2500));
        assertEquals(10000, service.refunded());
    }
}
