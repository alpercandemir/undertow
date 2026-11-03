package example.tickets;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TransferContractTest {
    @Test
    void linkPreviewDoesNotAcceptTransfer() {
        var transfer = new TicketTransfer("mira", "leo");
        var preview = transfer.preview(); // A chat unfurler opens the link first.
        assertEquals("NIGHT / SHIFT", preview.event());
        assertEquals("mira", transfer.owner(), "A preview must not transfer the ticket");
        assertFalse(transfer.accepted(), "A preview must leave the invitation usable");
        assertDoesNotThrow(() -> transfer.accept("leo"));
        assertEquals("leo", transfer.owner());
        assertTrue(transfer.accepted());
    }

    @Test
    void anotherAccountCannotAcceptTransfer() {
        var transfer = new TicketTransfer("mira", "leo");
        assertThrows(SecurityException.class, () -> transfer.accept("alex"));
        assertEquals("mira", transfer.owner());
        assertFalse(transfer.accepted());
    }
}
