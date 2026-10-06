package dev.undertow.changes;

import dev.undertow.findings.ReviewValidator;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;

/**
 * Shared validation for every publisher; source and policy authority remain provider independent.
 */
public final class PublicationValidation {
    private PublicationValidation() {}

    public static void validate(
            Report report, GitRepository git, Policy policy, EvidenceStore evidence)
            throws IOException {
        Schemas.validate(Schemas.resource("report"), Json.MAPPER.valueToTree(report));
        if (!report.baseSha().equals(git.base())
                || !report.headSha().equals(git.head())
                || !report.policyHashes().equals(policy.hashes())
                || !report.evidenceHash().equals(Json.hash(Json.stringify(evidence.entries())))
                || report.details() != null
                        && (!report.details().policyCommit().equals(git.policyCommit())
                                || !report.details().targetHead().equals(git.targetHead())))
            throw new IllegalArgumentException(
                    "Report provenance does not match pinned source and policy");
        var review = Json.MAPPER.createObjectNode();
        review.set("findings", Json.MAPPER.valueToTree(report.findings()));
        review.put("summary", report.summary());
        review.set("coverage_notes", Json.MAPPER.valueToTree(report.coverageGaps()));
        ReviewValidator.validate(review, git, policy, evidence);
    }
}
