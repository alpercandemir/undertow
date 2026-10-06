package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undertow.harness.Reviewer;
import dev.undertow.model.ReplayClient;
import dev.undertow.reporting.Json;
import dev.undertow.tools.CiEvidence;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowAndCoverageTest {
    @TempDir Path temp;

    @Test
    void ciAndReviewWorkflowsParseAndKeepPermissionsSeparated() throws Exception {
        var ci = Json.YAML.readTree(Files.readString(Path.of(".github/workflows/ci.yml")));
        var review =
                Json.YAML.readTree(Files.readString(Path.of(".github/workflows/agent-review.yml")));
        var action = Json.YAML.readTree(Files.readString(Path.of("action.yml")));
        assertThat(ci.path("permissions").path("contents").asText()).isEqualTo("read");
        assertThat(Files.readString(Path.of(".github/workflows/ci.yml")))
                .doesNotContain("secrets.GEMINI_API_KEY", "pull_request_target");
        assertThat(
                        review.path("jobs")
                                .path("review")
                                .path("permissions")
                                .path("pull-requests")
                                .asText())
                .isEqualTo("read");
        assertThat(
                        review.path("jobs")
                                .path("publish")
                                .path("permissions")
                                .path("pull-requests")
                                .asText())
                .isEqualTo("write");
        assertThat(review.path("jobs").path("review").path("if").asText())
                .contains("default_branch");
        assertThat(action.path("runs").path("using").asText()).isEqualTo("composite");
    }

    @Test
    void workflowsUseJava25ForHarnessAndJava21ForDemo() throws Exception {
        var ci = Json.YAML.readTree(Files.readString(Path.of(".github/workflows/ci.yml")));
        String javaVersion = "";
        for (var step : ci.path("jobs").path("verify").path("steps")) {
            if (step.path("uses").asText().startsWith("actions/setup-java@")) {
                javaVersion = step.path("with").path("java-version").asText();
            }
            String command = step.path("run").asText();
            if (command.startsWith("mvn ")) {
                String expected =
                        command.contains("demo/order-service/pom.xml")
                                        || command.contains("demo/customer-service/pom.xml")
                                ? "21"
                                : "25";
                assertThat(javaVersion).as(command).isEqualTo(expected);
            }
            if (command.contains("scripts/evaluate.py")
                    || command.contains("scripts/phase3-demo.py")) {
                assertThat(javaVersion).isEqualTo("25");
            }
        }

        for (String workflow : List.of("action.yml", ".github/workflows/agent-review.yml")) {
            var yaml = Json.YAML.readTree(Files.readString(Path.of(workflow)));
            var versions = yaml.findValuesAsText("java-version");
            assertThat(versions).isNotEmpty().containsOnly("25");
        }
    }

    @Test
    void unsupportedMigrationSourceIsAnExplicitGapWithoutNetworkAccess() throws Exception {
        var repo = new TestRepository(temp);
        repo.write(
                "pom.xml",
                "<project><dependencies><dependency><groupId>x</groupId><artifactId>a</artifactId><version>1</version></dependency></dependencies></project>");
        repo.commit();
        String base = repo.head;
        repo.write(
                "pom.xml",
                "<project><dependencies><dependency><groupId>x</groupId><artifactId>a</artifactId><version>2</version></dependency></dependencies></project>");
        repo.commit();
        var reviewer = new Reviewer(new GitRepository(temp, base, repo.head), null);
        var result =
                reviewer.tools()
                        .execute(
                                "fetch_migration_evidence",
                                Json.parse(
                                        "{\"coordinate\":\"x:a\",\"old_version\":\"1\",\"new_version\":\"2\"}"));
        assertThat(result.path("data").path("status").asText()).contains("unverified");
        assertThat(reviewer.tools().gaps()).anyMatch(e -> e.contains("x:a"));
    }

    @Test
    void ciEvidenceForDifferentCommitsCannotEnterTools() throws Exception {
        var repo = new TestRepository(temp);
        Json.write(
                temp.resolve("ci.json"),
                Map.of(
                        "schema_version",
                        1,
                        "base_sha",
                        "0".repeat(40),
                        "head_sha",
                        repo.head,
                        "artifacts",
                        List.of()));
        assertThatThrownBy(() -> CiEvidence.load(temp.resolve("ci.json"), repo.git()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesAreLabeledSyntacticRatherThanClaimingResolution() throws Exception {
        var repo = new TestRepository(temp);
        var reviewer = new Reviewer(repo.git(), null);
        var result =
                reviewer.tools()
                        .execute(
                                "find_references",
                                Json.parse("{\"snapshot\":\"head\",\"symbol\":\"amount\"}"));
        assertThat(result.path("data").path("references").size()).isEqualTo(1);
        assertThat(result.path("data").path("symbol_resolution").asText()).contains("unresolved");
        assertThat(reviewer.tools().gaps()).anyMatch(e -> e.contains("resolution"));
    }

    @Test
    void modelCallsStopAtConfiguredBudget() throws Exception {
        var repo = new TestRepository(temp);
        List<com.fasterxml.jackson.databind.JsonNode> turns = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            turns.add(
                    Json.parse(
                            "{\"calls\":[{\"name\":\"get_business_context\",\"arguments\":{}}]}"));
        }
        var report =
                new Reviewer(repo.git(), null)
                        .run(new ReplayClient(turns), temp.resolve("out"), "tools");
        assertThat(report.modelCalls()).isEqualTo(12);
        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.toolCalls()).isLessThanOrEqualTo(30);
    }
}
