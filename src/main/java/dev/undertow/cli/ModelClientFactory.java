package dev.undertow.cli;

import dev.undertow.harness.Reviewer;
import dev.undertow.model.GeminiClient;
import dev.undertow.model.ModelClient;
import dev.undertow.model.ReplayClient;
import dev.undertow.policy.Policy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Creates a client only after the command has loaded the pinned review policy. */
public final class ModelClientFactory {
    public ModelClient create(Reviewer reviewer, Path replay, String mode) throws IOException {
        if (replay != null) {
            return new ReplayClient(replay);
        }
        if (mode.equals("collect")) {
            return new ReplayClient(List.of());
        }

        Policy policy = reviewer.policy();
        String apiKey = System.getenv(policy.keyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            return new ModelClient() {
                public Turn next(
                        String instruction, List<Observation> observations, boolean finalOnly)
                        throws IOException {
                    throw new GeminiClient.ModelFailure(
                            401,
                            new IOException(
                                    "Model credential missing; set the trusted policy's api_key_env"));
                }

                public String identity() {
                    return policy.model();
                }

                public void close() {}
            };
        }
        int timeoutMillis = Math.multiplyExact(policy.limits().toolTimeoutSeconds(), 1000);
        return new GeminiClient(policy.model(), apiKey, timeoutMillis, reviewer.tools().schemas());
    }
}
