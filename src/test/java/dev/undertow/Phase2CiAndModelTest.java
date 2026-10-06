package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undertow.github.GitHubClient;
import dev.undertow.harness.Report;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.GeminiClient;
import dev.undertow.model.ModelClient;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Markdown;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.CiEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2CiAndModelTest {
    @TempDir Path temp;

    @Test
    void originalV1ReportRetainsItsContractWithoutV2Metadata() throws Exception {
        var original = Json.parse(Files.readString(Path.of("docs/sample-review.json")));
        Schemas.validate(Schemas.resource("report"), original);
        var report = Json.MAPPER.treeToValue(original, Report.class);
        assertThat(report.schemaVersion()).isEqualTo(1);
        assertThat(report.details()).isNull();
        var roundTrip = Json.MAPPER.valueToTree(report);
        Schemas.validate(Schemas.resource("report"), roundTrip);
        assertThat(roundTrip.has("details")).isFalse();
        assertThat(roundTrip.path("findings")).isEqualTo(original.path("findings"));
    }

    @Test
    void sourceReceiptBindsRepositoryRunArtifactAndContents() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var bundle =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 2)
                        .put("base_sha", repo.base)
                        .put("head_sha", repo.head)
                        .put("repository", "owner/repo")
                        .put("run_id", "123")
                        .put("workflow_id", "42")
                        .put("artifact_id", "77");
        var tests = Json.parse("[{\"name\":\"Payment.retry\",\"status\":\"failed\"}]");
        bundle.putArray("artifacts")
                .addObject()
                .put("snapshot", "head")
                .put("kind", "tests")
                .put("sha256", Json.hash(Json.stringify(tests)))
                .set("content", tests);
        Path file = temp.resolve("ci.json"), receipt = temp.resolve("receipt.json");
        Json.write(file, bundle);
        var trusted = Json.MAPPER.createObjectNode().put("verified_source", "github_actions_api");
        for (String key : List.of("repository", "run_id", "workflow_id", "artifact_id", "head_sha"))
            trusted.set(key, bundle.get(key));
        trusted.put("bundle_sha256", Json.hash(Files.readString(file)));
        Json.write(receipt, trusted);
        var evidence = CiEvidence.load(file, repo.git(), receipt);
        assertThat(evidence.path("execution").get(0).path("status").asText()).isEqualTo("failed");
        assertThat(
                        CiEvidence.load(file, repo.git())
                                .path("execution")
                                .get(0)
                                .path("status")
                                .asText())
                .isEqualTo("unverified");
        trusted.put("run_id", "999");
        Json.write(receipt, trusted);
        assertThatThrownBy(() -> CiEvidence.load(file, repo.git(), receipt))
                .hasMessageContaining("provenance mismatch");
        trusted.put("run_id", "123");
        Json.write(receipt, trusted);
        Files.writeString(file, Files.readString(file) + " ");
        assertThatThrownBy(() -> CiEvidence.load(file, repo.git(), receipt))
                .hasMessageContaining("modified");
        assertThatThrownBy(() -> CiEvidence.load(temp.resolve("missing"), repo.git(), receipt))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void modelFailuresHaveDistinctSafeClassifications() {
        assertThat(new GeminiClient.ModelFailure(401, new Exception("secret-sample")).category())
                .isEqualTo("authentication");
        assertThat(new GeminiClient.ModelFailure(403, new Exception()).category())
                .isEqualTo("model_access");
        assertThat(new GeminiClient.ModelFailure(429, new Exception("quota exhausted")).category())
                .isEqualTo("quota");
        var minuteLimit =
                new GeminiClient.ModelFailure(
                        429,
                        new Exception(
                                "Quota exceeded: GenerateRequestsPerMinutePerProject. Please retry in 45.2s."));
        assertThat(minuteLimit.category()).isEqualTo("rate_limit");
        assertThat(minuteLimit.retryDelayMillis(0)).isEqualTo(45200);
        assertThat(
                        new GeminiClient.ModelFailure(
                                        429,
                                        new Exception(
                                                "Quota exceeded: GenerateRequestsPerDayPerProject. Please retry in 900s."))
                                .category())
                .isEqualTo("quota");
        assertThat(
                        new GeminiClient.ModelFailure(429, new Exception("Please retry in 900s."))
                                .retryDelayMillis(0))
                .isEqualTo(60000);
        assertThat(
                        new GeminiClient.ModelFailure(429, new Exception("too many requests"))
                                .category())
                .isEqualTo("rate_limit");
        assertThat(new GeminiClient.ModelFailure(504, new Exception()).category())
                .isEqualTo("timeout");
        assertThat(new GeminiClient.ModelFailure(400, new Exception("schema")).category())
                .isEqualTo("invalid_output");
    }

    @Test
    void providerSecretPayloadNeverEntersArtifacts() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        ModelClient client =
                new ModelClient() {
                    public Turn next(
                            String instruction, List<Observation> observations, boolean finalOnly)
                            throws java.io.IOException {
                        throw new GeminiClient.ModelFailure(
                                401, new Exception("AIza-secret-sample bearer secret"));
                    }

                    public String identity() {
                        return "test-live";
                    }
                };
        var output = temp.resolve("out");
        var report = new Reviewer(repo.git(), null).run(client, output, "tools");
        assertThat(report.details().failureCause()).isEqualTo("authentication");
        assertThat(
                        Markdown.summary(
                                report,
                                GitHubClient.ref("owner/repo", 1),
                                "",
                                java.util.Map.of(),
                                ""))
                .contains(
                        "Review failure cause: authentication",
                        "zero findings do not establish success")
                .doesNotContain("AIza-secret-sample", "bearer secret");
        for (var file : Files.list(output).toList())
            assertThat(Files.readString(file))
                    .doesNotContain("AIza-secret-sample", "bearer secret");
        assertThat(Files.readString(output.resolve("publication.json"))).contains("not_requested");
    }
}
