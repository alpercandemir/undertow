package dev.undertow.findings;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReviewValidator {
    public record Assessment(String id, String status, String reason, List<String> evidenceRefs) {
        public Assessment {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    public record Review(
            List<Finding> findings,
            String summary,
            List<String> coverageNotes,
            List<Assessment> ruleAssessments) {
        public Review(List<Finding> findings, String summary, List<String> coverageNotes) {
            this(findings, summary, coverageNotes, List.of());
        }

        public Review {
            findings = List.copyOf(findings);
            coverageNotes = List.copyOf(coverageNotes);
            ruleAssessments = ruleAssessments == null ? List.of() : List.copyOf(ruleAssessments);
        }
    }

    private ReviewValidator() {}

    public static Review validate(
            JsonNode node, GitRepository git, Policy policy, EvidenceStore store)
            throws IOException {
        Schemas.validate(Schemas.resource("review"), node);
        Review review = Json.MAPPER.treeToValue(node, Review.class);
        if (review.findings().size() > policy.maxFindings()) {
            throw new IllegalArgumentException("Too many findings");
        }
        Set<String> ids = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        Set<String> changed = new HashSet<>(git.changedFiles());
        for (Finding finding : review.findings()) {
            var location = finding.location();
            String duplicateKey =
                    location.path()
                            + ":"
                            + location.startLine()
                            + ":"
                            + finding.category()
                            + ":"
                            + finding.ruleIds();
            if (!ids.add(finding.id()) || !duplicates.add(duplicateKey)) {
                throw new IllegalArgumentException("Duplicate finding");
            }
            validateLocation(location, git, changed);
            for (String rule : finding.ruleIds()) {
                JsonNode data = policy.rule(rule);
                if (!policy.catalog().active(data, location.path()))
                    throw new IllegalArgumentException(
                            "Finding cites disabled, excepted or out-of-scope rule");
                for (JsonNode tool : data.path("required_tools")) {
                    if (finding.evidenceRefs().stream()
                            .noneMatch(ref -> store.get(ref).kind().equals(tool.asText())))
                        throw new IllegalArgumentException("Finding lacks required rule evidence");
                }
            }
            validateEvidence(finding, store);
            validateSeverity(finding, policy);
            validateProposedTests(finding.regressionTests());
        }
        Set<String> assessed = new HashSet<>();
        for (Assessment assessment : review.ruleAssessments()) {
            if (!assessed.add(assessment.id()))
                throw new IllegalArgumentException("Duplicate rule assessment");
            JsonNode rule = policy.rule(assessment.id());
            if (rule.path("execution").asText().equals("deterministic"))
                throw new IllegalArgumentException("Model cannot assess deterministic rules");
            for (String ref : assessment.evidenceRefs()) store.get(ref);
            if (assessment.status().equals("evaluated")) {
                var scope =
                        policy.catalog().selection(git.changedFiles()).stream()
                                .filter(row -> row.id().equals(assessment.id()))
                                .findFirst()
                                .orElseThrow();
                if (!scope.status().equals("selected"))
                    throw new IllegalArgumentException(
                            "Model assessed an out-of-scope or excepted rule");
                for (JsonNode tool : rule.path("required_tools"))
                    if (assessment.evidenceRefs().stream()
                            .noneMatch(ref -> store.get(ref).kind().equals(tool.asText())))
                        throw new IllegalArgumentException(
                                "Rule assessment lacks required evidence");
                if (rule.path("required_tools").toString().contains("read_source")) {
                    for (String path : scope.paths()) {
                        boolean covered =
                                assessment.evidenceRefs().stream()
                                        .map(store::get)
                                        .anyMatch(
                                                entry ->
                                                        entry.kind().equals("read_source")
                                                                && entry.content()
                                                                        .path("path")
                                                                        .asText()
                                                                        .equals(path)
                                                                && (entry.commit()
                                                                                .equals(git.head())
                                                                        || entry.commit()
                                                                                .equals(
                                                                                        git
                                                                                                .base())));
                        if (!covered)
                            throw new IllegalArgumentException(
                                    "Rule assessment lacks source evidence for declared scope");
                    }
                }
            }
        }
        return review;
    }

    private static void validateLocation(
            Finding.Location location, GitRepository git, Set<String> changed) throws IOException {
        if (!location.commit().equals(git.commit(location.snapshot()))
                || !changed.contains(location.path())) {
            throw new IllegalArgumentException(
                    "Location must be in a changed file at its pinned commit");
        }
        String content = git.source(location.snapshot(), location.path());
        if (location.endLine() < location.startLine()
                || location.endLine() > content.split("\n", -1).length) {
            throw new IllegalArgumentException("Invalid finding line range");
        }
    }

    private static void validateEvidence(Finding finding, EvidenceStore store) {
        boolean hasSourceEvidence = false;
        boolean hasDiffEvidence = false;
        boolean hasMigrationDocuments = false;
        for (String reference : finding.evidenceRefs()) {
            EvidenceStore.Evidence entry = store.get(reference);
            JsonNode content = entry.content();
            switch (entry.kind()) {
                case "read_source" -> {
                    var location = finding.location();
                    hasSourceEvidence |=
                            content.path("path").asText().equals(location.path())
                                    && entry.commit().equals(location.commit())
                                    && content.path("start_line").asInt() <= location.startLine()
                                    && content.path("end_line").asInt() >= location.endLine();
                }
                case "get_diff" -> {
                    for (JsonNode file : content.path("files")) {
                        hasDiffEvidence |=
                                file.path("path").asText().equals(finding.location().path())
                                        && !file.path("diff").asText().isBlank();
                    }
                }
                case "fetch_migration_evidence" ->
                        hasMigrationDocuments |= content.path("documents").size() > 0;
                default -> {
                    // Other evidence can support a finding but cannot replace its required
                    // grounding.
                }
            }
        }
        if (!hasSourceEvidence || !hasDiffEvidence) {
            throw new IllegalArgumentException("Finding requires source-range and diff evidence");
        }
        if (finding.category() == Finding.Category.DEPENDENCY_COMPATIBILITY
                && !hasMigrationDocuments) {
            throw new IllegalArgumentException(
                    "Migration finding requires retrieved official documentation");
        }
    }

    private static void validateSeverity(Finding finding, Policy policy) {
        boolean isCritical =
                finding.severity() == Finding.Severity.CRITICAL
                        || finding.severity() == Finding.Severity.HIGH_CRITICAL;
        if (isCritical
                && (finding.confidence() != Finding.Confidence.HIGH
                        || finding.evidenceStatus() == Finding.EvidenceStatus.UNVERIFIED)) {
            throw new IllegalArgumentException(
                    "Critical findings need high confidence and verified technical evidence");
        }
        if (finding.severity() == Finding.Severity.HIGH_CRITICAL
                && (policy.business().path("catastrophic_scope").asText().isBlank()
                        || finding.evidenceStatus() != Finding.EvidenceStatus.OBSERVED)) {
            throw new IllegalArgumentException(
                    "High Critical requires owner-declared catastrophic scope and observed evidence");
        }
    }

    private static void validateProposedTests(List<Finding.RegressionTest> tests) {
        for (Finding.RegressionTest test : tests) {
            if (!Set.of("proposed", "not executable here").contains(test.status())) {
                throw new IllegalArgumentException(
                        "Agent cannot claim generated tests were executed");
            }
        }
    }
}
