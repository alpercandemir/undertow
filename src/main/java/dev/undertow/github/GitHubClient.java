package dev.undertow.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.changes.ChangeAdapters;
import dev.undertow.changes.ChangeRequest;
import dev.undertow.changes.ChangeRequestRef;
import dev.undertow.changes.ChangeSnapshot;
import dev.undertow.changes.FindingIdentity;
import dev.undertow.changes.PublicationValidation;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Markdown;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** GitHub.com adapter. Identity configuration comes from the trusted operator, never PR data. */
public final class GitHubClient
        implements ChangeAdapters.MetadataReader, ChangeAdapters.SummaryPublisher {
    public static final String MARKER = "<!-- undertow-review:v1 -->";

    public record PullRequest(
            String repository, int number, String baseSha, String headSha, String state) {}

    public record BotIdentity(String login, long id) {
        public BotIdentity {
            if (!login.matches("[A-Za-z0-9_-]+\\[bot\\]") || id < 1)
                throw new IllegalArgumentException(
                        "Expected bot login and numeric user ID required");
        }
    }

    @FunctionalInterface
    public interface Transport {
        JsonNode request(String method, String suffix, JsonNode body)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface WriteGuard {
        void verify() throws IOException, InterruptedException;
    }

    public static final class ApiFailure extends IOException {
        private final int status;

        public ApiFailure(int status) {
            super(
                    "GitHub HTTP "
                            + status
                            + (status == 403
                                    ? " (permission or rate limit)"
                                    : status == 429 ? " (rate limit)" : ""));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static final class AmbiguousPublication extends IOException {
        public AmbiguousPublication() {
            super("Publication outcome ambiguous; query owned summary before retrying creation");
        }
    }

    private final Transport transport;
    private final String repository;
    private final String token;
    private final BotIdentity bot;
    private boolean recoveryOnly;
    private WriteGuard writeGuard = () -> {};

    /** Optional service lease check, called before every external mutation. */
    public GitHubClient guardWrites(WriteGuard guard) {
        writeGuard = java.util.Objects.requireNonNull(guard);
        return this;
    }

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public GitHubClient(String repository, String token) {
        this(repository, token, null);
    }

    public GitHubClient(String repository, String token, Transport transport) {
        this(repository, token, transport, new BotIdentity("github-actions[bot]", 41898282));
    }

    public GitHubClient(String repository, String token, Transport transport, BotIdentity bot) {
        ref(repository, 1);
        this.repository = repository;
        this.token = token;
        this.bot = bot;
        this.transport = transport == null ? this::networkRequest : transport;
    }

    public GitHubClient recoveryOnly(boolean value) {
        recoveryOnly = value;
        return this;
    }

    public static ChangeRequestRef ref(String repository, int number) {
        return new ChangeRequestRef(
                "github",
                URI.create("https://github.com"),
                repository,
                number,
                URI.create("https://github.com/" + repository + "/pull/" + number));
    }

    @Override
    public ChangeAdapters.Capabilities capabilities() {
        return new ChangeAdapters.Capabilities(true, false, false, false);
    }

    @Override
    public ChangeRequest read(ChangeRequestRef ref) throws IOException, InterruptedException {
        if (!belongs(ref))
            throw new IllegalArgumentException("Change request does not belong to this adapter");
        JsonNode data = request("GET", "/pulls/" + ref.number(), null);
        if (!data.path("base").path("repo").path("full_name").asText().equalsIgnoreCase(repository))
            throw new IOException("PR repository does not match");
        String branch = data.path("base").path("ref").asText();
        if (branch.isBlank() || branch.length() > 255)
            throw new IOException("Missing or invalid target branch");
        // PR base.sha can remain the creation-time base after the target branch advances.
        // Resolve the live branch ref on every metadata/publication verification.
        JsonNode target =
                request(
                        "GET",
                        "/git/ref/heads/"
                                + java.net.URLEncoder.encode(
                                                branch, java.nio.charset.StandardCharsets.UTF_8)
                                        .replace("+", "%20"),
                        null);
        if (!target.path("ref").asText().equals("refs/heads/" + branch)
                || !target.path("object").path("type").asText().equals("commit"))
            throw new IOException("Target branch response does not match");
        String base = target.path("object").path("sha").asText(),
                head = data.path("head").path("sha").asText();
        long repositoryId = data.path("base").path("repo").path("id").asLong();
        if (repositoryId < 1) throw new IOException("Missing stable GitHub repository identity");
        return new ChangeRequest(
                new ChangeRequestRef(
                        ref.provider(),
                        ref.instance(),
                        ref.repository(),
                        ref.number(),
                        ref.url(),
                        Long.toString(repositoryId)),
                new ChangeSnapshot(head, base, base, base, ""),
                data.path("state").asText(),
                data.path("draft").asBoolean());
    }

    private boolean belongs(ChangeRequestRef ref) {
        var expected = ref(repository, ref.number());
        return ref.provider().equals(expected.provider())
                && ref.instance().equals(expected.instance())
                && ref.repository().equals(expected.repository())
                && ref.url().equals(expected.url());
    }

    public PullRequest pullRequest(int number) throws IOException, InterruptedException {
        var request = read(ref(repository, number));
        return new PullRequest(
                repository,
                number,
                request.snapshot().targetHead(),
                request.snapshot().sourceHead(),
                request.state());
    }

    private boolean owned(JsonNode comment) {
        return comment.path("user").path("login").asText().equals(bot.login())
                && comment.path("user").path("id").asLong() == bot.id()
                && comment.path("user").path("type").asText().equals("Bot")
                && comment.path("body").asText().startsWith(MARKER);
    }

    private JsonNode summary(int number) throws IOException, InterruptedException {
        JsonNode found = null;
        for (int page = 1; page <= 10; page++) {
            JsonNode comments =
                    request(
                            "GET",
                            "/issues/" + number + "/comments?per_page=100&page=" + page,
                            null);
            if (!comments.isArray()) throw new IOException("Invalid comment response");
            for (JsonNode comment : comments)
                if (owned(comment)) {
                    if (found != null)
                        throw new IOException(
                                "Multiple bot summary comments; reconcile before publishing");
                    if (comment.path("id").asLong() < 1)
                        throw new IOException("Invalid owned summary ID");
                    found = comment;
                }
            if (comments.size() < 100) return found;
        }
        throw new IOException("Comment pagination limit reached");
    }

    private boolean current(ChangeRequest request, Report report, GitRepository git) {
        return request.state().equals("open")
                && request.snapshot().sourceHead().equals(report.headSha())
                && request.snapshot().targetHead().equals(git.targetHead());
    }

    public long publish(
            int number, Report report, GitRepository git, Policy policy, EvidenceStore evidence)
            throws IOException, InterruptedException {
        var result = publish(ref(repository, number), report, git, policy, evidence, "");
        if (result.commentId() == 0) throw new IOException(result.reason());
        return result.commentId();
    }

    @Override
    public ChangeAdapters.Publication publish(
            ChangeRequestRef ref,
            Report report,
            GitRepository git,
            Policy policy,
            EvidenceStore evidence,
            String detailsUrl)
            throws IOException, InterruptedException {
        PublicationValidation.validate(report, git, policy, evidence);
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("GITHUB_TOKEN required to publish");
        if (!belongs(ref))
            throw new IllegalArgumentException("Change request does not belong to this adapter");
        JsonNode old = summary(ref.number());
        ChangeRequest current = read(ref);
        if (!current(current, report, git))
            return new ChangeAdapters.Publication(
                    "stale",
                    0,
                    report.headSha(),
                    report.runId(),
                    Map.of(),
                    "Review is stale or PR closed; summary was not published");
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (var finding : report.findings())
            fingerprints.put(
                    finding.id(), FindingIdentity.fingerprint(current.ref(), finding, git));
        String body =
                Markdown.summary(
                        report,
                        ref,
                        detailsUrl,
                        fingerprints,
                        old == null ? "" : old.path("body").asText());
        String pending =
                MARKER
                        + "\n**Publication pending verification:** this report is not current success.\n\n"
                        + body.substring(MARKER.length());
        long id;
        if (old == null) {
            if (recoveryOnly) throw new AmbiguousPublication();
            try {
                JsonNode created =
                        request(
                                "POST",
                                "/issues/" + ref.number() + "/comments",
                                Json.MAPPER.createObjectNode().put("body", pending));
                if (!owned(created))
                    throw new IOException(
                            "Returned publication is not owned by the configured application bot");
                id = created.path("id").asLong();
            } catch (IOException failure) {
                if (failure instanceof ApiFailure api && api.status() < 500) throw failure;
                JsonNode recovered = summary(ref.number());
                if (recovered == null
                        || !recovered
                                .path("body")
                                .asText()
                                .contains("<!-- undertow-run:" + report.runId() + " -->"))
                    throw new AmbiguousPublication();
                id = recovered.path("id").asLong();
            }
        } else {
            id = old.path("id").asLong();
            request(
                    "PATCH",
                    "/issues/comments/" + id,
                    Json.MAPPER.createObjectNode().put("body", pending));
        }
        if (id < 1) throw new AmbiguousPublication();
        String status = "published";
        try {
            if (!current(read(ref), report, git)) status = "stale";
            request(
                    "PATCH",
                    "/issues/comments/" + id,
                    Json.MAPPER
                            .createObjectNode()
                            .put("body", status.equals("stale") ? stale(body) : body));
            if (status.equals("published") && !current(read(ref), report, git)) {
                status = "stale";
                request(
                        "PATCH",
                        "/issues/comments/" + id,
                        Json.MAPPER.createObjectNode().put("body", stale(body)));
            }
        } catch (IOException failure) {
            // Pending verification is the safe default. Best-effort invalidate any finalized body.
            try {
                request(
                        "PATCH",
                        "/issues/comments/" + id,
                        Json.MAPPER.createObjectNode().put("body", stale(body)));
            } catch (IOException ignored) {
                /* A failed invalidation remains visible in the receipt. */
            }
            throw new IOException(
                    "Publication verification failed; report must not be treated as current",
                    failure);
        }
        return new ChangeAdapters.Publication(
                status,
                id,
                report.headSha(),
                report.runId(),
                fingerprints,
                status.equals("stale")
                        ? "PR closed or commits changed during publication"
                        : "Verified at publication; optional Checks/annotations unavailable, use summary/artifacts");
    }

    public void markOutdated(int number) throws IOException, InterruptedException {
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("GITHUB_TOKEN required to publish");
        var request = read(ref(repository, number));
        JsonNode old = summary(number);
        if (old != null
                && (!old.path("body")
                                .asText()
                                .contains(
                                        "<!-- undertow-head:"
                                                + request.snapshot().sourceHead()
                                                + " -->")
                        || !old.path("body")
                                .asText()
                                .contains(
                                        "<!-- undertow-target:"
                                                + request.snapshot().targetHead()
                                                + " -->")
                        || !request.state().equals("open")))
            request(
                    "PATCH",
                    "/issues/comments/" + old.path("id").asLong(),
                    Json.MAPPER.createObjectNode().put("body", stale(old.path("body").asText())));
    }

    private static String stale(String body) {
        if (body.startsWith(MARKER + "\n**Stale review:**")) return body;
        return MARKER
                + "\n**Stale review:** PR state or commits changed; this report is outdated.\n\n"
                + body.substring(MARKER.length());
    }

    private JsonNode request(String method, String suffix, JsonNode body)
            throws IOException, InterruptedException {
        if (!method.equals("GET")) writeGuard.verify();
        for (int attempt = 0; ; attempt++) {
            try {
                return transport.request(method, suffix, body);
            } catch (ApiFailure failure) {
                if (!method.equals("GET")
                        || attempt >= 2
                        || !(failure.status() == 429 || failure.status() >= 500)) throw failure;
                Thread.sleep(100L << attempt);
            }
        }
    }

    private JsonNode networkRequest(String method, String suffix, JsonNode body)
            throws IOException, InterruptedException {
        var builder =
                HttpRequest.newBuilder(
                                URI.create("https://api.github.com/repos/" + repository + suffix))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Cache-Control", "no-cache")
                        .header("User-Agent", "undertow/0.2");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            byte[] data = stream.readNBytes(1000001);
            if (data.length > 1000000) throw new IOException("GitHub response exceeds limit");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ApiFailure(response.statusCode());
            return Json.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
