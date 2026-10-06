package dev.undertow.cli;

import dev.undertow.harness.Reviewer;
import dev.undertow.model.GeminiClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class SetupCommands {
    private SetupCommands() {}

    public static final class Inputs {
        @Option(names = "--repo", defaultValue = ".")
        Path repo;

        @Option(names = "--base", defaultValue = "HEAD")
        String base;

        @Option(names = "--head", defaultValue = "HEAD")
        String head;

        GitRepository git() throws java.io.IOException {
            return new GitRepository(repo, base, head);
        }
    }

    @Command(
            name = "doctor",
            mixinStandardHelpOptions = true,
            description =
                    "Check local setup; --network explicitly enables a model preflight without source")
    public static final class Doctor implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Option(names = "--network")
        boolean network;

        @Override
        public Integer call() throws Exception {
            System.out.println(
                    "Runtime: JDK "
                            + Runtime.version()
                            + " (requires JDK 25); analysis: Java 21, single-module Maven, syntax only.");
            var git = inputs.git();
            var reviewer = new Reviewer(git, null);
            Policy policy = reviewer.policy();
            System.out.println("Git snapshots: " + git.base() + " / " + git.head());
            System.out.println(
                    "Trusted policy: base "
                            + git.base()
                            + "; rule schema v"
                            + policy.catalog().version());
            boolean present =
                    System.getenv(policy.keyEnv()) != null
                            && !System.getenv(policy.keyEnv()).isBlank();
            System.out.println(
                    "Model: "
                            + policy.model()
                            + "; credential: "
                            + (present ? "present" : "missing; set " + policy.keyEnv()));
            System.out.println(
                    "Live review sends source/evidence to Google. Artifacts can contain source; restrict access and retention. Findings remain advisory.");
            if (!network) return Runtime.version().feature() >= 25 ? 0 : 1;
            if (!present) return 1;
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try (var model = new ModelClientFactory().create(reviewer, null, "tools")) {
                var task =
                        executor.submit(
                                () ->
                                        model.next(
                                                "Model access preflight. Call finish_review with findings=[], summary=access confirmed, coverage_notes=[preflight only]. No source is provided. Do not call investigation tools.",
                                                List.of(),
                                                true));
                try {
                    task.get(policy.limits().toolTimeoutSeconds(), TimeUnit.SECONDS);
                    System.out.println(
                            "Model network preflight: accessible (does not measure review quality).");
                    return 0;
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    System.out.println(
                            "Model network preflight: "
                                    + (cause instanceof GeminiClient.ModelFailure failure
                                            ? failure.category()
                                            : cause instanceof java.util.concurrent.TimeoutException
                                                    ? "timeout"
                                                    : "unavailable"));
                    return 1;
                } finally {
                    task.cancel(true);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Command(
            name = "rules",
            mixinStandardHelpOptions = true,
            subcommands = {Validate.class, ListRules.class, Explain.class},
            description = "Validate or inspect versioned repository rules")
    public static final class Rules {}

    @Command(name = "validate", mixinStandardHelpOptions = true)
    public static final class Validate implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Option(
                names = "--candidate",
                description =
                        "Validate this proposed policy commit separately; it never changes review authority")
        String candidate;

        @Override
        public Integer call() throws Exception {
            var git = inputs.git();
            new Policy(git);
            System.out.println("Trusted base policy valid: " + git.base());
            if (candidate != null) {
                var proposed = new GitRepository(inputs.repo, candidate, candidate);
                new Policy(proposed);
                System.out.println(
                        "Candidate policy valid: "
                                + proposed.base()
                                + " (current review still uses trusted base policy)");
            }
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true)
    public static final class ListRules implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Override
        public Integer call() throws Exception {
            var policy = new Policy(inputs.git());
            for (var rule : policy.rules())
                System.out.println(
                        rule.path("id").asText()
                                + " v"
                                + rule.path("version").asText()
                                + " "
                                + rule.path("execution").asText()
                                + " "
                                + rule.path("label").asText()
                                + " enabled="
                                + rule.path("enabled").asBoolean());
            return 0;
        }
    }

    @Command(name = "explain", mixinStandardHelpOptions = true)
    public static final class Explain implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Parameters(index = "0", description = "Rule ID")
        String id;

        @Override
        public Integer call() throws Exception {
            System.out.println(
                    Json.MAPPER
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(new Policy(inputs.git()).rule(id)));
            return 0;
        }
    }
}
