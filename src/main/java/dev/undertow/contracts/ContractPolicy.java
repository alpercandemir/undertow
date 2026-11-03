package dev.undertow.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Owner-approved data from a separately pinned policy commit, never executable configuration. */
public final class ContractPolicy {
    private final JsonNode manifest;
    private final JsonNode binding;
    private final java.util.Map<String, String> oracleHashes;
    private final Set<String> externalSources;
    private final java.util.Map<String, java.util.List<String>> inventories;

    public ContractPolicy(GitRepository git, String path) throws IOException {
        String text = git.source("policy", GitRepository.safePath(path));
        manifest =
                Json.YAML
                        .reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(text);
        Schemas.validate(Schemas.resource("contracts"), manifest);
        if (manifest.path("repository").asText().contains("..")) {
            throw new IllegalArgumentException("Invalid contract repository");
        }
        Set<String> ids = new HashSet<>();
        var hashes = new TreeMap<String, String>();
        var artifacts = Json.MAPPER.createObjectNode();
        for (JsonNode artifact : manifest.path("regression_artifacts")) {
            String id = artifact.path("id").asText();
            if (artifacts.has(id))
                throw new IllegalArgumentException("Duplicate regression artifact");
            artifacts.put(id, artifact.path("sha256").asText());
        }
        var external = new HashSet<String>();
        var internal = new HashSet<String>();
        hashes.put(path, Json.hash(text));
        collect(git, manifest.path("oracle_paths"), hashes);
        for (JsonNode contract : manifest.path("contracts")) {
            if (!ids.add(contract.path("id").asText())) {
                throw new IllegalArgumentException("Duplicate contract ID");
            }
            for (JsonNode scope : contract.path("scope")) {
                GitRepository.safePath(scope.asText());
            }
            Set<String> tests = new HashSet<>();
            for (JsonNode test : contract.path("tests")) {
                if (test.has("artifact") && !artifacts.has(test.path("artifact").asText()))
                    throw new IllegalArgumentException("Unknown regression artifact");
                for (JsonNode source : test.path("sources")) {
                    (test.has("artifact") ? external : internal).add(source.asText());
                }
                if (!tests.add(test.path("id").asText())) {
                    throw new IllegalArgumentException("Duplicate test binding");
                }
                collect(git, test.path("sources"), hashes);
            }
        }
        for (JsonNode source : manifest.path("oracle_paths")) internal.add(source.asText());
        internal.add(path);
        external.removeAll(internal);
        var roots = new java.util.TreeSet<String>();
        roots.add(".mvn");
        for (JsonNode declared : manifest.path("oracle_roots"))
            roots.add(GitRepository.safePath(declared.asText()));
        for (JsonNode file : manifest.path("oracle_paths")) {
            String value = file.asText();
            if (value.endsWith("/pom.xml"))
                roots.add(value.substring(0, value.length() - "pom.xml".length()) + ".mvn");
        }
        var inventory = new TreeMap<String, java.util.List<String>>();
        var policyFiles = git.files("policy");
        for (String root : roots) {
            var selected =
                    policyFiles.stream()
                            .filter(p -> p.equals(root) || p.startsWith(root + "/"))
                            .sorted()
                            .toList();
            inventory.put(root, selected);
            collect(git, Json.MAPPER.valueToTree(selected), hashes);
            external.removeAll(selected);
        }
        oracleHashes = java.util.Map.copyOf(hashes);
        externalSources = Set.copyOf(external);
        inventories = java.util.Map.copyOf(inventory);
        // Include empty roots: adding a new Maven extension/config cannot inherit approval.
        var identity = new TreeMap<>(hashes);
        identity.put("$control_inventory", Json.hash(Json.stringify(inventory)));
        binding =
                Json.MAPPER
                        .createObjectNode()
                        .put("repository", manifest.path("repository").asText())
                        .put("policy_commit", git.policyCommit())
                        .put("manifest_path", path)
                        .put("manifest_sha256", Json.hash(text))
                        .put("oracle_sha256", Json.hash(Json.stringify(identity)));
        if (!artifacts.isEmpty())
            ((com.fasterxml.jackson.databind.node.ObjectNode) binding)
                    .set("oracle_artifacts", artifacts);
    }

    private static void collect(GitRepository git, JsonNode paths, TreeMap<String, String> hashes)
            throws IOException {
        for (JsonNode entry : paths) {
            String path = GitRepository.safePath(entry.asText());
            if (hashes.size() >= 500 && !hashes.containsKey(path)) {
                throw new IllegalArgumentException("Contract oracle inventory exceeds limit");
            }
            if (!hashes.containsKey(path)) hashes.put(path, Json.hash(git.source("policy", path)));
        }
    }

    public JsonNode manifest() {
        return manifest.deepCopy();
    }

    /** Export only through the trusted runner; retain outside candidate-controlled inputs. */
    public JsonNode binding() {
        return binding.deepCopy();
    }

    public java.util.List<String> changedOraclePaths(GitRepository git) throws IOException {
        return changedOraclePaths(git, false);
    }

    public java.util.List<String> changedOraclePaths(
            GitRepository git, boolean approvedExternalExecution) throws IOException {
        var changed = new java.util.ArrayList<String>();
        var files = git.files("head");
        for (var inventory : new TreeMap<>(inventories).entrySet()) {
            String root = inventory.getKey();
            var actual =
                    files.stream()
                            .filter(p -> p.equals(root) || p.startsWith(root + "/"))
                            .sorted()
                            .toList();
            if (!actual.equals(inventory.getValue()))
                changed.add(root + " (oracle inventory changed)");
        }
        for (var entry : new TreeMap<>(oracleHashes).entrySet()) {
            if (approvedExternalExecution && externalSources.contains(entry.getKey())) continue;
            if (!files.contains(entry.getKey())) {
                changed.add(entry.getKey());
                continue;
            }
            try {
                if (!entry.getValue().equals(Json.hash(git.source("head", entry.getKey())))) {
                    changed.add(entry.getKey());
                }
            } catch (IOException e) {
                // A symlink, binary file, or unreadable oracle cannot retain approval.
                changed.add(entry.getKey());
            }
        }
        return java.util.List.copyOf(changed);
    }
}
