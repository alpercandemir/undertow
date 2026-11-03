package dev.undertow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.file.Path;
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
        String bundleText = Json.read(file, 1000000);
        JsonNode bundle = Json.parse(bundleText);
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
            // Hash the same bounded input that was parsed; a second read permits a replacement
            // race.
            if (!Json.hash(bundleText).equals(receipt.path("bundle_sha256").asText()))
                throw new IllegalArgumentException("CI bundle content modified after import");
            verified = true;
            var source = result.putObject("source_receipt");
            for (String key :
                    java.util.List.of(
                            "repository",
                            "run_id",
                            "workflow_id",
                            "artifact_id",
                            "bundle_sha256")) {
                source.set(key, receipt.path(key));
            }
            if (bundle.has("contract_binding")) {
                if (!bundle.path("contract_binding").isObject()
                        || !bundle.path("contract_binding").equals(receipt.path("contract_binding"))
                        || !bundle.path("run_conclusion").equals(receipt.path("run_conclusion"))
                        || !bundle.path("repository")
                                .equals(bundle.path("contract_binding").path("repository"))) {
                    throw new IllegalArgumentException("CI contract binding provenance mismatch");
                }
                result.set("contract_binding", bundle.path("contract_binding"));
                result.set("run_conclusion", bundle.path("run_conclusion"));
            }
            result.put("provenance", "GitHub API artifact source verified by trusted importer")
                    .set("origin", bundle.path("origin"));
        }
        result.put("verified", verified);
        for (JsonNode artifact : bundle.path("artifacts")) {
            String snapshot = artifact.path("snapshot").asText();
            String kind = artifact.path("kind").asText();
            git.commit(snapshot);
            if (!Set.of("dependency_tree", "compiler", "tests", "spotbugs", "build", "oracle")
                            .contains(kind)
                    || !artifact.has("content")
                    || !Json.hash(Json.stringify(artifact.get("content")))
                            .equals(artifact.path("sha256").asText())) {
                throw new IllegalArgumentException("Invalid CI artifact kind/hash");
            }
            JsonNode content = artifact.path("content");
            if (Set.of("build", "oracle").contains(kind)) {
                if (content.path("schema_version").asInt() != 1
                        || !content.path("head_sha").asText().equals(git.commit(snapshot))) {
                    throw new IllegalArgumentException("CI build/oracle subject mismatch");
                }
                if (kind.equals("build")
                        && !content.path("artifact_sha256").asText().matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid CI build digest");
                }
                if (kind.equals("oracle")) {
                    if (!content.path("oracle_artifacts").isObject()
                            || content.path("oracle_artifacts").isEmpty()
                            || content.path("oracle_artifacts").size() > 10
                            || !content.path("image")
                                    .asText()
                                    .matches("[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("Invalid CI oracle execution");
                    }
                    var fields = content.path("oracle_artifacts").fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        if (!field.getKey().matches("[A-Za-z0-9_-]{1,80}")
                                || !field.getValue().asText().matches("[0-9a-f]{64}")) {
                            throw new IllegalArgumentException("Invalid CI oracle digest");
                        }
                    }
                }
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
