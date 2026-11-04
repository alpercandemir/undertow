package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import java.net.URI;
import java.net.URISyntaxException;

public final class ContractSarif {
    private ContractSarif() {}

    public static JsonNode render(JsonNode report, ContractPolicy policy) {
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("version", "2.1.0")
                        .put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        var run = result.putArray("runs").addObject();
        run.putObject("tool")
                .putObject("driver")
                .put("name", "Undertow contracts")
                .put("version", "1");
        run.putArray("versionControlProvenance")
                .addObject()
                .put(
                        "repositoryUri",
                        "https://github.com/" + report.path("policy").path("repository").asText())
                .put("revisionId", report.path("head_sha").asText());
        run.set("properties", report.deepCopy());
        var findings = run.putArray("results");
        for (JsonNode contract : report.path("contracts")) {
            for (JsonNode check : contract.path("checks")) {
                String status = check.path("status").asText();
                if (status.equals("satisfied")) continue;
                var finding =
                        findings.addObject()
                                .put("ruleId", contract.path("id").asText())
                                .put("level", status.equals("unsatisfied") ? "error" : "warning");
                finding.putObject("message")
                        .put(
                                "text",
                                check.path("test_id").asText()
                                        + ": "
                                        + status
                                        + ". "
                                        + check.path("next_action").asText());
                finding.putObject("properties")
                        .put("test_id", check.path("test_id").asText())
                        .put("verification", status);
                for (JsonNode definition : policy.manifest().path("contracts")) {
                    if (!definition.path("id").equals(contract.path("id"))) continue;
                    for (JsonNode test : definition.path("tests")) {
                        if (!test.path("id").equals(check.path("test_id"))) continue;
                        for (JsonNode source : test.path("sources")) {
                            try {
                                finding.withArray("locations")
                                        .addObject()
                                        .putObject("physicalLocation")
                                        .putObject("artifactLocation")
                                        .put(
                                                "uri",
                                                new URI(null, null, source.asText(), null)
                                                        .toASCIIString());
                            } catch (URISyntaxException e) {
                                throw new IllegalArgumentException("Invalid SARIF source path", e);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}
