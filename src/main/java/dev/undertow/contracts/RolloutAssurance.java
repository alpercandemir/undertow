package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import java.time.Duration;
import java.time.Instant;

/**
 * Evaluates trusted monitoring exports. Never deploys, executes a halt, or claims rollback repairs
 * payments.
 */
public final class RolloutAssurance {
    private RolloutAssurance() {}

    public static ObjectNode evaluate(
            JsonNode attestation, JsonNode approved, String raw, JsonNode receipt, Instant now)
            throws java.io.IOException {
        JsonNode observations = Json.parse(raw), policy = approved.path("document");
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("decision", "held")
                        .put("attestation_sha256", ReleaseAssurance.hash(attestation));
        result.set("operations_policy", approved.deepCopy());
        var reasons = result.putArray("reasons");
        if (!attestation.path("state").asText().equals("issued"))
            reasons.add("Release artifact has no eligible attestation");
        if (!receipt.path("verified_source").asText().equals("delivery_monitor_export")
                || !receipt.path("observation_sha256").asText().equals(Json.hash(raw))
                || !receipt.path("attestation_sha256")
                        .asText()
                        .equals(ReleaseAssurance.hash(attestation))) {
            reasons.add("Missing or mismatched trusted delivery/monitoring receipt");
        }
        for (String key : java.util.List.of("artifact_sha256", "head_sha")) {
            if (!observations.path(key).equals(attestation.path(key)))
                reasons.add("Deployment identity mismatch: " + key);
        }
        for (String key : java.util.List.of("environment", "contract_id")) {
            if (!observations.path(key).equals(policy.path(key)))
                reasons.add("Monitoring scope mismatch: " + key);
        }
        if (observations.path("deployment_id").asText().isBlank())
            reasons.add("Missing deployment identity");
        long traffic = observations.path("events").asLong(-1),
                violations = observations.path("violations").asLong(-1);
        if (!observations.path("events").isIntegralNumber()
                || !observations.path("violations").isIntegralNumber()
                || !observations.path("events").canConvertToLong()
                || !observations.path("violations").canConvertToLong()
                || traffic < 0
                || violations < 0
                || violations > traffic) reasons.add("Invalid observation counts");
        Instant start, end;
        try {
            start = Instant.parse(observations.path("started_at").asText());
            end = Instant.parse(observations.path("ended_at").asText());
        } catch (java.time.DateTimeException e) {
            reasons.add("Missing or invalid observation window");
            return result;
        }
        if (end.isBefore(start)
                || end.isAfter(now)
                || Duration.between(end, now).getSeconds()
                        > policy.path("maximum_age_seconds").asLong())
            reasons.add("Stale or invalid observation window");
        if (!reasons.isEmpty()) return result;
        if (violations == 0) {
            if (traffic < policy.path("minimum_events").asLong()
                    || Duration.between(start, end).getSeconds()
                            < policy.path("minimum_window_seconds").asLong()) {
                reasons.add(
                        "Insufficient relevant traffic or observation time; a quiet canary is incomplete");
            } else result.put("decision", "accepted");
            return result;
        }
        result.put("decision", "halt_required");
        reasons.add(
                "Observed business-contract violations; stop further effects and reconcile existing ones");
        try {
            Instant first = Instant.parse(observations.path("first_violation_at").asText());
            Instant halted = Instant.parse(observations.path("halt_confirmed_at").asText());
            if (first.isBefore(start)
                    || first.isAfter(end)
                    || halted.isBefore(first)
                    || halted.isAfter(now)
                    || Duration.between(first, halted).getSeconds()
                            > policy.path("maximum_detection_seconds").asLong()) {
                reasons.add("Halt confirmation exceeds the declared containment window");
                return result;
            }
            result.put("decision", "contained");
            if (policy.path("reconciliation_required").asBoolean()) {
                String link = observations.path("reconciliation_evidence").asText();
                Instant reconciled = Instant.parse(observations.path("reconciled_at").asText());
                var uri = java.net.URI.create(link);
                if (!"https".equals(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || reconciled.isBefore(halted)
                        || reconciled.isAfter(now)) {
                    reasons.add("Reconciliation evidence is missing or invalid");
                    return result;
                }
            }
            result.put("decision", "recovered");
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            reasons.add("Halt/reconciliation confirmation is incomplete");
        }
        return result;
    }
}
