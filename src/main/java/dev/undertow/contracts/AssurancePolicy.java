package dev.undertow.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;

public final class AssurancePolicy {
    private AssurancePolicy() {}

    public static JsonNode load(GitRepository git, String path, String schema) throws IOException {
        String text = git.source("policy", GitRepository.safePath(path));
        JsonNode value =
                Json.YAML
                        .reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(text);
        Schemas.validate(Schemas.resource(schema), value);
        var result = Json.MAPPER.createObjectNode();
        result.put("policy_commit", git.policyCommit())
                .put("path", path)
                .put("sha256", Json.hash(text));
        result.set("document", value);
        return result;
    }
}
