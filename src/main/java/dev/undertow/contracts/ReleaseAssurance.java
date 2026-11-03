package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.CiEvidence;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Release eligibility is separate from verification and only exists under explicitly enabled
 * policy.
 */
public final class ReleaseAssurance {
    private ReleaseAssurance() {}

    public static ObjectNode decide(JsonNode verification, JsonNode approved, Instant now) {
        JsonNode policy = approved.path("document");
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("head_sha", verification.path("head_sha").asText())
                        .put("target_head", verification.path("target_head").asText())
                        .put("verification_sha256", hash(verification))
                        .put("decision", "not_evaluated")
                        .put("mode", "shadow");
        result.set("contract_policy", verification.path("policy").deepCopy());
        result.set("release_policy", approved.deepCopy());
        var checks = result.putArray("requirements");
        var used = result.putArray("exceptions");
        Set<String> exceptionIds = new HashSet<>();
        for (JsonNode exception : policy.path("exceptions")) {
            Instant.parse(exception.path("expires").asText());
            if (!exceptionIds.add(exception.path("id").asText()))
                throw new IllegalArgumentException("Duplicate release exception");
            boolean known = false;
            for (JsonNode id : policy.path("required_contracts"))
                if (id.asText().equals(exception.path("contract_id").asText())) known = true;
            if (!known)
                throw new IllegalArgumentException(
                        "Exception references an unknown required contract");
        }
        boolean held = false;
        for (JsonNode required : policy.path("required_contracts")) {
            String id = required.asText();
            String status = "incomplete";
            for (JsonNode contract : verification.path("contracts")) {
                if (contract.path("id").asText().equals(id))
                    status = contract.path("status").asText();
            }
            boolean excepted = false;
            if (status.equals("unsatisfied")) {
                for (JsonNode exception : policy.path("exceptions")) {
                    if (exception.path("contract_id").asText().equals(id)
                            && exception.path("head_sha").equals(verification.path("head_sha"))
                            && Instant.parse(exception.path("expires").asText()).isAfter(now)) {
                        excepted = true;
                        used.add(exception.deepCopy());
                    }
                }
            }
            checks.addObject()
                    .put("contract_id", id)
                    .put("verification", status)
                    .put("approved_exception", excepted)
                    .put("reason", requirementReason(status, excepted));
            if (!status.equals("satisfied") && !excepted) held = true;
        }
        if (policy.path("enabled").asBoolean()) {
            result.put("mode", "enforced").put("decision", held ? "held" : "eligible");
        }
        return result;
    }

    public static ObjectNode attest(
            GitRepository git, JsonNode decision, Path artifact, Path bundle, Path receipt)
            throws IOException {
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("state", "held")
                        .put("head_sha", git.head())
                        .put("target_head", git.targetHead())
                        .put("release_sha256", hash(decision));
        result.set("contract_policy", decision.path("contract_policy").deepCopy());
        result.set("release_policy", decision.path("release_policy").deepCopy());
        var reasons = result.putArray("reasons");
        if (!git.includesTarget())
            reasons.add(
                    "The final candidate does not include the pinned target tip; verify the merge candidate");
        if (!decision.path("decision").asText().equals("eligible"))
            reasons.add("An enabled release policy must declare eligibility");
        JsonNode evidence;
        try {
            evidence = CiEvidence.load(bundle, git, receipt);
        } catch (IOException | IllegalArgumentException e) {
            evidence = Json.MAPPER.createObjectNode();
        }
        JsonNode build = evidence.path("head").path("build");
        if (!evidence.path("verified").asBoolean()
                || !build.path("head_sha").asText().equals(git.head())
                || !build.path("artifact_sha256").asText().matches("[0-9a-f]{64}")
                || !evidence.path("contract_binding").equals(decision.path("contract_policy"))) {
            reasons.add("Missing trusted exact-head build provenance for this contract policy");
        }
        if (!Files.isRegularFile(artifact, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Artifact must be a regular file");
        String digest;
        try (var stream = Files.newInputStream(artifact)) {
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) sha.update(buffer, 0, count);
            digest = java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        result.put("artifact_sha256", digest);
        result.set("evidence_source", evidence.path("source_receipt"));
        if (!digest.equals(build.path("artifact_sha256").asText()))
            reasons.add("Artifact differs from the CI-tested build");
        if (reasons.isEmpty()) result.put("state", "issued");
        return result;
    }

    public static String hash(JsonNode value) {
        try {
            return Json.hash(Json.stringify(value));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid structured result", e);
        }
    }

    private static String requirementReason(String status, boolean excepted) {
        if (status.equals("incomplete")) return "Required unknowns always hold release";
        if (excepted) return "Exact-subject owner exception";
        return "Approved required contract";
    }
}
