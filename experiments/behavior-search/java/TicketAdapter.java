package experiment.refunds;

import example.tickets.TicketTransfer;

/** Reads the actual ticket owner and invitation state, without an expected-state ledger. */
public final class TicketAdapter implements ExecutionAdapter {
    private final TicketTransfer transfer;

    public TicketAdapter(long initial) {
        if (initial != 0) {
            throw new IllegalArgumentException("Ticket initial sentinel must be zero");
        }
        transfer = new TicketTransfer("sender", "recipient");
    }

    @Override
    public String apply(String operation, int step) {
        if (operation.equals("P")) {
            transfer.preview();
            return "accepted";
        }
        if (!operation.startsWith("A:")) {
            throw new IllegalArgumentException("Unknown ticket operation");
        }
        String actor = operation.substring(2);
        if (!actor.equals("recipient") && !actor.equals("sender") && !actor.equals("other")) {
            throw new IllegalArgumentException("Unknown synthetic actor");
        }
        boolean previouslyAccepted = transfer.accepted();
        try {
            transfer.accept(actor);
            return "accepted";
        } catch (SecurityException rejected) {
            if (actor.equals("recipient")) {
                throw rejected;
            }
            return "rejected";
        } catch (IllegalStateException rejected) {
            if (!previouslyAccepted) {
                throw rejected;
            }
            return "rejected";
        }
    }

    @Override
    public String observe() {
        int owner =
                switch (transfer.owner()) {
                    case "sender" -> 0;
                    case "recipient" -> 1;
                    case "other" -> 2;
                    default -> 3;
                };
        return owner + "," + (transfer.accepted() ? 1 : 0);
    }
}
