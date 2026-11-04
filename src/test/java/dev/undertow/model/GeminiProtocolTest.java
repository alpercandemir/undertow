package dev.undertow.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undertow.reporting.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiProtocolTest {
    @Test
    void parallelCallsToTheSameToolRetainDistinctResponseIds() throws Exception {
        var content =
                GeminiClient.observationContent(
                        List.of(
                                new ModelClient.Observation(
                                        "call-base",
                                        "read_source",
                                        Json.parse("{\"evidence_id\":\"e1\"}")),
                                new ModelClient.Observation(
                                        "call-head",
                                        "read_source",
                                        Json.parse("{\"evidence_id\":\"e2\"}"))));
        assertThat(content.role().orElseThrow()).isEqualTo("user");
        var parts = content.parts().orElseThrow();
        assertThat(parts.get(0).functionResponse().orElseThrow().id().orElseThrow())
                .isEqualTo("call-base");
        assertThat(parts.get(1).functionResponse().orElseThrow().id().orElseThrow())
                .isEqualTo("call-head");
    }
}
