package dev.undertow.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.github.GitHubClient;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ModelClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "undertow",
        mixinStandardHelpOptions = true,
        version = "Undertow 0.1.0-alpha.1",
        description = "Evidence-backed Java change-risk reviewer")
public final class Undertow implements Runnable {
    @Spec private CommandSpec command;

    @Override
    public void run() {
        command.commandLine().usage(command.commandLine().getOut());
    }

    public static void main(String[] args) {
        UndertowApplication.main(args);
    }

    @Command(
            name = "review",
            mixinStandardHelpOptions = true,
            description = "Review pinned local Git snapshots; never execute reviewed code")
    public static final class Review implements Callable<Integer> {
        private final ModelClientFactory models;

        public Review(ModelClientFactory models) {
            this.models = models;
        }

        @Option(names = "--repo", required = true)
        Path repo;

        @Option(names = "--base", required = true)
        String base;

        @Option(names = "--head", required = true)
        String head;

        @Option(names = "--policy-commit", description = "Trusted policy commit; defaults to base")
        String policyCommit;

        @Option(names = "--target-head", description = "Pinned target tip; defaults to base")
        String targetHead;

        @Option(names = "--output", defaultValue = ".undertow/review")
        Path output;

        @Option(names = "--replay", description = "Offline fake model response bundle")
        Path replay;

        @Option(names = "--mode", defaultValue = "tools", description = "tools, diff, or collect")
        String mode;

        @Option(names = "--ci-bundle")
        Path ci;

        @Option(
                names = "--ci-receipt",
                description = "Trusted importer receipt, kept outside PR-controlled input")
        Path ciReceipt;

        @Override
        public Integer call() throws Exception {
            var git =
                    new GitRepository(
                            repo,
                            base,
                            head,
                            policyCommit == null ? base : policyCommit,
                            targetHead == null ? base : targetHead);
            var reviewer = new Reviewer(git, ci, ciReceipt);
            try (ModelClient model = models.create(reviewer, replay, mode)) {
                Report report = reviewer.run(model, output, mode);
                System.out.println(
                        "Review "
                                + report.status()
                                + ": "
                                + report.findings().size()
                                + " findings; "
                                + output.toAbsolutePath().resolve("report.md"));
                return report.status().equals("failed") ? 2 : 0;
            }
        }
    }

    @Command(
            name = "pr-info",
            mixinStandardHelpOptions = true,
            description = "Capture GitHub PR base/head metadata without reviewing or posting")
    public static final class PrInfo implements Callable<Integer> {
        @Option(names = "--repository", required = true)
        String repository;

        @Option(names = "--pr", required = true)
        int pr;

        @Option(names = "--output", required = true)
        Path output;

        @Override
        public Integer call() throws Exception {
            var info = new GitHubClient(repository, System.getenv("GITHUB_TOKEN")).pullRequest(pr);
            Json.write(output, info);
            return 0;
        }
    }

    @Command(
            name = "publish",
            mixinStandardHelpOptions = true,
            description =
                    "Validate and update a single bot summary comment (deterministic, separate from reviewer)")
    public static final class Publish implements Callable<Integer> {
        @Option(names = "--repository", required = true)
        String repository;

        @Option(names = "--pr", required = true)
        int pr;

        @Option(names = "--repo", required = true)
        Path repo;

        @Option(names = "--report-dir", required = true)
        Path output;

        @Option(names = "--details-url", defaultValue = "")
        String detailsUrl;

        @Option(names = "--bot-login")
        String botLogin;

        @Option(names = "--bot-id")
        Long botId;

        @Override
        public Integer call() throws Exception {
            Report report =
                    Json.MAPPER.readValue(
                            Json.read(output.resolve("report.json"), 1000000), Report.class);
            var git =
                    new GitRepository(
                            repo,
                            report.baseSha(),
                            report.headSha(),
                            report.details() == null
                                    ? report.baseSha()
                                    : report.details().policyCommit(),
                            report.details() == null
                                    ? report.baseSha()
                                    : report.details().targetHead());
            var policy = new Policy(git);
            EvidenceStore evidence =
                    EvidenceStore.restore(
                            Json.parse(Json.read(output.resolve("evidence.json"), 3000000)));
            if ((botLogin == null) != (botId == null))
                throw new IllegalArgumentException(
                        "Supply both --bot-login and --bot-id from trusted application configuration");
            var client =
                    botLogin == null
                            ? new GitHubClient(repository, System.getenv("GITHUB_TOKEN"))
                            : new GitHubClient(
                                    repository,
                                    System.getenv("GITHUB_TOKEN"),
                                    null,
                                    new GitHubClient.BotIdentity(botLogin, botId));
            Path receipt = output.resolve("publication.json");
            if (java.nio.file.Files.exists(receipt))
                client.recoveryOnly(
                        Json.parse(Json.read(receipt, 32000))
                                .path("reason")
                                .asText()
                                .equals("ambiguous_create"));
            try {
                var publication =
                        client.publish(
                                GitHubClient.ref(repository, pr),
                                report,
                                git,
                                policy,
                                evidence,
                                detailsUrl);
                Json.write(receipt, publication);
                System.out.println(
                        "Publication "
                                + publication.status()
                                + "; comment "
                                + publication.commentId());
                return publication.status().equals("published") ? 0 : 1;
            } catch (java.io.IOException | IllegalArgumentException | InterruptedException e) {
                Json.write(
                        receipt,
                        new dev.undertow.changes.ChangeAdapters.Publication(
                                "failed",
                                0,
                                report.headSha(),
                                report.runId(),
                                java.util.Map.of(),
                                e instanceof GitHubClient.AmbiguousPublication
                                        ? "ambiguous_create"
                                        : e instanceof GitHubClient.ApiFailure api
                                                ? "GitHub HTTP " + api.status()
                                                : "Publication failed: "
                                                        + e.getClass().getSimpleName()));
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    @Command(
            name = "mark-stale",
            mixinStandardHelpOptions = true,
            description = "Invalidate an owned summary after PR commits/state change")
    public static final class MarkStale implements Callable<Integer> {
        @Option(names = "--repository", required = true)
        String repository;

        @Option(names = "--pr", required = true)
        int pr;

        @Override
        public Integer call() throws Exception {
            new GitHubClient(repository, System.getenv("GITHUB_TOKEN")).markOutdated(pr);
            return 0;
        }
    }

    @Command(
            name = "investigate-dependency",
            mixinStandardHelpOptions = true,
            description =
                    "Collect exact dependency inventory, official notes and OSV evidence without a model")
    public static final class Investigate implements Callable<Integer> {
        @Option(names = "--repo", required = true)
        Path repo;

        @Option(names = "--base", required = true)
        String base;

        @Option(names = "--head", required = true)
        String head;

        @Option(names = "--coordinate", required = true)
        String coordinate;

        @Option(names = "--old-version", required = true)
        String oldVersion;

        @Option(names = "--new-version", required = true)
        String newVersion;

        @Option(names = "--output", required = true)
        Path output;

        @Override
        public Integer call() throws Exception {
            var git = new GitRepository(repo, base, head);
            var reviewer = new Reviewer(git, null);
            var results = Json.MAPPER.createObjectNode();
            var failures = results.putArray("failures");
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                for (String tool :
                        List.of(
                                "compare_dependencies",
                                "fetch_migration_evidence",
                                "query_vulnerabilities")) {
                    JsonNode parameters = argumentsFor(tool);
                    var future = executor.submit(() -> reviewer.tools().execute(tool, parameters));
                    try {
                        results.set(tool, future.get(20, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    } catch (ExecutionException | TimeoutException e) {
                        failures.add(
                                tool + ": unavailable or rejected (" + safeFailureDetail(e) + ")");
                    } finally {
                        future.cancel(true);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
            results.put("base_sha", git.base())
                    .put("head_sha", git.head())
                    .put("status", "partial")
                    .put(
                            "coverage",
                            "Inventory and endpoint documentation/advisories only; complete interval/API/runtime compatibility is unverified.");
            Json.write(output, results);
            return failures.isEmpty() ? 0 : 2;
        }

        private JsonNode argumentsFor(String tool) {
            return switch (tool) {
                case "compare_dependencies" -> Json.MAPPER.createObjectNode();
                case "query_vulnerabilities" ->
                        Json.MAPPER
                                .createObjectNode()
                                .put("coordinate", coordinate)
                                .put("version", newVersion);
                case "fetch_migration_evidence" ->
                        Json.MAPPER
                                .createObjectNode()
                                .put("coordinate", coordinate)
                                .put("old_version", oldVersion)
                                .put("new_version", newVersion);
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported dependency investigation tool");
            };
        }

        private static String safeFailureDetail(Exception failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String message = cause.getMessage();
            if (cause instanceof IOException
                    && message != null
                    && message.matches("Evidence (HTTP status [0-9]{3}|exceeds size limit)")) {
                return message;
            }
            return cause.getClass().getSimpleName();
        }
    }
}
