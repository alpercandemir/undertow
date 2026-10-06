package dev.undertow.reporting;

import dev.undertow.changes.ChangeRequestRef;
import dev.undertow.harness.Report;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Markdown {
    private Markdown() {}

    public static String safe(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("@", "&#64;")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace("`", "&#96;")
                .replace("*", "&#42;")
                .replace("_", "&#95;")
                .replace("\\", "&#92;")
                .replace("\r", "")
                .replace("\n", " ");
    }

    public static String render(Report report) {
        StringBuilder text =
                new StringBuilder("<!-- undertow-review:v1 -->\n# Undertow change-risk review\n\n");
        text.append("**Status: ")
                .append(report.status())
                .append("** · Advisory\n\n")
                .append("Base: ")
                .append(report.baseSha())
                .append("  \nHead: ")
                .append(report.headSha())
                .append("\n\n")
                .append(safe(report.summary()))
                .append("\n\n");
        if (report.findings().isEmpty()) {
            text.append(
                    report.status().equals("complete")
                            ? "No findings reported.\n\n"
                            : "No findings reported; incomplete coverage is not a clean review.\n\n");
        }
        for (var f : report.findings()) {
            text.append("## ")
                    .append(safe(f.title()))
                    .append("\n\n**Risk:** ")
                    .append(f.severity().name().replace('_', ' '))
                    .append(" · **Category:** ")
                    .append(f.category())
                    .append(" · **Confidence:** ")
                    .append(f.confidence())
                    .append("\n\n")
                    .append("Location: ")
                    .append(safe(f.location().path()))
                    .append(":")
                    .append(f.location().startLine())
                    .append(" at ")
                    .append(f.location().commit())
                    .append("\n\n")
                    .append("Trigger: ")
                    .append(safe(f.trigger()))
                    .append("\n\n")
                    .append("Changed behavior: ")
                    .append(safe(f.changedBehavior()))
                    .append("\n\n")
                    .append("Consequence: ")
                    .append(safe(f.technicalConsequence()))
                    .append(" ")
                    .append(safe(f.businessConsequence()))
                    .append("\n\n")
                    .append("Evidence: ")
                    .append(safe(String.join(", ", f.evidenceRefs())))
                    .append(" (")
                    .append(f.evidenceStatus())
                    .append(")\n\n")
                    .append("Confidence: ")
                    .append(safe(f.confidenceExplanation()))
                    .append("\n\n")
                    .append("Rules: ")
                    .append(safe(String.join(", ", f.ruleIds())))
                    .append(" · ")
                    .append(f.guidelineLabel())
                    .append("\n\n")
                    .append("Assumptions: ")
                    .append(safe(String.join("; ", f.assumptions())))
                    .append("\n\n")
                    .append("Recommended change: ")
                    .append(safe(f.recommendedChange()))
                    .append("\n\n");
            for (var test : f.regressionTests()) {
                text.append("- Regression (")
                        .append(safe(test.level()))
                        .append(", ")
                        .append(safe(test.status()))
                        .append("): ")
                        .append(safe(test.setup()))
                        .append(" → ")
                        .append(safe(test.stimulus()))
                        .append(" → ")
                        .append(safe(test.expectedOutcome()))
                        .append("\n");
            }
            text.append('\n');
        }
        if (!report.coverageGaps().isEmpty()) {
            text.append("## Coverage gaps\n\n");
            report.coverageGaps().forEach(g -> text.append("- ").append(safe(g)).append('\n'));
            text.append('\n');
        }
        if (!report.toolFailures().isEmpty()) {
            text.append("## Tool failures\n\n");
            report.toolFailures().forEach(g -> text.append("- ").append(safe(g)).append('\n'));
            text.append('\n');
        }
        if (report.details() != null) {
            text.append("## Rule coverage (trusted policy ")
                    .append(report.details().policyCommit())
                    .append(")\n\n");
            report.details()
                    .ruleCoverage()
                    .forEach(
                            row -> {
                                text.append("- ")
                                        .append(safe(row.id()))
                                        .append(" v")
                                        .append(safe(row.version()))
                                        .append(": ")
                                        .append(row.status())
                                        .append(" — ")
                                        .append(safe(row.reason()))
                                        .append('\n');
                                row.exceptions()
                                        .forEach(
                                                exception ->
                                                        text.append("  - Exception: ")
                                                                .append(safe(exception))
                                                                .append('\n'));
                            });
            text.append("\n## CI execution and analyzer evidence\n\n")
                    .append("Provenance: ")
                    .append(safe(report.details().ciEvidence().path("provenance").asText()))
                    .append("\n\n");
            report.details()
                    .ciEvidence()
                    .path("execution")
                    .forEach(
                            test ->
                                    text.append("- CI test ")
                                            .append(safe(test.path("name").asText()))
                                            .append(": ")
                                            .append(safe(test.path("status").asText()))
                                            .append(" (executed outside agent)\n"));
            report.details()
                    .ciEvidence()
                    .path("analyzer_findings")
                    .forEach(
                            bug ->
                                    text.append("- SpotBugs ")
                                            .append(safe(bug.path("type").asText()))
                                            .append(" at ")
                                            .append(safe(bug.path("path").asText()))
                                            .append(":")
                                            .append(bug.path("line").asInt())
                                            .append("; verified=")
                                            .append(bug.path("verified").asBoolean())
                                            .append('\n'));
            text.append("\nMode: ")
                    .append(report.details().mode())
                    .append(" · Failure cause: ")
                    .append(report.details().failureCause())
                    .append(" · Runtime: ")
                    .append(safe(report.details().runtimeVersion()))
                    .append(" · Analyzer: ")
                    .append(safe(report.details().analyzerVersion()))
                    .append('\n');
        }
        return text.append("Model: ")
                .append(safe(report.modelIdentity()))
                .append(" · Calls: ")
                .append(report.modelCalls())
                .append(" model / ")
                .append(report.toolCalls())
                .append(" tools · Elapsed: ")
                .append(report.elapsedMillis())
                .append(" ms\n")
                .toString();
    }

    private static String excerpt(String value, int limit) {
        return safe(value.length() <= limit ? value : value.substring(0, limit) + "…");
    }

    public static String summary(
            Report report,
            ChangeRequestRef ref,
            String detailsUrl,
            Map<String, String> fingerprints,
            String previous) {
        if (!detailsUrl.isBlank()) {
            URI url = URI.create(detailsUrl);
            if (!"https".equals(url.getScheme())
                    || !"github.com".equals(url.getHost())
                    || url.getUserInfo() != null
                    || url.getPort() != -1
                    || !url.getPath()
                            .matches(
                                    "/"
                                            + java.util.regex.Pattern.quote(ref.repository())
                                            + "/actions/runs/[0-9]+"))
                throw new IllegalArgumentException(
                        "Detailed report URL must identify this repository's GitHub Actions run");
        }
        long important =
                report.findings().stream()
                        .filter(
                                f ->
                                        Set.of("HIGH", "CRITICAL", "HIGH_CRITICAL")
                                                .contains(f.severity().name()))
                        .count();
        var text =
                new StringBuilder("<!-- undertow-review:v1 -->\n<!-- undertow-run:")
                        .append(report.runId())
                        .append(" -->\n<!-- undertow-head:")
                        .append(report.headSha())
                        .append(" -->\n<!-- undertow-target:")
                        .append(
                                report.details() == null
                                        ? report.baseSha()
                                        : report.details().targetHead())
                        .append(" -->\n## Undertow review\n\n**Review: ")
                        .append(report.status())
                        .append(" · Advisory · ")
                        .append(important)
                        .append(" important findings**\n\nReviewed commit: `")
                        .append(report.headSha())
                        .append("` · ")
                        .append(report.details() == null ? "legacy v1" : report.details().mode())
                        .append("\n\n");
        if (report.details() != null && !report.details().failureCause().equals("none"))
            text.append("**Review failure cause: ")
                    .append(safe(report.details().failureCause()))
                    .append(
                            ".** Inspect the retained report; zero findings do not establish success.\n\n");
        if (!detailsUrl.isBlank())
            text.append("[Detailed report and evidence artifacts](")
                    .append(detailsUrl)
                    .append("#artifacts)\n\n");
        else
            text.append(
                    "Detailed report: retain report.md and evidence.json as CI artifacts. No artifact link supplied.\n\n");
        if (!report.coverageGaps().isEmpty()) {
            text.append("**Coverage gaps: ").append(report.coverageGaps().size()).append("**\n\n");
            report.coverageGaps().stream()
                    .limit(5)
                    .forEach(g -> text.append("- ").append(excerpt(g, 300)).append('\n'));
            text.append('\n');
        }
        text.append(excerpt(report.summary(), 600)).append("\n\n");
        if (report.findings().isEmpty())
            text.append(
                    report.status().equals("complete")
                            ? "No findings within the declared scope.\n\n"
                            : "No findings; incomplete, failed or skipped review is not approval.\n\n");
        for (var f : report.findings()) {
            String path =
                    java.util.Arrays.stream(f.location().path().split("/"))
                            .map(
                                    s ->
                                            URLEncoder.encode(s, StandardCharsets.UTF_8)
                                                    .replace("+", "%20"))
                            .collect(java.util.stream.Collectors.joining("/"));
            text.append("<!-- undertow-finding:")
                    .append(fingerprints.get(f.id()))
                    .append(" -->\n### ")
                    .append(excerpt(f.title(), 160))
                    .append("\n\n**")
                    .append(f.severity())
                    .append(" · ")
                    .append(f.confidence())
                    .append(" confidence** · [Code](https://github.com/")
                    .append(ref.repository())
                    .append("/blob/")
                    .append(f.location().commit())
                    .append('/')
                    .append(path)
                    .append("#L")
                    .append(f.location().startLine())
                    .append(")\n\n")
                    .append("Trigger: ")
                    .append(excerpt(f.trigger(), 600))
                    .append("\n\nImpact: ")
                    .append(excerpt(f.technicalConsequence(), 500))
                    .append(' ')
                    .append(excerpt(f.businessConsequence(), 500))
                    .append("\n\nConfidence: ")
                    .append(excerpt(f.confidenceExplanation(), 400))
                    .append("\n\nEvidence: ")
                    .append(excerpt(String.join(", ", f.evidenceRefs()), 300))
                    .append(" in evidence.json (")
                    .append(f.evidenceStatus())
                    .append("); rules ")
                    .append(excerpt(String.join(", ", f.ruleIds()), 200))
                    .append("\n\n");
            f.regressionTests().stream()
                    .limit(2)
                    .forEach(
                            test ->
                                    text.append("- Proposed regression: ")
                                            .append(excerpt(test.stimulus(), 300))
                                            .append(" → ")
                                            .append(excerpt(test.expectedOutcome(), 400))
                                            .append("\n"));
            // Deterministic overlap preserves both sources, rather than counting the same location
            // twice.
            if (report.details() != null)
                report.details()
                        .ciEvidence()
                        .path("analyzer_findings")
                        .forEach(
                                bug -> {
                                    if (bug.path("path").asText().equals(f.location().path())
                                            && bug.path("line").asInt() >= f.location().startLine()
                                            && bug.path("line").asInt() <= f.location().endLine())
                                        text.append("- Overlapping analyzer source: SpotBugs ")
                                                .append(excerpt(bug.path("type").asText(), 100))
                                                .append(" (verified=")
                                                .append(bug.path("verified").asBoolean())
                                                .append(")\n");
                                });
            text.append('\n');
        }
        Set<String> earlier = new HashSet<>();
        var matcher =
                java.util.regex.Pattern.compile("<!-- undertow-finding:([0-9a-f]{64}) -->")
                        .matcher(previous);
        while (matcher.find()) earlier.add(matcher.group(1));
        earlier.removeAll(fingerprints.values());
        if (!earlier.isEmpty())
            text.append(
                            report.status().equals("complete")
                                    ? "Previously reported findings no longer observed within this review's declared scope: "
                                    : "Previously reported findings absent from this incomplete review; resolution unverified: ")
                    .append(earlier.size())
                    .append(".\n\n");
        text.append(
                "AI findings and BLOCKER labels do not block merging. Checks/annotations are unavailable; use source links and artifacts.\n");
        if (text.length() > 55000)
            throw new IllegalArgumentException("Summary exceeds bounded comment size");
        return text.toString();
    }
}
