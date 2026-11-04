package dev.undertow.changes;

/**
 * targetHead is the current target tip; analysisDiffBase can be its merge base. trustedPolicyCommit
 * identifies policy authority independently of the diff.
 */
public record ChangeSnapshot(
        String sourceHead,
        String targetHead,
        String analysisDiffBase,
        String trustedPolicyCommit,
        String providerDiffVersion) {
    public ChangeSnapshot {
        for (String sha :
                java.util.List.of(sourceHead, targetHead, analysisDiffBase, trustedPolicyCommit))
            if (!sha.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid snapshot commit");
    }
}
