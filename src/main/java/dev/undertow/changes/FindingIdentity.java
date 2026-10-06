package dev.undertow.changes;

import dev.undertow.findings.Finding;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.util.List;

public final class FindingIdentity {
    private FindingIdentity() {}

    public static String fingerprint(ChangeRequestRef ref, Finding finding, GitRepository git)
            throws IOException {
        var location = finding.location();
        String[] lines = git.source(location.snapshot(), location.path()).split("\n", -1);
        String context =
                String.join(
                                "\n",
                                java.util.Arrays.copyOfRange(
                                        lines,
                                        Math.max(0, location.startLine() - 4),
                                        Math.min(lines.length, location.endLine() + 3)))
                        .strip();
        String span =
                String.join(
                        "\n",
                        java.util.Arrays.copyOfRange(
                                lines, location.startLine() - 1, location.endLine()));
        int occurrence = 0;
        for (int i = 0; i < location.startLine() - 1; i++)
            if (lines[i].equals(lines[location.startLine() - 1])) occurrence++;
        return Json.hash(
                Json.stringify(
                        List.of(
                                ref.provider(),
                                ref.instance().toString(),
                                ref.stableRepositoryId(),
                                ref.number(),
                                finding.ruleIds().stream().sorted().toList(),
                                finding.category().name(),
                                location.path(),
                                context,
                                span,
                                occurrence)));
    }
}
