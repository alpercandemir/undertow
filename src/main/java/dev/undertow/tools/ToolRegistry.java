package dev.undertow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.languages.java.JavaAnalysis;
import dev.undertow.languages.java.MavenInventory;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ToolRegistry {
    private static final int MAX_CHANGED_FILES = 200;
    private static final int MAX_DIFF_CHARACTERS = 80_000;
    private static final int MAX_SOURCE_LINES = 200;
    private static final int MAX_SOURCE_CHARACTERS = 24_000;
    private static final int MAX_DOCUMENT_CHARACTERS = 24_000;
    private final GitRepository git;
    private final Policy policy;
    private final EvidenceStore evidence;
    private final JsonNode schemas;
    private final JsonNode ci;
    private final JavaAnalysis javaAnalysis = new JavaAnalysis();
    private final MavenInventory mavenInventory = new MavenInventory();
    private final SafeHttp http = new SafeHttp();
    private final List<String> gaps = new ArrayList<>();
    private final List<String> changed;
    private final Set<String> inspected = new HashSet<>();
    private final Set<String> considered = new HashSet<>();

    public ToolRegistry(GitRepository git, Policy policy, EvidenceStore evidence, JsonNode ci)
            throws IOException {
        this.git = git;
        this.policy = policy;
        this.evidence = evidence;
        this.ci = ci.deepCopy();
        schemas = Schemas.resource("tools");
        changed = git.changedFiles();
        if (changed.size() > MAX_CHANGED_FILES) {
            gaps.add(
                    "Review exceeds 200 changed files; diff collection limited to first 200, remaining files not evaluated");
        }
        for (String file : changed) {
            if (!supported(file)) {
                gaps.add("Skipped unsupported or excluded file: " + file);
            }
        }
    }

    public static boolean supported(String path) {
        return !path.matches("(^|.*/)(target|generated|vendor)/.*")
                && (path.endsWith(".java")
                        || path.equals("pom.xml")
                        || path.endsWith(".yaml")
                        || path.endsWith(".yml")
                        || path.endsWith(".sql"));
    }

    public JsonNode schemas() {
        return schemas.deepCopy();
    }

    public List<String> changed() {
        return changed;
    }

    public List<String> gaps() {
        List<String> all = new ArrayList<>(gaps);
        for (String file : changed) {
            if (supported(file) && !considered.contains(file)) {
                all.add("Diff not collected: " + file);
            }
        }
        return List.copyOf(all);
    }

    public Set<String> inspected() {
        return Set.copyOf(inspected);
    }

    public JsonNode ciEvidence() {
        return ci.deepCopy();
    }

    public JsonNode execute(String name, JsonNode args) throws IOException, InterruptedException {
        if (!schemas.has(name)) {
            throw new IllegalArgumentException("Unknown tool");
        }
        Schemas.validate(schemas.get(name), args);
        String snapshot = args.path("snapshot").asText("head");
        JsonNode result =
                switch (name) {
                    case "get_diff" -> diff();
                    case "read_source" -> source(snapshot, args);
                    case "find_references" ->
                            javaAnalysis.references(git, snapshot, args.path("symbol").asText());
                    case "get_rule" -> policy.rule(args.path("id").asText());
                    case "get_business_context" -> policy.business();
                    case "get_ci_evidence" -> ci.deepCopy();
                    case "compare_dependencies" -> dependencies();
                    case "fetch_migration_evidence" -> migration(args);
                    case "query_vulnerabilities" -> vulnerabilities(args);
                    default -> throw new IllegalArgumentException("Tool not implemented");
                };
        if (name.equals("find_references")
                && (result.path("unresolved_files").size() > 0
                        || result.path("symbol_resolution").asText().startsWith("unresolved"))) {
            gaps.add("Java symbol/classpath resolution is incomplete");
        }
        if (name.equals("fetch_migration_evidence")
                && result.path("coverage").asText().contains("endpoint")) {
            gaps.add(
                    "Dependency research retrieved endpoint documents; intermediate releases and JAR API comparison are not fully covered");
        }
        String commit =
                Set.of("get_rule", "get_business_context").contains(name)
                        ? git.policyCommit()
                        : git.commit(snapshot);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Tool cancelled before evidence registration");
        }
        return evidence.add(name, args.path("path").asText(name), commit, result);
    }

    private JsonNode diff() throws IOException {
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("base_sha", git.base())
                        .put("head_sha", git.head());
        var files = result.putArray("files");
        int size = 0;
        for (String file : changed.stream().limit(MAX_CHANGED_FILES).toList()) {
            if (!supported(file)) {
                continue;
            }
            try {
                String content = git.diff(file);
                if (size + content.length() > MAX_DIFF_CHARACTERS) {
                    gaps.add("Diff truncated before: " + file);
                    continue;
                }
                files.addObject().put("path", file).put("diff", content);
                considered.add(file);
                size += content.length();
            } catch (IOException | IllegalArgumentException e) {
                gaps.add("Diff unavailable: " + file);
            }
        }
        result.set("coverage_gaps", Json.MAPPER.valueToTree(gaps));
        return result;
    }

    private JsonNode source(String snapshot, JsonNode args) throws IOException {
        String path = args.path("path").asText();
        int start = args.path("start_line").asInt();
        int end = args.path("end_line").asInt();
        if (end < start || end - start >= MAX_SOURCE_LINES) {
            throw new IllegalArgumentException("Source range must span 1–200 lines");
        }
        String[] lines = git.source(snapshot, path).split("\n", -1);
        if (end > lines.length) {
            throw new IllegalArgumentException("Source range exceeds file");
        }
        String content = String.join("\n", Arrays.copyOfRange(lines, start - 1, end));
        if (content.length() > MAX_SOURCE_CHARACTERS) {
            throw new IOException("Source range exceeds character limit");
        }
        inspected.add(snapshot + ":" + path);
        return Json.MAPPER
                .createObjectNode()
                .put("snapshot", snapshot)
                .put("commit", git.commit(snapshot))
                .put("path", path)
                .put("start_line", start)
                .put("end_line", end)
                .put("total_lines", lines.length)
                .put("content", content);
    }

    private JsonNode dependencies() throws IOException {
        JsonNode data = mavenInventory.compare(git, ci);
        if (!data.path("coverage").asText().equals("resolved inventories")
                && changed.contains("pom.xml")) {
            gaps.add("Dependency resolution missing; inventory describes literal POM changes only");
        }
        return data;
    }

    private void checkVersionPair(JsonNode args) throws IOException {
        JsonNode inventory = mavenInventory.compare(git, ci);
        String coordinate = args.path("coordinate").asText();
        for (JsonNode change : inventory.path("changes")) {
            JsonNode before = change.path("before");
            JsonNode after = change.path("after");
            if (before.path("coordinate").asText().equals(coordinate)
                    && after.path("coordinate").asText().equals(coordinate)
                    && before.path("version").asText().equals(args.path("old_version").asText())
                    && after.path("version").asText().equals(args.path("new_version").asText())) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Version pair is not in the pinned dependency change inventory");
    }

    private void checkVersion(JsonNode args) throws IOException {
        JsonNode inventory = mavenInventory.compare(git, ci);
        String coordinate = args.path("coordinate").asText();
        String version = args.path("version").asText();
        for (String snapshot : List.of("base", "head")) {
            for (JsonNode dependency : inventory.path(snapshot)) {
                if (dependency.path("coordinate").asText().equals(coordinate)
                        && dependency.path("version").asText().equals(version)
                        && !version.equals("UNRESOLVED")) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("Exact version not present in dependency inventory");
    }

    private JsonNode migration(JsonNode args) throws IOException, InterruptedException {
        checkVersionPair(args);
        String coordinate = args.path("coordinate").asText();
        JsonNode config = policy.sources();
        JsonNode sources = config.path("sources").path(coordinate);
        if (!sources.isArray() || sources.isEmpty()) {
            gaps.add("No supported migration source: " + coordinate);
            return Json.MAPPER
                    .createObjectNode()
                    .put("status", "unverified; unsupported source registry coordinate");
        }
        Set<String> hosts = new HashSet<>();
        config.path("allowed_hosts").forEach(n -> hosts.add(n.asText()));
        var data = Json.MAPPER.createObjectNode();
        data.set("version_context", args.deepCopy());
        var docs = data.putArray("documents");
        var sourceFailures = data.putArray("source_failures");
        for (String version :
                List.of(args.path("old_version").asText(), args.path("new_version").asText())) {
            if (!version.matches("[A-Za-z0-9_.-]{1,80}")) {
                throw new IllegalArgumentException("Invalid version");
            }
            for (JsonNode template : sources) {
                String[] components = version.split("\\.");
                String url =
                        template.asText()
                                .replace("{version}", version)
                                .replace("{major}", components[0]);
                String raw;
                try {
                    raw = http.get(url, hosts);
                } catch (IOException e) {
                    sourceFailures
                            .addObject()
                            .put("url", url)
                            .put("version", version)
                            .put("status", "unverified: official source unavailable");
                    gaps.add(
                            "Official migration document unavailable for "
                                    + coordinate
                                    + " "
                                    + version);
                    continue;
                }
                String content =
                        raw.stripLeading().startsWith("<")
                                ? org.jsoup.Jsoup.parse(raw).text()
                                : raw;
                docs.addObject()
                        .put("url", url)
                        .put("version", version)
                        .put("content_hash", Json.hash(raw))
                        .put("text_hash", Json.hash(content))
                        .put(
                                "excerpt",
                                content.substring(
                                        0, Math.min(MAX_DOCUMENT_CHARACTERS, content.length())))
                        .put("truncated", content.length() > MAX_DOCUMENT_CHARACTERS);
            }
        }
        return data.put(
                "coverage",
                "endpoint documents only; inspect intermediate release claims explicitly");
    }

    private JsonNode vulnerabilities(JsonNode args) throws IOException, InterruptedException {
        checkVersion(args);
        var query = Json.MAPPER.createObjectNode().put("version", args.path("version").asText());
        query.putObject("package")
                .put("ecosystem", "Maven")
                .put("name", args.path("coordinate").asText());
        var data = Json.MAPPER.createObjectNode();
        data.set("query", query);
        data.set("advisories", Json.parse(http.postOsv(Json.stringify(query))));
        return data.put(
                "interpretation",
                "Advisory matches are not proof of reachable exploitation; POM versions may be unresolved by Maven");
    }
}
