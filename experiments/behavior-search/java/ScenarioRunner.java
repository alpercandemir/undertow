package experiment.refunds;

import java.io.IOException;

/** Explicit entry point for the host-selected refund fixture. */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    public static void main(String[] args) throws IOException {
        ScenarioProtocol.run(RefundAdapter::new);
    }
}
