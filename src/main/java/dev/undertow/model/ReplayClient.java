package dev.undertow.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fake responses exercise the real harness; they make no accuracy claim about a live model. */
public final class ReplayClient implements ModelClient {
    private final List<JsonNode> turns;
    private int cursor;

    public ReplayClient(Path path) throws IOException {
        JsonNode node = Json.parse(Json.read(path, 1000000));
        if (!node.isArray() || node.size() > 50) {
            throw new IOException("Replay must contain at most 50 turns");
        }
        List<JsonNode> responses = new ArrayList<>();
        node.forEach(responses::add);
        turns = List.copyOf(responses);
    }

    public ReplayClient(List<JsonNode> responses) {
        turns = responses.stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }

    @Override
    public Turn next(String instruction, List<Observation> observations, boolean finalOnly)
            throws IOException {
        if (cursor >= turns.size()) {
            throw new IOException("Replay exhausted");
        }
        JsonNode turn = turns.get(cursor++);
        List<Call> calls = new ArrayList<>();
        for (JsonNode call : turn.path("calls")) {
            calls.add(
                    new Call(
                            call.path("id").asText(),
                            call.path("name").asText(),
                            call.path("arguments")));
        }
        return new Turn(
                calls,
                turn.get("review"),
                turn.path("input_tokens").asInt(),
                turn.path("output_tokens").asInt(),
                identity());
    }

    @Override
    public String identity() {
        return "offline-replay-v1";
    }
}
