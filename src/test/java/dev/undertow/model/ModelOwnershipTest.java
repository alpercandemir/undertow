package dev.undertow.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelOwnershipTest {
    @Test
    void modelMessagesKeepTheirOwnJsonSnapshots() {
        ObjectNode content = Json.MAPPER.createObjectNode().put("value", "original");
        var call = new ModelClient.Call("tool", content);
        var observation = new ModelClient.Observation("tool", content);
        var turn = new ModelClient.Turn(List.of(call), content, 0, 0, "fake");

        content.put("value", "changed input");
        ((ObjectNode) call.arguments()).put("value", "changed call");
        ((ObjectNode) observation.result()).put("value", "changed observation");
        ((ObjectNode) turn.review()).put("value", "changed review");

        assertThat(call.arguments().path("value").asText()).isEqualTo("original");
        assertThat(observation.result().path("value").asText()).isEqualTo("original");
        assertThat(turn.review().path("value").asText()).isEqualTo("original");
    }

    @Test
    void replayUsesTheResponsesProvidedAtConstruction() throws Exception {
        ObjectNode response = Json.MAPPER.createObjectNode();
        response.putObject("review").put("summary", "original");
        var responses = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        responses.add(response);
        var replay = new ReplayClient(responses);

        ((ObjectNode) response.path("review")).put("summary", "changed input");
        responses.clear();

        assertThat(replay.next("", List.of(), false).review().path("summary").asText())
                .isEqualTo("original");
    }
}
