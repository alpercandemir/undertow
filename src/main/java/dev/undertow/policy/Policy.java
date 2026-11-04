package dev.undertow.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Policy {
    public record Limits(
            int maxModelCalls,
            int maxToolCalls,
            int maxContextTokens,
            int maxTotalTokens,
            int runTimeoutSeconds,
            int toolTimeoutSeconds,
            int retryAttempts) {
        public Limits {
            if (maxModelCalls < 1
                    || maxModelCalls > 50
                    || maxToolCalls < 1
                    || maxToolCalls > 100
                    || maxContextTokens < 1000
                    || maxContextTokens > 100000
                    || maxTotalTokens < 1000
                    || maxTotalTokens > 250000
                    || runTimeoutSeconds < 1
                    || runTimeoutSeconds > 900
                    || toolTimeoutSeconds < 1
                    || toolTimeoutSeconds > 30
                    || retryAttempts < 0
                    || retryAttempts > 3) {
                throw new IllegalArgumentException("Invalid or excessive review limits");
            }
        }
    }

    private final JsonNode config;
    private final JsonNode rules;
    private final JsonNode business;
    private final JsonNode sources;
    private final String source;
    private final Map<String, String> hashes;
    private final RuleCatalog catalog;
    private final Map<String, String> referenceSources;

    public Policy(GitRepository git) throws IOException {
        this(path -> git.source("policy", path), null, null);
    }

    /** Host execution settings have a separate identity; customer files remain inert data. */
    public Policy(Map<String, String> files, JsonNode execution, JsonNode dependencySources)
            throws IOException {
        this(packageReader(Map.copyOf(files)), execution, dependencySources);
    }

    private static RuleCatalog.SourceReader packageReader(Map<String, String> files) {
        return path -> {
            GitRepository.safePath(path);
            String text = files.get(path);
            if (text == null || text.length() > GitRepository.MAX_BYTES) {
                throw new IOException("Missing or excessive policy reference");
            }
            return text;
        };
    }

    private Policy(RuleCatalog.SourceReader reader, JsonNode execution, JsonNode dependencySources)
            throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        for (String p :
                List.of(
                        "config/review.yaml",
                        "config/languages/java/rules.yaml",
                        "config/business-context.yaml",
                        "config/dependency-sources.yaml",
                        "CODING-SKILL.md")) {
            if (execution != null
                    && (p.equals("config/review.yaml")
                            || p.equals("config/dependency-sources.yaml"))) continue;
            contents.put(p, reader.read(p));
        }
        config =
                execution == null
                        ? Json.YAML.readTree(contents.get("config/review.yaml"))
                        : execution.deepCopy();
        if (config.path("schema_version").asInt() != 1) {
            throw new IllegalArgumentException("Unsupported config version");
        }
        catalog =
                new RuleCatalog(
                        Json.YAML.readTree(contents.get("config/languages/java/rules.yaml")),
                        reader,
                        contents.get("CODING-SKILL.md"));
        rules = catalog.rules();
        business = Json.YAML.readTree(contents.get("config/business-context.yaml"));
        sources =
                dependencySources == null
                        ? Json.YAML.readTree(contents.get("config/dependency-sources.yaml"))
                        : dependencySources.deepCopy();
        source = contents.get("CODING-SKILL.md");
        Map<String, String> digest = new LinkedHashMap<>();
        Map<String, String> referenced = new LinkedHashMap<>();
        contents.forEach((k, v) -> digest.put(k, Json.hash(v)));
        if (execution != null) {
            digest.put("@service/execution.json", Json.hash(Json.stringify(config)));
            digest.put("@service/dependency-sources.json", Json.hash(Json.stringify(sources)));
        }
        for (JsonNode rule : rules) {
            for (JsonNode ref : rule.path("references")) {
                String path = ref.path("path").asText();
                String text = reader.read(path);
                digest.put(path, Json.hash(text));
                referenced.put(path, text);
            }
        }
        hashes = Map.copyOf(digest);
        referenceSources = Map.copyOf(referenced);
        limits();
        if (maxFindings() < 1 || maxFindings() > 25 || !rules.isArray() || rules.size() > 100) {
            throw new IllegalArgumentException("Invalid policy catalog or finding limit");
        }
    }

    public Limits limits() {
        JsonNode n = config.path("limits");
        return new Limits(
                n.path("max_model_calls").asInt(12),
                n.path("max_tool_calls").asInt(30),
                n.path("max_context_tokens").asInt(30000),
                n.path("max_total_tokens").asInt(80000),
                n.path("run_timeout_seconds").asInt(300),
                n.path("tool_timeout_seconds").asInt(20),
                n.path("retry_attempts").asInt(2));
    }

    public int maxFindings() {
        return config.path("output").path("max_findings").asInt(10);
    }

    public String model() {
        return config.path("model").path("name").asText("gemini-3.1-flash-lite");
    }

    public String keyEnv() {
        return config.path("model").path("api_key_env").asText("GEMINI_API_KEY");
    }

    public JsonNode business() {
        return business.deepCopy();
    }

    public JsonNode sources() {
        return sources.deepCopy();
    }

    public JsonNode rules() {
        return rules.deepCopy();
    }

    public Map<String, String> hashes() {
        return hashes;
    }

    public RuleCatalog catalog() {
        return catalog;
    }

    public String selectionCommit(GitRepository git) {
        return git.policyCommit();
    }

    public JsonNode rule(String id) {
        for (JsonNode rule : rules) {
            if (rule.path("id").asText().equals(id)) {
                var result = rule.deepCopy();
                StringBuilder sections = new StringBuilder();
                for (JsonNode number : rule.path("sections")) {
                    String marker = "# " + number.asInt() + ". ";
                    int start = source.indexOf(marker);
                    if (start < 0) {
                        throw new IllegalArgumentException("Policy section not found");
                    }
                    int end = source.indexOf("\n# ", start + marker.length());
                    sections.append(source, start, end < 0 ? source.length() : end).append('\n');
                }
                for (JsonNode ref : rule.path("references")) {
                    String content = referenceSources.get(ref.path("path").asText());
                    String heading = ref.path("heading").asText();
                    int start = content.indexOf(heading),
                            end = content.indexOf("\n#", start + heading.length());
                    sections.append(content, start, end < 0 ? content.length() : end).append('\n');
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) result)
                        .put("source_sections", sections.toString());
                return result;
            }
        }
        throw new IllegalArgumentException("Unknown rule ID");
    }
}
