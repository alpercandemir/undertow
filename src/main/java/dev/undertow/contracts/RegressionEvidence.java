package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RegressionEvidence {
    private RegressionEvidence() {}

    public static JsonNode plan(
            GitRepository git, ContractPolicy policy, String artifact, String image, String prefix)
            throws IOException {
        if (!image.matches("[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Use an immutable executor image digest");
        if (!prefix.isEmpty()) GitRepository.safePath(prefix);
        String root = prefix.isEmpty() ? "" : prefix + "/";
        String digest = policy.binding().path("oracle_artifacts").path(artifact).asText();
        if (digest.isEmpty())
            throw new IllegalArgumentException("Regression artifact is not approved");
        var plan =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("head_sha", git.head())
                        .put("project_prefix", root)
                        .put("image", image)
                        .put("artifact_id", artifact)
                        .put("artifact_sha256", digest);
        plan.set("contract_binding", policy.binding());
        var subject = plan.putObject("application_sources");
        for (String path : git.files("head")) {
            if (path.equals(root + "pom.xml") || path.startsWith(root + "src/main/")) {
                if (subject.size() >= 500)
                    throw new IOException("Regression subject exceeds file limit");
                subject.put(path, Json.hash(git.source("head", path)));
            }
        }
        if (!subject.has(root + "pom.xml")
                || !subject.path(root + "pom.xml")
                        .asText()
                        .equals(Json.hash(git.source("policy", root + "pom.xml")))) {
            throw new IllegalArgumentException(
                    "Regression build configuration must match approved policy");
        }
        var sources = plan.putObject("oracle_sources");
        var ids = plan.putArray("test_ids");
        for (JsonNode contract : policy.manifest().path("contracts")) {
            for (JsonNode test : contract.path("tests")) {
                if (!test.path("artifact").asText().equals(artifact)) continue;
                ids.add(test.path("id"));
                for (JsonNode file : test.path("sources")) {
                    String path = file.asText();
                    if (!path.startsWith(root + "src/test/"))
                        throw new IllegalArgumentException(
                                "External oracles must be test sources/resources");
                    sources.put(path, Json.hash(git.source("policy", path)));
                }
            }
        }
        if (sources.isEmpty())
            throw new IllegalArgumentException("Approved artifact has no bound tests");
        plan.put("execution", "isolated_ci_only");
        return plan;
    }

    public static JsonNode compare(
            GitRepository git,
            ContractPolicy policy,
            JsonNode runs,
            Path root,
            String contractId,
            String testId)
            throws IOException {
        if (runs.path("schema_version").asInt() != 1)
            throw new IllegalArgumentException("Unknown reproduction input");
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("conclusion", "incomplete")
                        .put("contract_id", contractId)
                        .put("test_id", testId)
                        .put("base_sha", git.base())
                        .put("head_sha", git.head());
        Set<String> identities = new HashSet<>();
        var statuses = Json.MAPPER.createObjectNode();
        var observed = result.putArray("runs");
        boolean complete = true;
        for (String side : List.of("baseline", "candidate")) {
            JsonNode repetitions = runs.path(side);
            if (!repetitions.isArray() || repetitions.size() < 2 || repetitions.size() > 10)
                throw new IllegalArgumentException(
                        "Require 2–10 independent executions per subject");
            var subject =
                    new GitRepository(
                            git.root(),
                            git.base(),
                            side.equals("baseline") ? git.base() : git.head(),
                            git.policyCommit(),
                            git.targetHead());
            Set<String> outcomes = new HashSet<>();
            for (JsonNode repetition : repetitions) {
                var report =
                        ContractVerifier.verify(
                                subject,
                                policy,
                                root.resolve(repetition.path("bundle").asText()),
                                root.resolve(repetition.path("receipt").asText()));
                String identity =
                        report.path("evidence_source").path("repository").asText()
                                + "/"
                                + report.path("evidence_source").path("run_id").asText();
                if (!identities.add(identity)
                        || report.path("evidence_source").path("run_id").asText().isEmpty())
                    complete = false;
                String outcome = "incomplete";
                for (JsonNode contract : report.path("contracts")) {
                    if (!contract.path("id").asText().equals(contractId)) continue;
                    for (JsonNode check : contract.path("checks"))
                        if (check.path("test_id").asText().equals(testId))
                            outcome = check.path("status").asText();
                }
                outcomes.add(outcome);
                observed.addObject()
                        .put("subject", side)
                        .put("outcome", outcome)
                        .set("evidence_source", report.path("evidence_source"));
            }
            if (outcomes.size() != 1 || outcomes.contains("incomplete")) complete = false;
            statuses.put(side, outcomes.size() == 1 ? outcomes.iterator().next() : "inconsistent");
        }
        if (complete) {
            String before = statuses.path("baseline").asText(),
                    after = statuses.path("candidate").asText();
            result.put("conclusion", conclusion(before, after));
        }
        result.set("outcomes", statuses);
        return result;
    }

    private static String conclusion(String before, String after) {
        if (before.equals("satisfied") && after.equals("unsatisfied")) {
            return "reproduced_regression";
        }
        if (before.equals("unsatisfied") && after.equals("satisfied")) {
            return "verified_correction";
        }
        if (before.equals("unsatisfied")) {
            return "existing_violation";
        }
        return "no_reproduced_regression";
    }
}
