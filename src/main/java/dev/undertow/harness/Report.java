package dev.undertow.harness;

import dev.undertow.findings.Finding;
import java.util.List;
import java.util.Map;

public record Report(
        int schemaVersion,
        String runId,
        String status,
        String baseSha,
        String headSha,
        String modelIdentity,
        String promptVersion,
        String promptHash,
        Map<String, String> policyHashes,
        String summary,
        List<Finding> findings,
        List<String> coverageGaps,
        List<String> changedFiles,
        List<String> inspectedSources,
        List<String> toolFailures,
        int modelCalls,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        long estimatedInputTokens,
        long elapsedMillis,
        String evidenceHash,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                ReviewDetails details) {
    public Report {
        policyHashes = Map.copyOf(policyHashes);
        findings = List.copyOf(findings);
        coverageGaps = List.copyOf(coverageGaps);
        changedFiles = List.copyOf(changedFiles);
        inspectedSources = List.copyOf(inspectedSources);
        toolFailures = List.copyOf(toolFailures);
    }
}
