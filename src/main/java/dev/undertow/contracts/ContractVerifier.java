package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Markdown;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.CiEvidence;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Deterministic shadow verification. It has no model, shell, publisher, or release authority. */
public final class ContractVerifier {
    private ContractVerifier() {}

    public static ObjectNode verify(
            GitRepository git, ContractPolicy policy, Path bundle, Path receipt)
            throws IOException {
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("base_sha", git.base())
                        .put("head_sha", git.head())
                        .put("target_head", git.targetHead())
                        .put("mode", "shadow")
                        .put("release_decision", "not_evaluated");
        result.set("policy", policy.binding());
        var gaps = result.putArray("evidence_gaps");
        JsonNode evidence;
        try {
            evidence = CiEvidence.load(bundle, git, receipt);
        } catch (IllegalArgumentException | IOException e) {
            // Candidate evidence must never replace an earlier report with an implicit success.
            evidence = Json.MAPPER.createObjectNode();
            gaps.add(
                    "CI evidence rejected: invalid, stale, unreadable, or modified bundle/receipt");
        }
        result.put("evidence_provenance", evidence.path("provenance").asText("rejected"));
        if (evidence.path("origin").isObject())
            result.set("evidence_origin", evidence.path("origin"));
        else result.putNull("evidence_origin");
        result.set("evidence_source", evidence.path("source_receipt"));
        if (!evidence.path("verified").asBoolean()) {
            gaps.add("Import exact-head CI evidence with a receipt retained by the trusted runner");
        }
        if (!policy.binding().equals(evidence.path("contract_binding"))) {
            gaps.add(
                    "CI receipt is missing the exact approved contract/oracle binding; re-import with --contract-policy");
        }
        if (!List.of("success", "failure").contains(evidence.path("run_conclusion").asText())) {
            gaps.add("CI run did not complete with a supported execution conclusion");
        }
        boolean external =
                policy.binding().has("oracle_artifacts")
                        && policy.binding()
                                .path("oracle_artifacts")
                                .equals(
                                        evidence.path("head")
                                                .path("oracle")
                                                .path("oracle_artifacts"))
                        && git.head()
                                .equals(
                                        evidence.path("head")
                                                .path("oracle")
                                                .path("head_sha")
                                                .asText());
        if (policy.binding().has("oracle_artifacts") && !external)
            gaps.add("Missing or changed approved regression artifact execution");
        List<String> changed =
                policy.changedOraclePaths(git, external && evidence.path("verified").asBoolean());
        for (String path : changed) {
            gaps.add(
                    "Approved oracle changed or was removed: "
                            + path
                            + "; obtain owner review/remapping at the trusted policy commit");
        }
        var contracts = result.putArray("contracts");
        String overall = "satisfied";
        for (JsonNode contract : policy.manifest().path("contracts")) {
            var row = contracts.addObject();
            for (String key :
                    List.of("id", "version", "owner", "expectation", "scope", "assumptions")) {
                if (contract.has(key)) row.set(key, contract.get(key));
            }
            String status = "satisfied";
            var checks = row.putArray("checks");
            for (JsonNode test : contract.path("tests")) {
                String name = test.path("id").asText();
                var check = checks.addObject().put("test_id", name);
                int matches = 0;
                String reported = "missing";
                for (JsonNode execution : evidence.path("execution")) {
                    if (execution.path("snapshot").asText().equals("head")
                            && execution.path("name").asText().equals(name)) {
                        matches++;
                        reported = execution.path("reported_status").asText();
                    }
                }
                String outcome = "incomplete";
                String action =
                        "Run the exact required test in approved CI and import fresh evidence";
                if (matches > 1) {
                    action =
                            "Resolve duplicate/conflicting test identities; import one execution per exact ID";
                } else if (gaps.isEmpty() && matches == 1) {
                    if (reported.equals("passed")) {
                        outcome = "satisfied";
                        action = "No action required";
                    } else if (reported.equals("failed")) {
                        outcome = "unsatisfied";
                        action = "Inspect the assertion failure and correct the contract violation";
                    }
                } else if (!gaps.isEmpty()) {
                    action = "Resolve evidence_gaps before interpreting this execution";
                }
                check.put("matches", matches)
                        .put("reported_status", reported)
                        .put("status", outcome)
                        .put("next_action", action)
                        .put(
                                "evidence_strength",
                                outcome.equals("incomplete")
                                        ? "unverified"
                                        : "approved_verifier_result");
                status = combine(status, outcome);
            }
            row.put("status", status);
            overall = combine(overall, status);
        }
        result.put("verification", overall);
        // Normalize absent optional evidence to JSON null before validating the public result.
        if (result.path("evidence_origin").isMissingNode()) result.putNull("evidence_origin");
        if (result.path("evidence_source").isMissingNode()) result.putNull("evidence_source");
        Schemas.validate(Schemas.resource("contract-verification"), result);
        return result;
    }

    private static String combine(String left, String right) {
        if (left.equals("unsatisfied") || right.equals("unsatisfied")) return "unsatisfied";
        if (left.equals("incomplete") || right.equals("incomplete")) return "incomplete";
        return "satisfied";
    }

    public static void write(Path output, JsonNode result) throws IOException {
        Json.write(output.resolve("contracts.json"), result);
        StringBuilder markdown = new StringBuilder("# Contract verification\n\n");
        markdown.append("Result: **")
                .append(result.path("verification").asText())
                .append("**. Shadow mode; release decision not evaluated.\n\n")
                .append("Candidate: `")
                .append(result.path("head_sha").asText())
                .append("`\n\n")
                .append("Policy: `")
                .append(result.path("policy").path("policy_commit").asText())
                .append("`\n\n");
        String run = result.path("evidence_source").path("run_id").asText();
        if (run.matches("[0-9]+")) {
            markdown.append("Evidence: [CI run](https://github.com/")
                    .append(result.path("policy").path("repository").asText())
                    .append("/actions/runs/")
                    .append(run)
                    .append("); artifact `")
                    .append(escape(result.path("evidence_source").path("artifact_id").asText()))
                    .append("`.\n\n");
        }
        for (JsonNode gap : result.path("evidence_gaps")) {
            markdown.append("- ").append(escape(gap.asText())).append('\n');
        }
        for (JsonNode contract : result.path("contracts")) {
            markdown.append("\n## ")
                    .append(escape(contract.path("id").asText()))
                    .append(": ")
                    .append(contract.path("status").asText())
                    .append("\n\n")
                    .append(escape(contract.path("expectation").asText()))
                    .append("\n\n");
            for (JsonNode check : contract.path("checks")) {
                markdown.append("- ")
                        .append(escape(check.path("test_id").asText()))
                        .append(": **")
                        .append(check.path("status").asText())
                        .append("** (reported ")
                        .append(check.path("reported_status").asText())
                        .append("). ")
                        .append(escape(check.path("next_action").asText()))
                        .append('\n');
            }
        }
        Files.writeString(output.resolve("contracts.md"), markdown);
    }

    private static String escape(String text) {
        return Markdown.safe(text);
    }
}
