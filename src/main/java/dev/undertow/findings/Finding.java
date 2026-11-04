package dev.undertow.findings;

import java.util.List;

public record Finding(
        String id,
        String title,
        String language,
        Severity severity,
        Category category,
        Confidence confidence,
        String confidenceExplanation,
        EvidenceStatus evidenceStatus,
        GuidelineLabel guidelineLabel,
        List<String> ruleIds,
        Location location,
        String trigger,
        String changedBehavior,
        String technicalConsequence,
        String businessConsequence,
        List<String> assumptions,
        List<String> evidenceRefs,
        String recommendedChange,
        List<RegressionTest> regressionTests) {
    public Finding {
        ruleIds = List.copyOf(ruleIds);
        assumptions = List.copyOf(assumptions);
        evidenceRefs = List.copyOf(evidenceRefs);
        regressionTests = List.copyOf(regressionTests);
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL,
        HIGH_CRITICAL
    }

    public enum Category {
        CORRECTNESS,
        SECURITY,
        DATA_INTEGRITY,
        CONCURRENCY,
        TRANSACTIONS,
        RELIABILITY,
        DEPENDENCY_COMPATIBILITY,
        API_CONTRACT,
        PERFORMANCE,
        MAINTAINABILITY,
        STYLE
    }

    public enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum EvidenceStatus {
        OBSERVED,
        INFERRED,
        UNVERIFIED
    }

    public enum GuidelineLabel {
        BLOCKER,
        MAJOR,
        MINOR,
        SUGGESTION
    }

    public record Location(
            String commit, String path, String snapshot, int startLine, int endLine) {}

    public record RegressionTest(
            String setup, String stimulus, String expectedOutcome, String level, String status) {}
}
