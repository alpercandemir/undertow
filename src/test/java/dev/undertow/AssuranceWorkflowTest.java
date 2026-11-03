package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.cli.UndertowApplication;
import dev.undertow.contracts.AssurancePolicy;
import dev.undertow.contracts.ContractMcp;
import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.ContractSarif;
import dev.undertow.contracts.LocalPreview;
import dev.undertow.contracts.PilotStudy;
import dev.undertow.contracts.RegressionEvidence;
import dev.undertow.contracts.ReleaseAssurance;
import dev.undertow.contracts.RolloutAssurance;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class AssuranceWorkflowTest {
    @TempDir Path temp;
    final ContractVerificationTest helper = new ContractVerificationTest();
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    ContractVerificationTest.Fixture fixture() throws Exception {
        helper.temp = temp;
        return helper.fixture();
    }

    ContractVerificationTest.Fixture policies(boolean enabled) throws Exception {
        var f = fixture();
        f.repo()
                .write(
                        ".undertow/release.yaml",
                        "schema_version: 1\nenabled: "
                                + enabled
                                + "\nowner: demo maintainers\nrequired_contracts: [PAYMENT-RETRY]\n");
        f.repo()
                .write(
                        ".undertow/operations.yaml",
                        """
                schema_version: 1
                owner: demo operations
                environment: test
                contract_id: PAYMENT-RETRY
                minimum_events: 10
                minimum_window_seconds: 30
                maximum_age_seconds: 120
                maximum_detection_seconds: 10
                reconciliation_required: true
                """);
        f.repo().commit();
        var git =
                new GitRepository(
                        f.repo().root,
                        f.git().policyCommit(),
                        f.repo().head,
                        f.repo().head,
                        f.repo().head);
        return new ContractVerificationTest.Fixture(
                f.repo(), git, new ContractPolicy(git, ".undertow/contracts.yaml"));
    }

    JsonNode decision(ContractVerificationTest.Fixture f) throws Exception {
        return ReleaseAssurance.decide(
                helper.verify(f),
                AssurancePolicy.load(f.git(), ".undertow/release.yaml", "release-policy"),
                NOW);
    }

    JsonNode attestation(ContractVerificationTest.Fixture f) throws Exception {
        Path artifact = temp.resolve("app.jar");
        Files.writeString(artifact, "synthetic application artifact");
        var bundle = helper.evidence(f, helper.tests("passed"));
        var build =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("head_sha", f.git().head())
                        .put("artifact_sha256", Json.hash(Files.readString(artifact)));
        bundle.withArray("artifacts")
                .addObject()
                .put("snapshot", "head")
                .put("kind", "build")
                .put("sha256", Json.hash(Json.stringify(build)))
                .set("content", build);
        helper.save(bundle);
        return ReleaseAssurance.attest(
                f.git(), decision(f), artifact, helper.bundle(), helper.receipt());
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
    void shadowRemainsSeparateFromOwnerEnabledGatesAndUnknownsHold() throws Exception {
        var f = policies(false);
        helper.evidence(f, helper.tests("passed"));
        assertThat(decision(f).path("decision").asText()).isEqualTo("not_evaluated");
        var approved =
                (ObjectNode)
                        AssurancePolicy.load(f.git(), ".undertow/release.yaml", "release-policy");
        ((ObjectNode) approved.path("document")).put("enabled", true);
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("eligible");
        helper.evidence(f, helper.tests("skipped"));
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("held");
    }

    @Test
    void exceptionsRequireExactSubjectUnexpiredOwnerApprovalAndCannotWaiveUnknowns()
            throws Exception {
        var f = policies(true);
        var approved =
                (ObjectNode)
                        AssurancePolicy.load(f.git(), ".undertow/release.yaml", "release-policy");
        var exception =
                ((ObjectNode) approved.path("document"))
                        .putArray("exceptions")
                        .addObject()
                        .put("id", "reviewed-incident")
                        .put("contract_id", "PAYMENT-RETRY")
                        .put("head_sha", f.git().head())
                        .put("owner", "payments")
                        .put("reason", "Temporary reviewed mitigation")
                        .put("expires", "2026-11-04T00:00:00Z");
        helper.evidence(f, helper.tests("failed"));
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("eligible");
        exception.put("head_sha", "0".repeat(40));
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("held");
        exception.put("head_sha", f.git().head()).put("expires", "2026-11-01T00:00:00Z");
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("held");
        exception.put("expires", "2026-11-04T00:00:00Z");
        helper.evidence(f, helper.tests("error"));
        assertThat(
                        ReleaseAssurance.decide(helper.verify(f), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("held");
    }

    @Test
    void candidateCannotReplaceGatePolicyAndArtifactBytesMustMatchTrustedCi() throws Exception {
        var f = policies(true);
        assertThat(attestation(f).path("state").asText()).isEqualTo("issued");
        Files.writeString(temp.resolve("app.jar"), "substituted artifact");
        assertThat(
                        ReleaseAssurance.attest(
                                        f.git(),
                                        decision(f),
                                        temp.resolve("app.jar"),
                                        helper.bundle(),
                                        helper.receipt())
                                .path("state")
                                .asText())
                .isEqualTo("held");
        var disabled =
                (ObjectNode)
                        AssurancePolicy.load(f.git(), ".undertow/release.yaml", "release-policy");
        ((ObjectNode) disabled.path("document")).put("enabled", false);
        f.repo()
                .write(
                        ".undertow/release.yaml",
                        "schema_version: 1\nenabled: false\nowner: candidate\nrequired_contracts: [UNKNOWN]\n");
        f.repo().commit();
        var git =
                new GitRepository(
                        f.repo().root,
                        f.git().base(),
                        f.repo().head,
                        f.git().policyCommit(),
                        f.git().targetHead());
        assertThat(
                        AssurancePolicy.load(git, ".undertow/release.yaml", "release-policy")
                                .path("document")
                                .path("enabled")
                                .asBoolean())
                .isTrue();
    }

    ObjectNode observations(JsonNode attestation) {
        var value =
                Json.MAPPER
                        .createObjectNode()
                        .put("environment", "test")
                        .put("contract_id", "PAYMENT-RETRY")
                        .put("deployment_id", "controlled-demo")
                        .put("events", 20)
                        .put("violations", 0)
                        .put("started_at", "2026-11-03T11:59:00Z")
                        .put("ended_at", "2026-11-03T11:59:50Z");
        value.set("artifact_sha256", attestation.path("artifact_sha256"));
        value.set("head_sha", attestation.path("head_sha"));
        return value;
    }

    JsonNode rollout(
            ContractVerificationTest.Fixture f, JsonNode attestation, JsonNode observations)
            throws Exception {
        String raw = Json.stringify(observations);
        var receipt =
                Json.MAPPER
                        .createObjectNode()
                        .put("verified_source", "delivery_monitor_export")
                        .put("observation_sha256", Json.hash(raw))
                        .put("attestation_sha256", ReleaseAssurance.hash(attestation));
        return RolloutAssurance.evaluate(
                attestation,
                AssurancePolicy.load(f.git(), ".undertow/operations.yaml", "operations-policy"),
                raw,
                receipt,
                NOW);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "quiet",
                "stale",
                "wrong_artifact",
                "wrong_environment",
                "negative",
                "wrong_time",
                "overflow"
            })
    void quietStaleWrongSubjectAndMalformedTelemetryCannotAuthorizeRollout(String mutation)
            throws Exception {
        var f = policies(true);
        var attestation = attestation(f);
        var observation = observations(attestation);
        switch (mutation) {
            case "quiet" -> observation.put("events", 0);
            case "stale" -> observation.put("ended_at", "2026-11-03T11:00:00Z");
            case "wrong_artifact" -> observation.put("artifact_sha256", "0".repeat(64));
            case "wrong_environment" -> observation.put("environment", "other");
            case "negative" -> observation.put("events", -1);
            case "wrong_time" -> observation.put("started_at", "invalid");
            case "overflow" ->
                    observation.put("events", new java.math.BigInteger("18446744073709551636"));
            default -> throw new IllegalArgumentException();
        }
        assertThat(rollout(f, attestation, observation).path("decision").asText())
                .isEqualTo("held");
    }

    @Test
    void rolloutDetectsHaltsAndRequiresReconciliationOfIrreversibleEffects() throws Exception {
        var f = policies(true);
        var attestation = attestation(f);
        var observation = observations(attestation);
        assertThat(rollout(f, attestation, observation).path("decision").asText())
                .isEqualTo("accepted");
        observation.put("violations", 1).put("first_violation_at", "2026-11-03T11:59:10Z");
        assertThat(rollout(f, attestation, observation).path("decision").asText())
                .isEqualTo("halt_required");
        observation.put("halt_confirmed_at", "2026-11-03T11:59:15Z");
        assertThat(rollout(f, attestation, observation).path("decision").asText())
                .isEqualTo("contained");
        observation
                .put("reconciled_at", "2026-11-03T11:59:40Z")
                .put("reconciliation_evidence", "https://example.test/incident/1");
        assertThat(rollout(f, attestation, observation).path("decision").asText())
                .isEqualTo("recovered");
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
    void pilotRequiresIndependentLabelsAndCannotSelfEnableAGate() throws Exception {
        var f = fixture();
        helper.evidence(f, helper.tests("passed"));
        var study = Json.MAPPER.createObjectNode().put("schema_version", 1);
        var row = study.putArray("cases").addObject().put("contract_id", "PAYMENT-RETRY");
        row.set("verification", helper.verify(f));
        row.putArray("reviews").addObject().put("reviewer", "reviewer-1").put("verdict", "benign");
        assertThat(PilotStudy.measure(study).path("unresolved_assessments").asInt()).isEqualTo(1);
        row.withArray("reviews").addObject().put("reviewer", "reviewer-2").put("verdict", "benign");
        var measured = PilotStudy.measure(study);
        assertThat(measured.path("automatic_gate_authority").asBoolean()).isFalse();
        assertThat(measured.path("false_hold_ci95").get(1).asDouble()).isGreaterThan(0);
        study.withArray("cases").add(row.deepCopy());
        assertThatThrownBy(() -> PilotStudy.measure(study))
                .isInstanceOf(IllegalArgumentException.class);
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
    void headCannotEnableDisabledGate() throws Exception {
        var f = policies(false);
        f.repo()
                .write(
                        ".undertow/release.yaml",
                        "schema_version: 1\nenabled: true\nowner: candidate\nrequired_contracts: [PAYMENT-RETRY]\n");
        f.repo().commit();
        var git =
                new GitRepository(
                        f.repo().root,
                        f.git().base(),
                        f.repo().head,
                        f.git().policyCommit(),
                        f.git().targetHead());
        var candidate =
                new ContractVerificationTest.Fixture(
                        f.repo(), git, new ContractPolicy(git, ".undertow/contracts.yaml"));
        helper.evidence(candidate, helper.tests("passed"));
        var approved = AssurancePolicy.load(git, ".undertow/release.yaml", "release-policy");
        assertThat(
                        ReleaseAssurance.decide(helper.verify(candidate), approved, NOW)
                                .path("decision")
                                .asText())
                .isEqualTo("not_evaluated");
    }

    @Test
    void policyAndExceptionSnapshotsCannotBeChangedAfterDecision() throws Exception {
        var f = policies(true);
        helper.evidence(f, helper.tests("passed"));
        var approved = AssurancePolicy.load(f.git(), ".undertow/release.yaml", "release-policy");
        var decision = ReleaseAssurance.decide(helper.verify(f), approved, NOW);
        ((ObjectNode) approved.path("document")).put("enabled", false);
        assertThat(decision.path("release_policy").path("document").path("enabled").asBoolean())
                .isTrue();
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
