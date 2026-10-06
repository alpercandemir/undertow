package dev.undertow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

public final class CiEvidence {
    private CiEvidence() {}

    public static JsonNode load(Path file, GitRepository git) throws IOException {
        return load(file, git, null);
    }

    public static JsonNode load(Path file, GitRepository git, Path trustedReceipt)
            throws IOException {
        if (file == null) {
            return Json.MAPPER.createObjectNode().put("provenance", "no CI bundle provided");
        }
        JsonNode bundle = Json.parse(Json.read(file, 1000000));
        int version = bundle.path("schema_version").asInt();
        if (!Set.of(1, 2).contains(version)
                || !bundle.path("base_sha").asText().equals(git.base())
                || !bundle.path("head_sha").asText().equals(git.head())
                || !bundle.path("artifacts").isArray()) {
            throw new IllegalArgumentException("CI bundle commits/schema do not match");
        }
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put(
                                "provenance",
                                "caller-supplied local bundle; hashes verified; workflow authenticity not established");
        boolean verified = false;
        if (trustedReceipt != null) {
            JsonNode receipt = Json.parse(Json.read(trustedReceipt, 16000));
            if (version != 2
                    || !receipt.path("verified_source").asText().equals("github_actions_api"))
                throw new IllegalArgumentException(
                        "CI receipt must come from the trusted GitHub importer");
            for (String key :
                    java.util.List.of(
                            "repository", "run_id", "workflow_id", "artifact_id", "head_sha"))
                if (receipt.path(key).asText().isBlank()
                        || !receipt.path(key).equals(bundle.path(key)))
                    throw new IllegalArgumentException(
                            "CI repository/run/artifact provenance mismatch");
            try {
                String hash =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(file)));
                if (!hash.equals(receipt.path("bundle_sha256").asText()))
                    throw new IllegalArgumentException("CI bundle content modified after import");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            verified = true;
            result.put("provenance", "GitHub API artifact source verified by trusted importer")
                    .set("origin", bundle.path("origin"));
        }
        result.put("verified", verified);
        for (JsonNode artifact : bundle.path("artifacts")) {
            String snapshot = artifact.path("snapshot").asText();
            String kind = artifact.path("kind").asText();
            git.commit(snapshot);
            if (!Set.of("dependency_tree", "compiler", "tests", "spotbugs").contains(kind)
                    || !artifact.has("content")
                    || !Json.hash(Json.stringify(artifact.get("content")))
                            .equals(artifact.path("sha256").asText())) {
                throw new IllegalArgumentException("Invalid CI artifact kind/hash");
            }
            var group = result.withObject("/" + snapshot);
            if (group.has(kind)) {
                throw new IllegalArgumentException("Duplicate CI artifact");
            }
            group.set(kind, artifact.get("content"));
        }
        var execution = result.putArray("execution");
        var analyzer = result.putArray("analyzer_findings");
        for (String snapshot : java.util.List.of("base", "head")) {
            JsonNode tests = result.path(snapshot).path("tests");
            if (!tests.isMissingNode()) {
                if (!tests.isArray() || tests.size() > 10000)
                    throw new IllegalArgumentException("Invalid JUnit evidence");
                for (JsonNode test : tests) {
                    if (!Set.of("passed", "failed", "error", "skipped")
                                    .contains(test.path("status").asText())
                            || test.path("name").asText().isBlank())
                        throw new IllegalArgumentException("Invalid JUnit test result");
                    execution
                            .addObject()
                            .put("snapshot", snapshot)
                            .put("name", test.path("name").asText())
                            .put("status", verified ? test.path("status").asText() : "unverified")
                            .put("reported_status", test.path("status").asText())
                            .put("source", "CI JUnit (executed outside agent)");
                }
            }
            JsonNode bugs = result.path(snapshot).path("spotbugs");
            if (!bugs.isMissingNode()) {
                if (!bugs.isArray() || bugs.size() > 10000)
                    throw new IllegalArgumentException("Invalid SpotBugs evidence");
                for (JsonNode bug : bugs) {
                    GitRepository.safePath(bug.path("path").asText());
                    if (bug.path("line").asInt() < 1 || bug.path("type").asText().isBlank())
                        throw new IllegalArgumentException("Invalid SpotBugs finding");
                    analyzer.addObject()
                            .put("snapshot", snapshot)
                            .put("path", bug.path("path").asText())
                            .put("line", bug.path("line").asInt())
                            .put("type", bug.path("type").asText())
                            .put("source", "SpotBugs")
                            .put("verified", verified);
                }
            }
        }
        return result;
    }
}
