package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ReplayClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2PolicyTest {
    @TempDir Path temp;

    private ObjectNode v2(TestRepository repo) throws Exception {
        var catalog = Json.MAPPER.createObjectNode().put("schema_version", 2);
        var rules = catalog.putArray("rules");
        for (var input : new Policy(repo.git()).rules()) {
            ObjectNode rule = input.deepCopy();
            rule.remove("kind");
            rule.putObject("examples")
                    .put("unsafe", "Unsafe behavior")
                    .put("corrected", "Corrected behavior")
                    .put("benign", "Benign case");
            rules.add(rule);
        }
        return catalog;
    }

    private GitRepository withPolicy(TestRepository repo, ObjectNode catalog) throws Exception {
        repo.write("config/languages/java/rules.yaml", Json.stringify(catalog));
        repo.commit();
        String policy = repo.head;
        repo.write("src/Pay.java", "class Pay { int amount() { return 3; } } // " + policy + "\n");
        repo.commit();
        return new GitRepository(repo.root, policy, repo.head);
    }

    @Test
    void v1MigrationPreservesMeaningAndV2Validates() throws Exception {
        var repo = new TestRepository(temp);
        var original = new Policy(repo.git());
        var policy = new Policy(withPolicy(repo, v2(repo)));
        assertThat(policy.catalog().version()).isEqualTo(2);
        assertThat(policy.rule("JAVA-TX-001").path("wording"))
                .isEqualTo(original.rule("JAVA-TX-001").path("wording"));
    }

    @Test
    void invalidCatalogFailsBeforeModelAccess() throws Exception {
        for (String mutation :
                List.of("id", "unknown", "reference", "glob", "duplicate", "executor")) {
            var repo = new TestRepository(temp.resolve(mutation));
            var catalog = v2(repo);
            ObjectNode rule = (ObjectNode) catalog.path("rules").get(0);
            switch (mutation) {
                case "id" -> rule.put("id", "bad id");
                case "unknown" -> rule.put("shell", "echo dangerous");
                case "reference" -> rule.putArray("sections").add(9999);
                case "glob" -> rule.putArray("include").add("../**");
                case "duplicate" ->
                        ((com.fasterxml.jackson.databind.node.ArrayNode) catalog.path("rules"))
                                .add(rule.deepCopy());
                case "executor" ->
                        rule.put("execution", "deterministic").put("executor", "arbitrary_shell");
                default -> throw new AssertionError();
            }
            var git = withPolicy(repo, catalog);
            assertThatThrownBy(() -> new Reviewer(git, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void expiredExceptionIsRejectedAndHeadCannotDisableBaseRule() throws Exception {
        var repo = new TestRepository(temp);
        var catalog = v2(repo);
        catalog.putArray("exceptions")
                .addObject()
                .put("id", "EXCEPTION-TX-001")
                .put("rule_id", "JAVA-TX-001")
                .put("justification", "Legacy integration")
                .put("owner", "payments")
                .put("expires", "2000-01-01")
                .putArray("include")
                .add("src/**");
        var git = withPolicy(repo, catalog);
        var expired = git;
        assertThatThrownBy(() -> new Policy(expired)).hasMessageContaining("Expired");
        catalog.remove("exceptions");
        git = withPolicy(repo, catalog);
        String base = git.base();
        ((ObjectNode) catalog.path("rules").get(0)).put("enabled", false);
        repo.write("config/languages/java/rules.yaml", Json.stringify(catalog));
        repo.commit();
        var trusted = new Policy(new GitRepository(repo.root, base, repo.head));
        assertThat(trusted.rule("JAVA-TX-001").path("enabled").asBoolean()).isTrue();
    }

    @Test
    void scopeExceptionsAndMissingEvidenceAreVisible() throws Exception {
        var repo = new TestRepository(temp);
        var catalog = v2(repo);
        catalog.putArray("exceptions")
                .addObject()
                .put("id", "EXCEPTION-TX-001")
                .put("rule_id", "JAVA-TX-001")
                .put("justification", "Reviewed legacy boundary")
                .put("owner", "payments")
                .put("expires", "2099-01-01")
                .putArray("include")
                .add("src/**");
        var git = withPolicy(repo, catalog);
        var report =
                new Reviewer(git, null)
                        .run(new ReplayClient(List.of()), temp.resolve("out"), "collect");
        assertThat(report.details().ruleCoverage())
                .anyMatch(
                        row ->
                                row.id().equals("JAVA-TX-001")
                                        && row.status().equals("exception")
                                        && !row.exceptions().isEmpty());
        assertThat(report.details().ruleCoverage())
                .anyMatch(row -> row.status().equals("not_evaluated"));
        assertThat(report.status()).isEqualTo("partial");
    }

    @Test
    void targetPolicyAndMergeBaseHaveIndependentAuthority() throws Exception {
        var repo = new TestRepository(temp);
        var catalog = v2(repo);
        ((ObjectNode) catalog.path("rules").get(0)).put("owner", "current target owner");
        var target = withPolicy(repo, catalog).base();
        var git = new GitRepository(repo.root, repo.base, repo.head, target, target);
        assertThat(new Policy(git).rule("JAVA-TX-001").path("owner").asText())
                .isEqualTo("current target owner");
        var report =
                new Reviewer(git, null)
                        .run(new ReplayClient(List.of()), temp.resolve("out"), "collect");
        assertThat(report.baseSha()).isEqualTo(repo.base);
        assertThat(report.details().policyCommit()).isEqualTo(target);
        assertThat(report.details().targetHead()).isEqualTo(target);
    }
}
