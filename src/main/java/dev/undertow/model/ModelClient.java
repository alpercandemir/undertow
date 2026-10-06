package dev.undertow.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

public interface ModelClient extends AutoCloseable {
    record Call(String id, String name, JsonNode arguments) {
        public Call {
            arguments = arguments.deepCopy();
        }

        @Override
        public JsonNode arguments() {
            return arguments.deepCopy();
        }

        public Call(String name, JsonNode arguments) {
            this("", name, arguments);
        }
    }

    record Observation(String id, String name, JsonNode result) {
        public Observation {
            result = result.deepCopy();
        }

        @Override
        public JsonNode result() {
            return result.deepCopy();
        }

        public Observation(String name, JsonNode result) {
            this("", name, result);
        }
    }

    record Turn(
            List<Call> calls,
            JsonNode review,
            int inputTokens,
            int outputTokens,
            String modelIdentity,
            String reviewCallId) {
        public Turn(
                List<Call> calls,
                JsonNode review,
                int inputTokens,
                int outputTokens,
                String modelIdentity) {
            this(calls, review, inputTokens, outputTokens, modelIdentity, "");
        }

        public Turn {
            calls = List.copyOf(calls);
            review = review == null ? null : review.deepCopy();
        }

        /** A turn may contain investigation calls without a final review. */
        @Override
        public JsonNode review() {
            return review == null ? null : review.deepCopy();
        }
    }

    Turn next(String instruction, List<Observation> observations, boolean finalOnly)
            throws IOException;

    String identity();

    /** False when provider token counts are absent; zero must not imply free inference. */
    default boolean usageKnown() {
        return true;
    }

    @Override
    default void close() {}
}
