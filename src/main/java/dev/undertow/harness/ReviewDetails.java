package dev.undertow.harness;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.policy.RuleCatalog;
import java.util.List;

/** Report v2 metadata; publication is deliberately stored in a separate receipt. */
public record ReviewDetails(
        String mode,
        String failureCause,
        String runtimeVersion,
        String analyzerVersion,
        String policyCommit,
        String targetHead,
        List<RuleCatalog.Coverage> ruleCoverage,
        JsonNode ciEvidence,
        java.util.Map<String, String> dependencyVersions) {
    public ReviewDetails {
        ruleCoverage = List.copyOf(ruleCoverage);
        ciEvidence = ciEvidence.deepCopy();
        dependencyVersions =
                dependencyVersions == null
                        ? java.util.Map.of()
                        : java.util.Map.copyOf(dependencyVersions);
    }

    @Override
    public JsonNode ciEvidence() {
        return ciEvidence.deepCopy();
    }
}
