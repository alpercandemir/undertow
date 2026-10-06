package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Resolves immutable customer data without rewriting customer Git objects. */
public final class ServicePolicy {
    private ServicePolicy() {}

    public static Map<String, String> files(JsonNode node) {
        if (!node.isObject() || node.size() > 100)
            throw new ServiceException(422, "invalid_policy_files");
        Map<String, String> result = new TreeMap<>();
        int size = 0;
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String path = GitRepository.safePath(field.getKey());
            if (!path.startsWith(".undertow/")
                    || !field.getValue().isTextual()
                    || field.getValue().asText().length() > GitRepository.MAX_BYTES)
                throw new ServiceException(422, "invalid_policy_files");
            size = Math.addExact(size, field.getValue().asText().length());
            if (size > 1000000) throw new ServiceException(422, "policy_too_large");
            result.put(path, field.getValue().asText());
        }
        return Map.copyOf(result);
    }

    public static Policy resolve(Map<String, String> files, JsonNode execution, JsonNode sources)
            throws IOException {
        Map<String, String> logical = new LinkedHashMap<>(files);
        logical.put("config/languages/java/rules.yaml", required(files, ".undertow/rules.yaml"));
        logical.put("CODING-SKILL.md", required(files, ".undertow/guidelines.md"));
        logical.put(
                "config/business-context.yaml",
                files.getOrDefault(".undertow/business-context.yaml", "invariants: []\n"));
        return new Policy(logical, execution, sources);
    }

    public static String effectiveHash(Policy policy) throws IOException {
        return Json.hash(Json.stringify(new TreeMap<>(policy.hashes())));
    }

    private static String required(Map<String, String> files, String key) {
        String result = files.get(key);
        if (result == null) throw new ServiceException(422, "missing_policy_file");
        return result;
    }
}
