package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.cli.UndertowApplication;
import dev.undertow.contracts.ContractMcp;
import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.ContractSarif;
import dev.undertow.contracts.LocalPreview;
import dev.undertow.contracts.RegressionEvidence;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ContractToolsTest {
    @TempDir Path temp;
    final ContractVerificationTest helper = new ContractVerificationTest();

    ContractVerificationTest.Fixture fixture() throws Exception {
        helper.temp = temp;
        return helper.fixture();
    }

    @Test
    void localMutationInvalidatesWorkingPreviewButNotUnchangedIndex() throws Exception {
        var f = fixture();
        var working = LocalPreview.capture(f.git(), f.policy(), false, List.of());
        var staged = LocalPreview.capture(f.git(), f.policy(), true, List.of());
        assertThat(LocalPreview.fresh(f.git(), f.policy(), working)).isTrue();
        f.repo().write("src/Pay.java", "// local candidate changes\n");
        assertThat(LocalPreview.fresh(f.git(), f.policy(), working)).isFalse();
        assertThat(LocalPreview.fresh(f.git(), f.policy(), staged)).isTrue();
        f.repo().command("add", "src/Pay.java");
        assertThat(LocalPreview.fresh(f.git(), f.policy(), staged)).isFalse();
        assertThat(working.path("release_decision").asText()).isEqualTo("not_evaluated");
    }

    @Test
    void previewsRequireDeliberateUntrackedScopeAndRejectSecretsAndSymlinks() throws Exception {
        var f = fixture();
        f.repo().write("new.java", "// deliberately included\n");
        var preview = LocalPreview.capture(f.git(), f.policy(), false, List.of("new.java"));
        f.repo().write("new.java", "// modified\n");
        assertThat(LocalPreview.fresh(f.git(), f.policy(), preview)).isFalse();
        f.repo().write(".env", "synthetic secret\n");
        assertThatThrownBy(() -> LocalPreview.capture(f.git(), f.policy(), false, List.of(".env")))
                .isInstanceOf(IllegalArgumentException.class);
        Files.createSymbolicLink(f.repo().root.resolve("linked.java"), Path.of("src/Pay.java"));
        assertThatThrownBy(
                        () ->
                                LocalPreview.capture(
                                        f.git(), f.policy(), false, List.of("linked.java")))
                .isInstanceOf(java.io.IOException.class);
        Files.write(f.repo().root.resolve("invalid.java"), new byte[] {(byte) 0xff});
        assertThatThrownBy(
                        () ->
                                LocalPreview.capture(
                                        f.git(), f.policy(), false, List.of("invalid.java")))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void mcpCannotOverrideHostPolicyCredentialsEvidenceOrExecuteTools() throws Exception {
        var f = fixture();
        var mcp = new ContractMcp(f.git(), f.policy(), null, null);
        assertThat(
                        mcp.handle(
                                        Json.parse(
                                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                                .has("error"))
                .isTrue();
        mcp.handle(
                Json.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"));
        assertThat(
                        mcp.handle(
                                        Json.parse(
                                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                                .path("result")
                                .path("tools")
                                .size())
                .isEqualTo(2);
        for (String name : List.of("shell", "contracts_verify")) {
            var request =
                    Json.MAPPER
                            .createObjectNode()
                            .put("jsonrpc", "2.0")
                            .put("id", 3)
                            .put("method", "tools/call");
            request.putObject("params")
                    .put("name", name)
                    .putObject("arguments")
                    .put("policy_commit", f.git().head());
            assertThat(mcp.handle(request).has("error")).isTrue();
        }
        var response =
                mcp.handle(
                        Json.parse(
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"contracts_verify\",\"arguments\":{}}}"));
        assertThat(response.path("result").path("structuredContent").path("verification").asText())
                .isEqualTo("incomplete");
    }

    @Test
    void starterCreatesOneExplicitContractAndCannotOverwriteAnExistingPolicy() throws Exception {
        Path repo = temp.resolve("new");
        Files.createDirectories(repo);
        try (var context = UndertowApplication.start()) {
            String[] args = {
                "contracts",
                "init",
                "--repo",
                repo.toString(),
                "--repository",
                "example/shop",
                "--owner",
                "payments",
                "--id",
                "PAYMENT-RETRY",
                "--expectation",
                "Retry preserves identity",
                "--scope",
                "src/Pay.java",
                "--test-id",
                "example.PayTest.retry",
                "--test-source",
                "src/test/java/example/PayTest.java",
                "--oracle",
                "pom.xml"
            };
            assertThat(context.getBean(CommandLine.class).execute(args)).isZero();
            var second = context.getBean(CommandLine.class);
            second.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
            assertThat(second.execute(args)).isNotZero();
        }
    }

    @Test
    void sarifPreservesCommitAndExactTestSourceWithoutInventedLineNumbers() throws Exception {
        var f = fixture();
        helper.evidence(f, helper.tests("failed"));
        var sarif = ContractSarif.render(helper.verify(f), f.policy());
        assertThat(sarif.path("version").asText()).isEqualTo("2.1.0");
        assertThat(
                        sarif.path("runs")
                                .get(0)
                                .path("versionControlProvenance")
                                .get(0)
                                .path("revisionId")
                                .asText())
                .isEqualTo(f.git().head());
        assertThat(
                        sarif.path("runs")
                                .get(0)
                                .path("results")
                                .get(0)
                                .path("locations")
                                .get(0)
                                .path("physicalLocation")
                                .has("region"))
                .isFalse();
    }

    @Test
    void approvedExternalOracleCannotBeReplacedByMutableCandidateExpectations() throws Exception {
        var f = fixture();
        var manifest = (ObjectNode) f.policy().manifest();
        manifest.putArray("regression_artifacts")
                .addObject()
                .put("id", "regression")
                .put("sha256", "a".repeat(64));
        ((ObjectNode) manifest.path("contracts").get(0).path("tests").get(0))
                .put("artifact", "regression");
        f.repo().write(".undertow/contracts.yaml", Json.stringify(manifest));
        f.repo().commit();
        String approved = f.repo().head;
        f.repo().write("tests/PaymentTest.java", "// candidate cannot approve this\n");
        f.repo().commit();
        var git =
                new GitRepository(f.repo().root, f.git().base(), f.repo().head, approved, approved);
        var external =
                new ContractVerificationTest.Fixture(
                        f.repo(), git, new ContractPolicy(git, ".undertow/contracts.yaml"));
        var bundle = helper.evidence(external, helper.tests("passed"));
        assertThat(helper.verify(external).path("verification").asText()).isEqualTo("incomplete");
        var oracle =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("head_sha", git.head())
                        .put("image", "example/maven@sha256:" + "b".repeat(64));
        oracle.set("oracle_artifacts", external.policy().binding().path("oracle_artifacts"));
        bundle.withArray("artifacts")
                .addObject()
                .put("snapshot", "head")
                .put("kind", "oracle")
                .put("sha256", Json.hash(Json.stringify(oracle)))
                .set("content", oracle);
        helper.save(bundle);
        assertThat(helper.verify(external).path("verification").asText()).isEqualTo("satisfied");
    }

    @Test
    void reproductionRequiresRelevantAssertionsAndIndependentConsistentRepetitions()
            throws Exception {
        var f = fixture();
        var comparison =
                new GitRepository(
                        f.repo().root,
                        f.git().policyCommit(),
                        f.git().head(),
                        f.git().policyCommit(),
                        f.git().policyCommit());
        var runs = Json.MAPPER.createObjectNode().put("schema_version", 1);
        int runId = 100;
        for (String side : List.of("baseline", "candidate")) {
            var repetitions = runs.putArray(side);
            for (int repeat = 0; repeat < 2; repeat++) {
                var git =
                        new GitRepository(
                                f.repo().root,
                                comparison.base(),
                                side.equals("baseline") ? comparison.base() : comparison.head(),
                                comparison.policyCommit(),
                                comparison.targetHead());
                var subject =
                        new ContractVerificationTest.Fixture(
                                f.repo(), git, new ContractPolicy(git, ".undertow/contracts.yaml"));
                var bundle =
                        helper.evidence(
                                subject,
                                helper.tests(side.equals("baseline") ? "passed" : "failed"));
                bundle.put("run_id", String.valueOf(runId++));
                helper.save(bundle);
                String name = side + repeat;
                Files.copy(helper.bundle(), temp.resolve(name + ".json"));
                Files.copy(helper.receipt(), temp.resolve(name + "-receipt.json"));
                repetitions
                        .addObject()
                        .put("bundle", name + ".json")
                        .put("receipt", name + "-receipt.json");
            }
        }
        assertThat(
                        RegressionEvidence.compare(
                                        comparison,
                                        f.policy(),
                                        runs,
                                        temp,
                                        "PAYMENT-RETRY",
                                        "example.PaymentTest.retry")
                                .path("conclusion")
                                .asText())
                .isEqualTo("reproduced_regression");
        ((com.fasterxml.jackson.databind.node.ArrayNode) runs.path("candidate"))
                .set(1, runs.path("candidate").get(0));
        assertThat(
                        RegressionEvidence.compare(
                                        comparison,
                                        f.policy(),
                                        runs,
                                        temp,
                                        "PAYMENT-RETRY",
                                        "example.PaymentTest.retry")
                                .path("conclusion")
                                .asText())
                .isEqualTo("incomplete");
        assertThat(
                        RegressionEvidence.compare(
                                        comparison,
                                        f.policy(),
                                        runs,
                                        temp,
                                        "PAYMENT-RETRY",
                                        "missing.Test.test")
                                .path("conclusion")
                                .asText())
                .isEqualTo("incomplete");
    }

    @Test
    void newlyAddedMavenControlsCannotSilentlyReplaceApprovedExecution() throws Exception {
        var f = fixture();
        f.repo().write(".mvn/maven.config", "-DskipTests\n");
        f.repo().commit();
        var git =
                new GitRepository(
                        f.repo().root,
                        f.git().base(),
                        f.repo().head,
                        f.git().policyCommit(),
                        f.git().targetHead());
        var changed =
                new ContractVerificationTest.Fixture(
                        f.repo(), git, new ContractPolicy(git, ".undertow/contracts.yaml"));
        helper.evidence(changed, helper.tests("passed"));
        assertThat(helper.verify(changed).path("verification").asText()).isEqualTo("incomplete");
        assertThat(changed.policy().changedOraclePaths(git).toString())
                .contains("oracle inventory changed");
    }

    @Test
    void sourceReadsTreatGitPathspecCharactersLiterally() throws Exception {
        var f = fixture();
        f.repo().write("src/Named[1].java", "class Named {}\n");
        f.repo().commit();
        var git = new GitRepository(f.repo().root, f.git().base(), f.repo().head);
        assertThat(git.source("head", "src/Named[1].java")).isEqualTo("class Named {}\n");
        assertThatThrownBy(() -> git.source("head", "src/*.java"))
                .isInstanceOf(java.io.IOException.class);
    }
}
