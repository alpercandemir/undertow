package experiment.refunds;

import example.refunds.RefundService;

/** Observes the subject's public state; never calculates an expected balance. */
public final class RefundAdapter implements ExecutionAdapter {
    private final RefundService service;

    public RefundAdapter(long captured) {
        if (captured < 2 || captured > 10000) {
            throw new IllegalArgumentException("Capture outside scenario range");
        }
        service = new RefundService(captured);
    }

    public String apply(int step, long amount) {
        try {
            service.refund("search-" + step, amount);
            return "accepted";
        } catch (IllegalArgumentException rejected) {
            return "rejected";
        }
    }

    public long refunded() {
        return service.refunded();
    }

    @Override
    public String apply(String operation, int step) {
        if (!operation.startsWith("F:")) {
            return applyBounded(step, Long.parseLong(operation));
        }
        String[] fields = operation.split(":", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Invalid refund operation");
        }
        int key = Integer.parseInt(fields[1]);
        if (key < 1 || key > 16) {
            throw new IllegalArgumentException("Key outside scenario range");
        }
        return applyBounded(key, Long.parseLong(fields[2]));
    }

    private String applyBounded(int key, long amount) {
        if (amount < -1 || amount > 10001) {
            throw new IllegalArgumentException("Amount outside scenario range");
        }
        return apply(key, amount);
    }

    @Override
    public String observe() {
        return refunded() + "," + effects();
    }

    public int effects() {
        return service.effects();
    }
}
