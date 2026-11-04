package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.changes.ChangeAdapters;
import dev.undertow.changes.FindingIdentity;
import dev.undertow.changes.PublicationValidation;
import dev.undertow.findings.Finding;
import dev.undertow.github.GitHubClient;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ReplayClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2AdapterTest {
    @TempDir Path temp;

    private JsonNode metadata(TestRepository repo) {
        var data =
                Json.MAPPER.createObjectNode().put("state", "open").put("ref", "refs/heads/main");
        data.putObject("object").put("type", "commit").put("sha", repo.base);
        data.putObject("head").put("sha", repo.head);
        data.putObject("base")
                .put("sha", repo.base)
                .put("ref", "main")
                .putObject("repo")
                .put("full_name", "owner/repo")
                .put("id", 1234);
        return data;
    }

    private JsonNode comment(String body) {
        var data = Json.MAPPER.createObjectNode().put("id", 42).put("body", body);
        data.putObject("user")
                .put("login", "github-actions[bot]")
                .put("id", 41898282)
                .put("type", "Bot");
        return data;
    }

    @Test
    void ambiguousPostIsReconciledWithoutCreatingDuplicate() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var out = temp.resolve("out");
        var git = repo.git();
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        var stored = new AtomicReference<JsonNode>();
        var posts = new AtomicInteger();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (method.equals("POST")) {
                                posts.incrementAndGet();
                                stored.set(comment(body.path("body").asText()));
                                throw new IOException("connection lost after acceptance");
                            }
                            if (method.equals("PATCH")) {
                                stored.set(comment(body.path("body").asText()));
                                return stored.get();
                            }
                            if (path.startsWith("/issues/"))
                                return stored.get() == null
                                        ? Json.MAPPER.createArrayNode()
                                        : Json.MAPPER.createArrayNode().add(stored.get());
                            return metadata(repo);
                        });
        var publication =
                client.publish(
                        GitHubClient.ref("owner/repo", 1),
                        report,
                        git,
                        new Policy(git),
                        EvidenceStore.restore(
                                Json.parse(Files.readString(out.resolve("evidence.json")))),
                        "");
        assertThat(publication.status()).isEqualTo("published");
        assertThat(posts.get()).isEqualTo(1);
        assertThat(stored.get().path("body").asText()).doesNotContain("Publication pending");
        client.publish(
                1,
                report,
                git,
                new Policy(git),
                EvidenceStore.restore(Json.parse(Files.readString(out.resolve("evidence.json")))));
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test
    void permissionAndRateLimitFailuresAreBounded() throws Exception {
        var count = new AtomicInteger();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            count.incrementAndGet();
                            throw new GitHubClient.ApiFailure(429);
                        });
        var limited = client;
        assertThatThrownBy(() -> limited.pullRequest(1))
                .isInstanceOf(GitHubClient.ApiFailure.class);
        assertThat(count.get()).isEqualTo(3);
        count.set(0);
        client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            count.incrementAndGet();
                            throw new GitHubClient.ApiFailure(403);
                        });
        var denied = client;
        assertThatThrownBy(() -> denied.pullRequest(1)).isInstanceOf(GitHubClient.ApiFailure.class);
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void fakeAdapterConsumesSameValidatedReportWithoutCoreChanges() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var git = repo.git();
        var out = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        ChangeAdapters.SnapshotAccess access = snapshot -> git;
        ChangeAdapters.SummaryPublisher fake =
                (ref, result, source, policy, evidence, details) -> {
                    PublicationValidation.validate(result, source, policy, evidence);
                    return new ChangeAdapters.Publication(
                            "published",
                            1,
                            result.headSha(),
                            result.runId(),
                            Map.of(),
                            "offline adapter contract only");
                };
        var snapshot =
                new dev.undertow.changes.ChangeSnapshot(
                        git.head(), git.base(), git.base(), git.base(), "");
        assertThat(
                        fake.publish(
                                        GitHubClient.ref("owner/repo", 1),
                                        report,
                                        access.open(snapshot),
                                        new Policy(git),
                                        EvidenceStore.restore(
                                                Json.parse(
                                                        Files.readString(
                                                                out.resolve("evidence.json")))),
                                        "")
                                .status())
                .isEqualTo("published");
        assertThatThrownBy(() -> GitHubClient.ref("../attacker", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintsIgnoreModelIdButSeparateRepositoryAndLocation() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var finding =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        Json.parse(Files.readString(Path.of("docs/sample-review.json")))
                                .path("findings")
                                .get(0)
                                .deepCopy();
        finding.putObject("location")
                .put("commit", repo.head)
                .put("path", "src/Pay.java")
                .put("snapshot", "head")
                .put("start_line", 1)
                .put("end_line", 1);
        var first = Json.MAPPER.treeToValue(finding, Finding.class);
        finding.put("id", "new-model-id");
        var second = Json.MAPPER.treeToValue(finding, Finding.class);
        String fingerprint =
                FindingIdentity.fingerprint(GitHubClient.ref("owner/repo", 1), first, repo.git());
        assertThat(
                        FindingIdentity.fingerprint(
                                GitHubClient.ref("owner/repo", 1), second, repo.git()))
                .isEqualTo(fingerprint);
        assertThat(
                        FindingIdentity.fingerprint(
                                GitHubClient.ref("other/repo", 1), first, repo.git()))
                .isNotEqualTo(fingerprint);
        assertThat(
                        FindingIdentity.fingerprint(
                                GitHubClient.ref("owner/repo", 2), first, repo.git()))
                .isNotEqualTo(fingerprint);
        finding.putArray("rule_ids").add("JAVA-LOG-001");
        assertThat(
                        FindingIdentity.fingerprint(
                                GitHubClient.ref("owner/repo", 1),
                                Json.MAPPER.treeToValue(finding, Finding.class),
                                repo.git()))
                .isNotEqualTo(fingerprint);
    }

    @Test
    void closedHeadAndTargetChangesAreRejectedBeforeWrites() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var git = repo.git();
        var out = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        var evidence =
                EvidenceStore.restore(Json.parse(Files.readString(out.resolve("evidence.json"))));
        for (String scenario : List.of("closed", "head", "target")) {
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) metadata(repo);
            if (scenario.equals("closed")) data.put("state", "closed");
            if (scenario.equals("head"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("head"))
                        .put("sha", "0".repeat(40));
            if (scenario.equals("target"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("object"))
                        .put("sha", "0".repeat(40));
            var writes = new AtomicInteger();
            var client =
                    new GitHubClient(
                            "owner/repo",
                            "test-token",
                            (method, path, body) -> {
                                if (!method.equals("GET")) {
                                    writes.incrementAndGet();
                                    return comment(body.path("body").asText());
                                }
                                return path.startsWith("/issues/")
                                        ? Json.MAPPER.createArrayNode()
                                        : data;
                            });
            assertThat(
                            client.publish(
                                            GitHubClient.ref("owner/repo", 1),
                                            report,
                                            git,
                                            new Policy(git),
                                            evidence,
                                            "")
                                    .status())
                    .isEqualTo("stale");
            assertThat(writes.get()).isZero();
        }
    }

    @Test
    void closedDuringPublicationIsMarkedStale() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var git = repo.git();
        var out = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        var reads = new AtomicInteger();
        var bodies = new java.util.ArrayList<String>();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                bodies.add(body.path("body").asText());
                                return comment(body.path("body").asText());
                            }
                            if (path.startsWith("/issues/")) return Json.MAPPER.createArrayNode();
                            var data =
                                    (com.fasterxml.jackson.databind.node.ObjectNode) metadata(repo);
                            if (reads.incrementAndGet() > 1) data.put("state", "closed");
                            return data;
                        });
        var result =
                client.publish(
                        GitHubClient.ref("owner/repo", 1),
                        report,
                        git,
                        new Policy(git),
                        EvidenceStore.restore(
                                Json.parse(Files.readString(out.resolve("evidence.json")))),
                        "");
        assertThat(result.status()).isEqualTo("stale");
        assertThat(bodies.getFirst()).contains("Publication pending verification");
        assertThat(bodies.getLast()).contains("**Stale review:**");
    }

    @Test
    void liveTargetRefOverridesCachedPrBaseAndIsCheckedBeforePublication() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var git = repo.git();
        var out = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) metadata(repo);
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("base"))
                .put("ref", "release/2026");
        var target = Json.MAPPER.createObjectNode().put("ref", "refs/heads/release/2026");
        target.putObject("object").put("type", "commit").put("sha", repo.head);
        var branchReads = new AtomicInteger();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            assertThat(method).isEqualTo("GET");
                            if (path.startsWith("/issues/")) return Json.MAPPER.createArrayNode();
                            if (path.startsWith("/git/ref/")) {
                                assertThat(path).isEqualTo("/git/ref/heads/release%2F2026");
                                branchReads.incrementAndGet();
                                return target;
                            }
                            return data;
                        });
        var snapshot = client.read(GitHubClient.ref("owner/repo", 1)).snapshot();
        assertThat(snapshot.targetHead()).isEqualTo(repo.head);
        assertThat(snapshot.trustedPolicyCommit()).isEqualTo(repo.head);
        assertThat(
                        client.publish(
                                        GitHubClient.ref("owner/repo", 1),
                                        report,
                                        git,
                                        new Policy(git),
                                        EvidenceStore.restore(
                                                Json.parse(
                                                        Files.readString(
                                                                out.resolve("evidence.json")))),
                                        "")
                                .status())
                .isEqualTo("stale");
        assertThat(branchReads.get()).isEqualTo(2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) target.path("object")).put("type", "tag");
        assertThatThrownBy(() -> client.read(GitHubClient.ref("owner/repo", 1)))
                .hasMessageContaining("Target branch response");
    }

    @Test
    void paginationAndNumericBotOwnershipAreEnforced() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        var git = repo.git();
        var out = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), out, "collect");
        var pages = new AtomicInteger();
        var posts = new AtomicInteger();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                if (method.equals("POST")) posts.incrementAndGet();
                                return comment(body.path("body").asText());
                            }
                            if (!path.startsWith("/issues/")) return metadata(repo);
                            pages.incrementAndGet();
                            var comments = Json.MAPPER.createArrayNode();
                            if (path.endsWith("page=1")) {
                                for (int i = 0; i < 100; i++) {
                                    var fake =
                                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                                    comment(GitHubClient.MARKER);
                                    ((com.fasterxml.jackson.databind.node.ObjectNode)
                                                    fake.path("user"))
                                            .put("id", i + 1);
                                    comments.add(fake);
                                }
                            } else comments.add(comment(GitHubClient.MARKER));
                            return comments;
                        });
        assertThat(client.read(GitHubClient.ref("owner/repo", 1)).ref().stableRepositoryId())
                .isEqualTo("1234");
        client.publish(
                1,
                report,
                git,
                new Policy(git),
                EvidenceStore.restore(Json.parse(Files.readString(out.resolve("evidence.json")))));
        assertThat(pages.get()).isEqualTo(2);
        assertThat(posts.get()).isZero();
    }
}
