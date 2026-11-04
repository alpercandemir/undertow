package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.changes.PublicationValidation;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ModelClient;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Credential-limited process boundary; never fetches source, executes builds, or publishes. */
public final class HostedWorker {
    private HostedWorker() {}

    public static Reviewer reviewer(ObjectNode manifest, Path repository) throws Exception {
        return reviewer(manifest, repository, null);
    }

    private static Reviewer reviewer(ObjectNode manifest, Path repository, Path output)
            throws Exception {
        var snapshot = manifest.path("snapshot");
        var git =
                new GitRepository(
                        repository,
                        snapshot.path("diffBase").asText(),
                        snapshot.path("head").asText(),
                        snapshot.path("policyCommit").asText(),
                        snapshot.path("target").asText());
        var policy =
                ServicePolicy.resolve(
                        ServicePolicy.files(manifest.path("files")),
                        manifest.path("execution"),
                        manifest.path("sources"));
        if (!ServicePolicy.effectiveHash(policy).equals(manifest.path("effectivePolicy").asText()))
            throw new IOException("Effective policy identity mismatch");
        Path bundle = null, receipt = null;
        if (output != null && manifest.has("ci")) {
            java.nio.file.Files.createDirectories(output.resolve("ci"));
            bundle = output.resolve("ci/bundle.json");
            receipt = output.resolve("ci/receipt.json");
            String text = manifest.path("ci").path("bundle").asText();
            JsonNode data = Json.parse(text), origin = snapshot.path("ciOrigin");
            if (!origin.path("status").asText().equals("verified")
                    || data.path("artifact_id").asLong() != origin.path("artifactId").asLong()
                    || data.path("workflow_id").asLong() != origin.path("workflowId").asLong()
                    || data.path("run_id").asLong() != origin.path("runId").asLong()
                    || !data.path("repository").asText().equals(origin.path("name").asText())
                    || !data.path("origin")
                            .path("archive_digest")
                            .asText()
                            .equals(origin.path("digest").asText()))
                throw new IOException("CI service provenance binding mismatch");
            java.nio.file.Files.writeString(bundle, text);
            java.nio.file.Files.writeString(receipt, manifest.path("ci").path("receipt").asText());
        }
        return new Reviewer(git, policy, bundle, receipt);
    }

    public static ObjectNode run(
            ObjectNode manifest, Path repository, Path output, ModelClient model, String mode)
            throws Exception {
        Reviewer reviewer = reviewer(manifest, repository, output);
        var metered = new MeteredModel(model, mode);
        Report report = reviewer.run(metered, output, mode.equals("collect") ? "collect" : "tools");
        ObjectNode files = Json.MAPPER.createObjectNode();
        for (String file : List.of("report.json", "evidence.json"))
            files.set(file, Json.parse(Json.read(output.resolve(file), 2000000)));
        files.put("report.md", Json.read(output.resolve("report.md"), 1000000));
        // Retain immutable inert policy for publisher-only retries after source cleanup.
        files.set("effective-policy.json", manifest.deepCopy());
        ObjectNode hashes = Json.MAPPER.createObjectNode();
        var fields = files.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            hashes.put(field.getKey(), Json.hash(Json.stringify(field.getValue())));
        }
        ObjectNode envelope =
                Json.MAPPER
                        .createObjectNode()
                        .put("schemaVersion", 1)
                        .put("review", manifest.path("review").asText())
                        .put("tenant", manifest.path("tenant").asText())
                        .put("engineDigest", manifest.path("engineDigest").asText())
                        .put("effectivePolicy", manifest.path("effectivePolicy").asText())
                        .put("policyIdentity", manifest.path("policyIdentity").asText())
                        .put("executionVersion", manifest.path("executionVersion").asText())
                        .put("executionMode", mode)
                        .put("promptVersion", report.promptVersion())
                        .put("promptHash", report.promptHash());
        envelope.set("snapshot", manifest.path("snapshot"));
        envelope.set("artifactHashes", hashes);
        envelope.set("usage", metered.usage(report.modelCalls()));
        envelope.put("publication", "not_requested");
        Schemas.validate(Schemas.resource("service-envelope"), envelope);
        files.set("service-envelope.json", envelope);
        ObjectNode artifact = Json.MAPPER.createObjectNode();
        artifact.set("files", files);
        Json.write(output.resolve("service-envelope.json"), envelope);
        Json.write(output.resolve("service-artifact.json"), artifact);
        validate(manifest, repository, artifact);
        return artifact;
    }

    public static void validate(ObjectNode manifest, Path repository, ObjectNode artifact)
            throws Exception {
        JsonNode files = artifact.path("files"), envelope = files.path("service-envelope.json");
        Schemas.validate(Schemas.resource("service-envelope"), envelope);
        for (String field :
                List.of(
                        "tenant",
                        "engineDigest",
                        "effectivePolicy",
                        "policyIdentity",
                        "executionVersion",
                        "snapshot"))
            if (!envelope.path(field).equals(manifest.path(field)))
                throw new IOException("Service artifact binding mismatch");
        if (!envelope.path("review").equals(manifest.path("review"))
                || !files.path("effective-policy.json").equals(manifest))
            throw new IOException("Service review identity mismatch");
        for (String file :
                List.of("report.json", "evidence.json", "report.md", "effective-policy.json"))
            if (!Json.hash(Json.stringify(files.path(file)))
                    .equals(envelope.path("artifactHashes").path(file).asText()))
                throw new IOException("Service artifact hash mismatch");
        Report report = Json.MAPPER.treeToValue(files.path("report.json"), Report.class);
        var snapshot = manifest.path("snapshot");
        var git =
                new GitRepository(
                        repository,
                        snapshot.path("diffBase").asText(),
                        snapshot.path("head").asText(),
                        snapshot.path("policyCommit").asText(),
                        snapshot.path("target").asText());
        var policy =
                ServicePolicy.resolve(
                        ServicePolicy.files(manifest.path("files")),
                        manifest.path("execution"),
                        manifest.path("sources"));
        PublicationValidation.validate(
                report, git, policy, EvidenceStore.restore(files.path("evidence.json")));
    }

    private static final class MeteredModel implements ModelClient {
        private final ModelClient delegate;
        private final String mode;
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicBoolean known = new AtomicBoolean(true);

        private MeteredModel(ModelClient delegate, String mode) {
            this.delegate = delegate;
            this.mode = mode;
        }

        @Override
        public Turn next(String instruction, List<Observation> observations, boolean finalOnly)
                throws IOException {
            active.incrementAndGet();
            calls.incrementAndGet();
            try {
                Turn result = delegate.next(instruction, observations, finalOnly);
                if (!delegate.usageKnown() || result.inputTokens() < 0 || result.outputTokens() < 0)
                    known.set(false);
                input.updateAndGet(
                        value -> Math.addExact(value, Math.max(0, result.inputTokens())));
                output.updateAndGet(
                        value -> Math.addExact(value, Math.max(0, result.outputTokens())));
                return result;
            } catch (IOException | RuntimeException e) {
                known.set(false);
                throw e;
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public String identity() {
            return delegate.identity();
        }

        private ObjectNode usage(int expectedCalls) {
            int startedCalls = calls.get();
            int running = active.get();
            long capturedInput = input.get(), capturedOutput = output.get();
            boolean verified =
                    running == 0
                            && active.get() == 0
                            && known.get()
                            && startedCalls == expectedCalls
                            && calls.get() == startedCalls;
            return Json.MAPPER
                    .createObjectNode()
                    .put("known", verified)
                    .put("inputTokens", capturedInput)
                    .put("outputTokens", capturedOutput)
                    .put("modelCalls", startedCalls)
                    .put("billable", mode.equals("live"))
                    .put("provider", mode.equals("live") ? "gemini" : mode)
                    .put("model", identity());
        }
    }
}
