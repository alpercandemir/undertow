package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import java.io.IOException;
import java.util.HashSet;

public final class PilotStudy {
    private PilotStudy() {}

    public static JsonNode measure(JsonNode study) throws IOException {
        if (study.path("schema_version").asInt() != 1
                || !study.path("cases").isArray()
                || study.path("cases").size() > 10000)
            throw new IllegalArgumentException("Unsupported bounded pilot study");
        var changes = new HashSet<String>();
        var cases = new HashSet<String>();
        int benign = 0, violations = 0, falseHolds = 0, escaped = 0, unresolved = 0, incomplete = 0;
        var latencies = new java.util.ArrayList<Long>();
        for (JsonNode row : study.path("cases")) {
            JsonNode report = row.path("verification");
            Schemas.validate(Schemas.resource("contract-verification"), report);
            String change =
                    report.path("policy").path("repository").asText()
                            + "/"
                            + report.path("head_sha").asText();
            String id = row.path("contract_id").asText();
            if (!cases.add(change + "/" + id))
                throw new IllegalArgumentException(
                        "Duplicate pilot case; repeats are not independent changes");
            changes.add(change);
            String status = "incomplete";
            for (JsonNode contract : report.path("contracts"))
                if (contract.path("id").asText().equals(id))
                    status = contract.path("status").asText();
            if (status.equals("incomplete")) incomplete++;
            var reviewers = new HashSet<String>();
            var verdicts = new HashSet<String>();
            for (JsonNode review : row.path("reviews")) {
                String reviewer = review.path("reviewer").asText();
                if (reviewer.isBlank() || !reviewers.add(reviewer))
                    throw new IllegalArgumentException("Independent named reviewers are required");
                verdicts.add(review.path("verdict").asText());
            }
            if (reviewers.size() < 2
                    || verdicts.size() != 1
                    || (!verdicts.contains("benign") && !verdicts.contains("violation")))
                unresolved++;
            else if (verdicts.contains("benign")) {
                benign++;
                if (!status.equals("satisfied")) falseHolds++;
            } else {
                violations++;
                if (status.equals("satisfied")) escaped++;
            }
            if (row.has("latency_ms")) {
                if (!row.path("latency_ms").isIntegralNumber()
                        || row.path("latency_ms").asLong() < 0)
                    throw new IllegalArgumentException("Invalid pilot latency");
                latencies.add(row.path("latency_ms").asLong());
            }
        }
        latencies.sort(Long::compareTo);
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("independent_changes", changes.size())
                        .put("cases", cases.size())
                        .put("benign_cases", benign)
                        .put("violation_cases", violations)
                        .put("false_holds", falseHolds)
                        .put("escaped_violations", escaped)
                        .put("incomplete", incomplete)
                        .put("unresolved_assessments", unresolved)
                        .put("promotion", "owner_review_required")
                        .put("dataset_kind", study.path("dataset_kind").asText("unspecified"))
                        .put("automatic_gate_authority", false)
                        .put("cost_usd", (String) null);
        if (latencies.isEmpty()) result.putNull("p95_latency_ms");
        else
            result.put(
                    "p95_latency_ms", latencies.get((int) Math.ceil(latencies.size() * .95) - 1));
        if (benign == 0) result.putNull("false_hold_rate");
        else {
            double n = benign, p = falseHolds / n, z = 1.96, denominator = 1 + z * z / n;
            double center = (p + z * z / (2 * n)) / denominator;
            double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator;
            result.put("false_hold_rate", p);
            result.putArray("false_hold_ci95")
                    .add(Math.max(0, center - margin))
                    .add(Math.min(1, center + margin));
        }
        JsonNode thresholds = study.path("tolerances");
        boolean specified = true;
        for (String name :
                java.util.List.of(
                        "minimum_changes",
                        "minimum_benign",
                        "minimum_violations",
                        "maximum_false_holds",
                        "maximum_escaped")) {
            if (!thresholds.path(name).isIntegralNumber() || thresholds.path(name).asInt(-1) < 0)
                specified = false;
        }
        result.put(
                "tolerances_met",
                specified
                        && unresolved == 0
                        && incomplete == 0
                        && changes.size() >= Math.max(1, thresholds.path("minimum_changes").asInt())
                        && benign >= Math.max(1, thresholds.path("minimum_benign").asInt())
                        && violations >= Math.max(1, thresholds.path("minimum_violations").asInt())
                        && falseHolds <= thresholds.path("maximum_false_holds").asInt()
                        && escaped <= thresholds.path("maximum_escaped").asInt());
        return result;
    }
}
