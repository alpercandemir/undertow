package dev.undertow.changes;

import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.policy.Policy;
import dev.undertow.tools.GitRepository;
import java.io.IOException;

/** Git-host capabilities are independent of ModelClient and the investigation tool registry. */
public final class ChangeAdapters {
    private ChangeAdapters() {}

    public record Capabilities(
            boolean summaryComments,
            boolean commitStatus,
            boolean annotations,
            boolean discussionUpdates) {}

    public interface MetadataReader {
        ChangeRequest read(ChangeRequestRef ref) throws IOException, InterruptedException;

        Capabilities capabilities();
    }

    @FunctionalInterface
    public interface SnapshotAccess {
        GitRepository open(ChangeSnapshot snapshot) throws IOException;
    }

    @FunctionalInterface
    public interface SummaryPublisher {
        Publication publish(
                ChangeRequestRef ref,
                Report report,
                GitRepository git,
                Policy policy,
                EvidenceStore evidence,
                String detailsUrl)
                throws IOException, InterruptedException;
    }

    public record Publication(
            String status,
            long commentId,
            String reviewedHead,
            String runId,
            java.util.Map<String, String> findingFingerprints,
            String reason) {
        public Publication {
            findingFingerprints = java.util.Map.copyOf(findingFingerprints);
        }
    }
}
