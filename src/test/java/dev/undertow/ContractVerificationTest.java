package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.cli.UndertowApplication;
import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.ContractVerifier;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class ContractVerificationTest {
    private static final String MANIFEST = ".undertow/contracts.yaml";
    private static final String TEST_ID = "example.PaymentTest.retry";
    @TempDir Path temp;

    record Fixture(TestRepository repo, GitRepository git, ContractPolicy policy) {}

    Fixture fixture() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        for (String legacy :
                List.of(
                        "config/review.yaml",
                        "config/business-context.yaml",
                        "config/dependency-sources.yaml",
                        "config/languages/java/rules.yaml",
                        "CODING-SKILL.md")) {
            Files.delete(repo.root.resolve(legacy));
        }
        repo.write("pom.xml", "<project/>\n");
        repo.write("tests/PaymentTest.java", "// Approved payment oracle\n");
        repo.write(
                MANIFEST,
                """
                schema_version: 1
                repository: owner/repo
                oracle_paths: [pom.xml]
                contracts:
                  - id: PAYMENT-RETRY
                    version: '1'
                    owner: payments
                    expectation: Retries preserve the logical operation key
                    scope: [src/Pay.java]
                    verifier: junit
                    tests:
                      - id: example.PaymentTest.retry
                        sources: [tests/PaymentTest.java]
                """);
        repo.commit();
        String policy = repo.head;
        repo.write("src/Pay.java", "class Pay { int amount() { return 3; } }\n");
        repo.commit();
        var git = new GitRepository(repo.root, repo.base, repo.head, policy, policy);
        return new Fixture(repo, git, new ContractPolicy(git, MANIFEST));
    }

    Path bundle() {
        return temp.resolve("ci.json");
    }

    Path receipt() {
        return temp.resolve("receipt.json");
    }

    ObjectNode evidence(Fixture f, JsonNode tests) throws Exception {
        var bundle =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 2)
                        .put("base_sha", f.git.base())
                        .put("head_sha", f.git.head())
                        .put("repository", "owner/repo")
                        .put("run_id", "123")
                        .put("workflow_id", "42")
                        .put("artifact_id", "77")
                        .put("run_conclusion", "success");
        bundle.set("contract_binding", f.policy.binding());
        bundle.putObject("origin")
                .put("workflow_url", "https://github.com/owner/repo/actions/runs/123");
        bundle.putArray("artifacts")
                .addObject()
                .put("snapshot", "head")
                .put("kind", "tests")
                .put("sha256", Json.hash(Json.stringify(tests)))
                .set("content", tests);
        save(bundle);
        return bundle;
    }

    void save(ObjectNode bundle) throws Exception {
        Json.write(bundle(), bundle);
        var receipt = Json.MAPPER.createObjectNode().put("verified_source", "github_actions_api");
        for (String key :
                List.of(
                        "repository",
                        "run_id",
                        "workflow_id",
                        "artifact_id",
                        "head_sha",
                        "contract_binding",
                        "run_conclusion")) {
            if (bundle.has(key)) receipt.set(key, bundle.get(key));
        }
        receipt.put("bundle_sha256", Json.hash(Files.readString(bundle())));
        Json.write(receipt(), receipt);
    }

    JsonNode tests(String status) {
        var rows = Json.MAPPER.createArrayNode();
        rows.addObject().put("name", TEST_ID).put("status", status);
        return rows;
    }

    JsonNode verify(Fixture f) throws Exception {
        return ContractVerifier.verify(f.git, f.policy, bundle(), receipt());
    }

    @ParameterizedTest
    @CsvSource({"passed,satisfied", "failed,unsatisfied", "error,incomplete", "skipped,incomplete"})
    void rawExecutionsHaveDistinctContractOutcomes(String execution, String expected)
            throws Exception {
        var f = fixture();
        evidence(f, tests(execution));
        var result = verify(f);
        assertThat(result.path("verification").asText()).isEqualTo(expected);
        assertThat(result.path("release_decision").asText()).isEqualTo("not_evaluated");
        assertThat(
                        result.path("contracts")
                                .get(0)
                                .path("checks")
                                .get(0)
                                .path("reported_status")
                                .asText())
                .isEqualTo(execution);
        assertThat(verify(f)).isEqualTo(result);
    }

    @Test
    void emptyUnrelatedAndDuplicateResultsCannotSatisfyBindings() throws Exception {
        var f = fixture();
        evidence(f, Json.MAPPER.createArrayNode());
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
        var unrelated = Json.MAPPER.createArrayNode();
        unrelated.addObject().put("name", "example.OtherTest.green").put("status", "passed");
        evidence(f, unrelated);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
        var duplicates =
                Json.MAPPER
                        .createArrayNode()
                        .addAll((com.fasterxml.jackson.databind.node.ArrayNode) tests("passed"))
                        .addAll((com.fasterxml.jackson.databind.node.ArrayNode) tests("failed"));
        evidence(f, duplicates);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
    }

    @Test
    void localReceiptlessAndLegacyEvidenceCannotGrantSatisfaction() throws Exception {
        var f = fixture();
        evidence(f, tests("passed"));
        assertThat(
                        ContractVerifier.verify(f.git, f.policy, bundle(), null)
                                .path("verification")
                                .asText())
                .isEqualTo("incomplete");
        var legacy = evidence(f, tests("passed"));
        legacy.remove("contract_binding");
        save(legacy);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
        assertThat(
                        ContractVerifier.verify(f.git, f.policy, null, null)
                                .path("verification")
                                .asText())
                .isEqualTo("incomplete");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"base_sha", "head_sha", "contract_binding", "run_conclusion", "repository"})
    void staleWrongPolicyAndIncompleteRunsAreRejected(String key) throws Exception {
        var f = fixture();
        var bundle = evidence(f, tests("passed"));
        if (key.equals("contract_binding")) {
            ((ObjectNode) bundle.path(key)).put("policy_commit", f.repo.base);
        } else if (key.equals("repository")) {
            bundle.put(key, "other/repo");
        } else {
            bundle.put(key, key.equals("run_conclusion") ? "cancelled" : "a".repeat(40));
        }
        save(bundle);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
    }

    @Test
    void tamperingWithBundleReceiptOrBindingCannotBecomeGreen() throws Exception {
        var f = fixture();
        evidence(f, tests("passed"));
        Files.writeString(bundle(), Files.readString(bundle()) + " ");
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
        evidence(f, tests("passed"));
        var receipt = (ObjectNode) Json.parse(Files.readString(receipt()));
        ((ObjectNode) receipt.path("contract_binding")).put("oracle_sha256", "0".repeat(64));
        Json.write(receipt(), receipt);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
        evidence(f, tests("passed"));
        receipt = (ObjectNode) Json.parse(Files.readString(receipt()));
        receipt.put("workflow_id", "99");
        Json.write(receipt(), receipt);
        assertThat(verify(f).path("verification").asText()).isEqualTo("incomplete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"modify", "delete", "rename", "symlink", "policy", "build"})
    void changedOraclesAndCandidatePolicyWeakeningRequireOwnerReview(String change)
            throws Exception {
        var f = fixture();
        switch (change) {
            case "modify" -> f.repo.write("tests/PaymentTest.java", "// assertion removed\n");
            case "delete" -> Files.delete(f.repo.root.resolve("tests/PaymentTest.java"));
            case "rename" ->
                    Files.move(
                            f.repo.root.resolve("tests/PaymentTest.java"),
                            f.repo.root.resolve("tests/Renamed.java"));
            case "symlink" -> {
                Files.delete(f.repo.root.resolve("tests/PaymentTest.java"));
                Files.createSymbolicLink(
                        f.repo.root.resolve("tests/PaymentTest.java"), Path.of("../src/Pay.java"));
            }
            case "policy" -> f.repo.write(MANIFEST, "schema_version: 1\ncontracts: []\n");
            case "build" -> f.repo.write("pom.xml", "<project><!-- skip tests --></project>\n");
            default -> throw new IllegalArgumentException();
        }
        f.repo.commit();
        var git =
                new GitRepository(
                        f.repo.root,
                        f.git.base(),
                        f.repo.head,
                        f.git.policyCommit(),
                        f.git.targetHead());
        var candidate = new Fixture(f.repo, git, new ContractPolicy(git, MANIFEST));
        evidence(candidate, tests("passed"));
        var result = verify(candidate);
        assertThat(result.path("verification").asText()).isEqualTo("incomplete");
        assertThat(result.path("evidence_gaps").toString()).contains("Approved oracle changed");
        assertThat(result.path("contracts").size()).isEqualTo(1);
    }

    @Test
    void invalidManifestFieldsDuplicatesAndUnknownVerifiersAreRejected() throws Exception {
        var f = fixture();
        var original = f.policy.manifest();
        for (String mutation :
                List.of("duplicate", "test", "verifier", "scope", "command", "empty")) {
            ObjectNode manifest = original.deepCopy();
            ObjectNode contract = (ObjectNode) manifest.path("contracts").get(0);
            switch (mutation) {
                case "duplicate" ->
                        ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("contracts"))
                                .add(contract.deepCopy());
                case "test" ->
                        ((com.fasterxml.jackson.databind.node.ArrayNode) contract.path("tests"))
                                .add(contract.path("tests").get(0).deepCopy());
                case "verifier" -> contract.put("verifier", "shell");
                case "scope" -> contract.putArray("scope").add("../outside");
                case "command" -> contract.put("command", "execute something");
                case "empty" -> manifest.putArray("contracts");
                default -> throw new IllegalArgumentException();
            }
            f.repo.write(MANIFEST, Json.stringify(manifest));
            f.repo.commit();
            var proposed = new GitRepository(f.repo.root, f.repo.head, f.repo.head);
            assertThatThrownBy(() -> new ContractPolicy(proposed, MANIFEST))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cliProducesShadowReportsWithoutModelConfigurationOrCredentials() throws Exception {
        var f = fixture();
        var output = temp.resolve("out");
        // Deliberately choose a policy commit separate from the comparison base.
        try (var context = UndertowApplication.start()) {
            assertThat(
                            context.getBean(CommandLine.class)
                                    .execute(
                                            "verify",
                                            "--repo",
                                            f.repo.root.toString(),
                                            "--base",
                                            f.git.base(),
                                            "--head",
                                            f.git.head(),
                                            "--policy-commit",
                                            f.git.policyCommit(),
                                            "--output",
                                            output.toString()))
                    .isZero();
        }
        assertThat(
                        Json.parse(Files.readString(output.resolve("contracts.json")))
                                .path("verification")
                                .asText())
                .isEqualTo("incomplete");
        assertThat(Files.readString(output.resolve("contracts.md")))
                .contains("PAYMENT-RETRY", "incomplete", "Shadow mode");
    }

    @Test
    void trailingYamlDocumentsCannotExtendTheApprovedManifest() throws Exception {
        var f = fixture();
        f.repo.write(
                MANIFEST,
                Files.readString(f.repo.root.resolve(MANIFEST)) + "\n---\ncommand: ignored\n");
        f.repo.commit();
        var proposed = new GitRepository(f.repo.root, f.repo.head, f.repo.head);
        assertThatThrownBy(() -> new ContractPolicy(proposed, MANIFEST))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void markdownEscapesOwnerContentAndLinksToExactEvidence() throws Exception {
        var f = fixture();
        evidence(f, tests("passed"));
        ObjectNode result = (ObjectNode) verify(f);
        ((ObjectNode) result.path("contracts").get(0))
                .put("expectation", "@everyone <script> [click](bad)");
        ContractVerifier.write(temp.resolve("out"), result);
        assertThat(Files.readString(temp.resolve("out/contracts.md")))
                .contains("actions/runs/123", "artifact `77`", "&#64;everyone", "&lt;script&gt;")
                .doesNotContain("@everyone", "<script>", "[click](bad)");
    }
}
