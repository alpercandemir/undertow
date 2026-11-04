package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undertow.github.GitHubClient;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ReplayClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublisherTest {
    @TempDir Path temp;

    @Test
    void staleHeadNeverReceivesCurrentSummary() throws Exception {
        var repo = new TestRepository(temp);
        var git = repo.git();
        var dir = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), dir, "collect");
        List<String> writes = new ArrayList<>();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                writes.add(path);
                            }
                            if (path.startsWith("/issues/")) {
                                return Json.parse("[]");
                            }
                            return metadata(git.base(), "0".repeat(40));
                        });
        assertThatThrownBy(
                        () ->
                                client.publish(
                                        1,
                                        report,
                                        git,
                                        new Policy(git),
                                        EvidenceStore.restore(
                                                Json.parse(
                                                        Files.readString(
                                                                dir.resolve("evidence.json"))))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("stale");
        assertThat(writes).isEmpty();
    }

    @Test
    void botSummaryIsUpdatedWithoutCreatingDuplicate() throws Exception {
        var repo = new TestRepository(temp);
        var git = repo.git();
        var dir = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), dir, "collect");
        List<String> writes = new ArrayList<>();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                writes.add(method + " " + path);
                                return posted(42, body.path("body").asText());
                            }
                            if (path.startsWith("/issues/")) {
                                return Json.parse(
                                        "[{\"id\":42,\"user\":{\"login\":\"github-actions[bot]\",\"id\":41898282,\"type\":\"Bot\"},\"body\":\"<!-- undertow-review:v1 --> previous\"}]");
                            }
                            return metadata(git.base(), git.head());
                        });
        long id =
                client.publish(
                        1,
                        report,
                        git,
                        new Policy(git),
                        EvidenceStore.restore(
                                Json.parse(Files.readString(dir.resolve("evidence.json")))));
        assertThat(id).isEqualTo(42);
        assertThat(writes)
                .containsExactly("PATCH /issues/comments/42", "PATCH /issues/comments/42");
    }

    @Test
    void changedHeadDuringPublicationIsMarkedStale() throws Exception {
        var repo = new TestRepository(temp);
        var git = repo.git();
        var dir = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), dir, "collect");
        List<String> bodies = new ArrayList<>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                bodies.add(body.path("body").asText());
                                return posted(42, body.path("body").asText());
                            }
                            if (path.startsWith("/issues/")) {
                                return Json.parse("[]");
                            }
                            return metadata(
                                    git.base(),
                                    reads.incrementAndGet() == 1 ? git.head() : "0".repeat(40));
                        });
        client.publish(
                1,
                report,
                git,
                new Policy(git),
                EvidenceStore.restore(Json.parse(Files.readString(dir.resolve("evidence.json")))));
        assertThat(bodies).hasSize(2);
        assertThat(bodies.getLast()).contains("**Stale review:**");
    }

    @Test
    void fakeMarkerFromAnotherAuthorIsNotOverwritten() throws Exception {
        var repo = new TestRepository(temp);
        var git = repo.git();
        var dir = temp.resolve("out");
        var report = new Reviewer(git, null).run(new ReplayClient(List.of()), dir, "collect");
        List<String> writes = new ArrayList<>();
        var client =
                new GitHubClient(
                        "owner/repo",
                        "test-token",
                        (method, path, body) -> {
                            if (!method.equals("GET")) {
                                writes.add(method + " " + path);
                                return posted(43, body.path("body").asText());
                            }
                            if (path.startsWith("/issues/")) {
                                return Json.parse(
                                        "[{\"id\":42,\"user\":{\"login\":\"attacker\"},\"body\":\"<!-- undertow-review:v1 -->\"}]");
                            }
                            return metadata(git.base(), git.head());
                        });
        client.publish(
                1,
                report,
                git,
                new Policy(git),
                EvidenceStore.restore(Json.parse(Files.readString(dir.resolve("evidence.json")))));
        assertThat(writes).containsExactly("POST /issues/1/comments", "PATCH /issues/comments/43");
    }

    private static com.fasterxml.jackson.databind.JsonNode posted(long id, String body) {
        var node = Json.MAPPER.createObjectNode().put("id", id).put("body", body);
        node.putObject("user")
                .put("login", "github-actions[bot]")
                .put("id", 41898282)
                .put("type", "Bot");
        return node;
    }

    private static com.fasterxml.jackson.databind.JsonNode metadata(String base, String head) {
        var node =
                Json.MAPPER.createObjectNode().put("state", "open").put("ref", "refs/heads/main");
        node.putObject("object").put("type", "commit").put("sha", base);
        node.putObject("head").put("sha", head);
        node.putObject("base")
                .put("sha", base)
                .put("ref", "main")
                .putObject("repo")
                .put("full_name", "owner/repo")
                .put("id", 1234);
        return node;
    }
}
