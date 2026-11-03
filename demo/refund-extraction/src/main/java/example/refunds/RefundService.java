package example.refunds;

import java.util.HashMap;
import java.util.Map;

/** One payment, one currency, sequential calls. No real payment provider. */
public final class RefundService {
    private final long captured;
    private final Map<String, Long> settled = new HashMap<>();
    private long refunded;

    public RefundService(long captured) {
        if (captured <= 0) throw new IllegalArgumentException("Positive capture required");
        this.captured = captured;
    }

    public void refund(String operationKey, long amount) {
        if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("Key required");
        if (settled.containsKey(operationKey)) {
            if (settled.get(operationKey) != amount) throw new IllegalArgumentException("Key reused with another amount");
            return;
        }
        if (amount <= 0 || amount > captured - refunded) throw new IllegalArgumentException("Refund exceeds available capture");
        settled.put(operationKey, amount);
        refunded += amount;
    }

    public long refunded() { return refunded; }
    public int effects() { return settled.size(); }
}
