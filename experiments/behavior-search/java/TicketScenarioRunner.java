package experiment.refunds;

import java.io.IOException;

/** Explicit entry point for the owned ticket-transfer fixture. */
public final class TicketScenarioRunner {
    private TicketScenarioRunner() {}

    public static void main(String[] args) throws IOException {
        ScenarioProtocol.run(TicketAdapter::new);
    }
}
