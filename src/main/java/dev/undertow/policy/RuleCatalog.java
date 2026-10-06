package dev.undertow.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, inert policy data. Rules cannot register tools or execute commands. */
public final class RuleCatalog {
    @FunctionalInterface
    public interface SourceReader {
        String read(String path) throws IOException;
    }

    public record Coverage(
            String id,
            String version,
            String status,
            String reason,
            List<String> paths,
            List<String> exceptions) {
        public Coverage {
            paths = List.copyOf(paths);
            exceptions = List.copyOf(exceptions);
        }
    }

    private final ArrayNode rules = Json.MAPPER.createArrayNode();
    private final JsonNode exceptions;
    private final int version;

    public RuleCatalog(JsonNode catalog, GitRepository git, String guideline) throws IOException {
        this(catalog, path -> git.source("policy", path), guideline);
    }

    public RuleCatalog(JsonNode catalog, SourceReader reader, String guideline) throws IOException {
        Schemas.validate(Schemas.resource("rules"), catalog);
        version = catalog.path("schema_version").asInt(1);
        exceptions =
                catalog.path("exceptions").isArray()
                        ? catalog.path("exceptions").deepCopy()
                        : Json.MAPPER.createArrayNode();
        Set<String> ids = new HashSet<>();
        for (JsonNode input : catalog.path("rules")) {
            String id = input.path("id").asText();
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate rule ID: " + id);
            ObjectNode rule = input.deepCopy();
            if (version == 1) {
                rule.put("version", "1")
                        .put("owner", "repository policy owner")
                        .put("description", rule.path("wording").asText())
                        .put("execution", "contextual")
                        .put("enabled", true);
                rule.putArray("languages").add("java");
                rule.putArray("include").add("**");
                rule.putArray("exclude");
                rule.putArray("required_tools").add("get_diff").add("read_source");
            }
            validateGlobs(rule.path("include"));
            validateGlobs(rule.path("exclude"));
            if (rule.path("execution").asText().equals("deterministic")
                    && !Set.of("junit", "spotbugs").contains(rule.path("executor").asText())) {
                throw new IllegalArgumentException(
                        "Deterministic rule needs a registered executor");
            }
            for (JsonNode section : rule.path("sections")) {
                if (!guideline.contains("# " + section.asInt() + ". "))
                    throw new IllegalArgumentException("Missing guideline section for " + id);
            }
            for (JsonNode reference : rule.path("references")) {
                String path = GitRepository.safePath(reference.path("path").asText());
                String content = reader.read(path);
                if (!content.lines()
                        .anyMatch(line -> line.equals(reference.path("heading").asText())))
                    throw new IllegalArgumentException("Missing guideline heading for " + id);
            }
            for (JsonNode fixture : rule.path("examples").path("fixtures"))
                reader.read(GitRepository.safePath(fixture.asText()));
            rules.add(rule);
        }
        Set<String> exceptionIds = new HashSet<>();
        for (JsonNode exception : exceptions) {
            if (!exceptionIds.add(exception.path("id").asText())
                    || !ids.contains(exception.path("rule_id").asText()))
                throw new IllegalArgumentException("Duplicate exception or unknown rule");
            validateGlobs(exception.path("include"));
            LocalDate expiration;
            try {
                expiration = LocalDate.parse(exception.path("expires").asText());
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException("Invalid exception expiration date");
            }
            if (expiration.isBefore(LocalDate.now(java.time.ZoneOffset.UTC)))
                throw new IllegalArgumentException(
                        "Expired policy exception: " + exception.path("id").asText());
        }
    }

    public int version() {
        return version;
    }

    public JsonNode rules() {
        return rules.deepCopy();
    }

    public JsonNode exceptions() {
        return exceptions.deepCopy();
    }

    public static void validateGlobs(JsonNode globs) {
        for (JsonNode glob : globs) {
            String value = glob.asText();
            if (value.length() > 300
                    || value.startsWith("/")
                    || value.startsWith("-")
                    || !value.matches("[A-Za-z0-9_./*? -]+")
                    || List.of(value.split("/", -1)).stream()
                            .anyMatch(
                                    part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                    || value.contains("***"))
                throw new IllegalArgumentException("Invalid repository path glob");
            globPattern(value);
        }
    }

    private static Pattern globPattern(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' && i + 1 < value.length() && value.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < value.length() && value.charAt(i + 1) == '/') {
                    i++;
                    regex.append("(?:.*/)?");
                } else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*");
            else if (c == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static boolean matches(JsonNode globs, String path) {
        for (JsonNode glob : globs)
            if (globPattern(glob.asText()).matcher(path).matches()) return true;
        return false;
    }

    public boolean active(JsonNode rule, String path) {
        return rule.path("enabled").asBoolean(true)
                && matches(rule.path("include"), path)
                && !matches(rule.path("exclude"), path)
                && !excepted(rule.path("id").asText(), path);
    }

    private boolean excepted(String id, String path) {
        for (JsonNode exception : exceptions)
            if (exception.path("rule_id").asText().equals(id)
                    && matches(exception.path("include"), path)) return true;
        return false;
    }

    public List<Coverage> selection(List<String> changed) {
        List<Coverage> result = new ArrayList<>();
        for (JsonNode rule : rules) {
            String id = rule.path("id").asText();
            List<String> applicable =
                    changed.stream()
                            .filter(
                                    path ->
                                            matches(rule.path("include"), path)
                                                    && !matches(rule.path("exclude"), path))
                            .toList();
            List<String> active = applicable.stream().filter(path -> !excepted(id, path)).toList();
            List<String> excepts = new ArrayList<>();
            for (JsonNode exception : exceptions)
                if (exception.path("rule_id").asText().equals(id)
                        && applicable.stream()
                                .anyMatch(path -> matches(exception.path("include"), path)))
                    excepts.add(
                            exception.path("id").asText()
                                    + ": "
                                    + exception.path("justification").asText()
                                    + " (owner "
                                    + exception.path("owner").asText()
                                    + ", expires "
                                    + exception.path("expires").asText()
                                    + ")");
            String status =
                    !rule.path("enabled").asBoolean() || applicable.isEmpty()
                            ? "out_of_scope"
                            : active.isEmpty() ? "exception" : "selected";
            String reason =
                    !rule.path("enabled").asBoolean()
                            ? "Disabled in trusted policy"
                            : applicable.isEmpty()
                                    ? "No changed paths match"
                                    : active.isEmpty()
                                            ? "Trusted scoped exception"
                                            : "Selected for declared scope; evaluation requires evidence";
            result.add(
                    new Coverage(
                            id, rule.path("version").asText(), status, reason, active, excepts));
        }
        return List.copyOf(result);
    }
}
