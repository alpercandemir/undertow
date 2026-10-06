package dev.undertow.harness;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceStore {
    public record Evidence(
            String id,
            String kind,
            String origin,
            String commit,
            String contentHash,
            String retrievedAt,
            JsonNode content) {
        public Evidence {
            content = Objects.requireNonNull(content, "Evidence content is required").deepCopy();
        }

        @Override
        public JsonNode content() {
            return content.deepCopy();
        }
    }

    private final Map<String, Evidence> entries = new LinkedHashMap<>();

    public synchronized JsonNode add(String kind, String origin, String commit, JsonNode content)
            throws IOException {
        String id = "e" + (entries.size() + 1);
        Evidence entry =
                new Evidence(
                        id,
                        kind,
                        origin,
                        commit,
                        Json.hash(Json.stringify(content)),
                        Instant.now().toString(),
                        content);
        entries.put(id, entry);
        return Json.MAPPER
                .createObjectNode()
                .put("evidence_id", id)
                .set("data", content.deepCopy());
    }

    public static EvidenceStore restore(JsonNode bundle) throws IOException {
        if (!bundle.isArray() || bundle.size() > 100) {
            throw new IOException("Invalid evidence bundle");
        }
        EvidenceStore store = new EvidenceStore();
        for (JsonNode node : bundle) {
            Evidence entry = Json.MAPPER.treeToValue(node, Evidence.class);
            if (entry == null
                    || !("e" + (store.entries.size() + 1)).equals(entry.id())
                    || !Json.hash(Json.stringify(entry.content())).equals(entry.contentHash())) {
                throw new IOException("Evidence ID or hash mismatch");
            }
            store.entries.put(entry.id(), entry);
        }
        return store;
    }

    public synchronized List<Evidence> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized Evidence get(String id) {
        Evidence entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown evidence reference: " + id);
        }
        return entry;
    }
}
