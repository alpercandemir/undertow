package dev.undertow.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** Prepares authorized data and imports validated output from an independently isolated runner. */
public final class ServiceOrchestrator {
    @FunctionalInterface
    public interface Runner {
        ObjectNode run(Path repository, Path manifest, Path output) throws Exception;
    }

    @FunctionalInterface
    public interface Publisher {
        ObjectNode publish(
                ObjectNode lease, ObjectNode repository, ObjectNode artifact, Path workspace)
                throws Exception;
    }

    private final ServiceControl control;
    private final Path workspaces;
    private final Runner runner;
    private final Publisher publisher;
    private final String mode;

    public ServiceOrchestrator(
            ServiceControl control,
            Path workspaces,
            Runner runner,
            Publisher publisher,
            String mode)
            throws IOException {
        this.control = control;
        this.workspaces = workspaces.toAbsolutePath();
        this.runner = runner;
        this.publisher = publisher;
        this.mode = mode;
        Files.createDirectories(this.workspaces);
    }

    public boolean tick() throws Exception {
        control.processEvents();
        control.maintenance();
        if (publisher != null) {
            ObjectNode lease = control.leasePublication("publisher-" + UUID.randomUUID());
            if (lease != null) {
                publish(lease);
                return true;
            }
        }
        if (runner == null) return false;
        ObjectNode lease = control.lease("worker-" + UUID.randomUUID());
        if (lease == null) return false;
        Path workspace = Files.createTempDirectory(workspaces, "review-");
        String stage = "preparation_failed";
        try {
            control.verifyExecutionAccess(lease);
            ObjectNode repository = control.executionRepository(lease);
            Path git = workspace.resolve("source.git");
            control.gateway()
                    .prepare(
                            repository.path("installation").asLong(),
                            repository.path("id").asLong(),
                            (ObjectNode) lease.path("snapshot"),
                            git);
            ObjectNode manifest = manifest(lease, control.preparation(lease));
            ObjectNode ci =
                    control.gateway()
                            .importCi(
                                    repository.path("installation").asLong(),
                                    repository.path("id").asLong(),
                                    (ObjectNode) lease.path("snapshot"),
                                    workspace);
            if (!ci.isEmpty()) manifest.set("ci", ci);
            Path manifestPath = workspace.resolve("manifest.json");
            Json.write(manifestPath, manifest);
            control.verifyExecutionAccess(lease);
            control.startInference(lease, mode);
            stage = "worker_failed";
            ObjectNode artifact = runner.run(git, manifestPath, workspace.resolve("output"));
            HostedWorker.validate(manifest, git, artifact);
            if (!artifact.path("files")
                    .path("service-envelope.json")
                    .path("executionMode")
                    .asText()
                    .equals(mode)) throw new IOException("Worker execution mode mismatch");
            control.complete(
                    lease,
                    artifact,
                    (ObjectNode)
                            artifact.path("files").path("service-envelope.json").path("usage"));
        } catch (Exception e) {
            String reason = stage;
            if (e instanceof ServiceException failure) {
                if (failure.status() == 403) {
                    reason = "access_revoked";
                } else if (failure.code().equals("snapshot_changed")) {
                    reason = "snapshot_changed";
                }
            }
            try {
                control.fail(lease, reason);
            } catch (ServiceException lost) {
                if (!lost.code().equals("lease_lost")) throw lost;
            }
        } finally {
            cleanup(workspace);
        }
        return true;
    }

    private void publish(ObjectNode lease) throws Exception {
        Path workspace = Files.createTempDirectory(workspaces, "publication-");
        ObjectNode receipt;
        try {
            control.verifyExecutionAccess(lease);
            receipt =
                    publisher.publish(
                            lease,
                            control.executionRepository(lease),
                            control.publicationArtifact(lease),
                            workspace);
        } catch (Exception e) {
            String status =
                    e instanceof ServiceException failure
                                    && (failure.status() == 403
                                            || failure.code().equals("snapshot_changed"))
                            ? "stale"
                            : "failed";
            receipt =
                    Json.MAPPER
                            .createObjectNode()
                            .put("status", status)
                            .put("reason", "publication_access_or_transport_failure");
        } finally {
            cleanup(workspace);
        }
        control.publicationResult(lease, receipt);
    }

    public static ObjectNode manifest(ObjectNode lease, ObjectNode preparation) {
        ObjectNode result = preparation.deepCopy();
        result.remove("expires");
        for (String key :
                java.util.List.of(
                        "engineDigest",
                        "effectivePolicy",
                        "policyIdentity",
                        "executionVersion",
                        "snapshot")) result.set(key, lease.path(key));
        return result;
    }

    private static void cleanup(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
