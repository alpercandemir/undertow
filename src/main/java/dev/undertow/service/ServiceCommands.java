package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.github.GitHubClient;
import dev.undertow.harness.EvidenceStore;
import dev.undertow.harness.Report;
import dev.undertow.model.GeminiClient;
import dev.undertow.model.ModelClient;
import dev.undertow.model.ReplayClient;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

public final class ServiceCommands {
    private ServiceCommands() {}

    @Command(
            name = "service",
            mixinStandardHelpOptions = true,
            description =
                    "Start the Phase 3 control API; requires operator-owned App configuration")
    public static final class Serve implements Callable<Integer> {
        @Option(names = "--config", required = true)
        Path config;

        @Option(names = "--data", required = true)
        Path data;

        @Option(names = "--bind", defaultValue = "127.0.0.1")
        String bind;

        @Option(names = "--port", defaultValue = "8080")
        int port;

        @Override
        public Integer call() throws Exception {
            ObjectNode conf = (ObjectNode) Json.parse(Json.read(config, 1000000));
            Files.createDirectories(data);
            data = data.toRealPath();
            String digest = runningDigest();
            if (!digest.equals(conf.path("engineDigest").asText()))
                throw new IllegalArgumentException(
                        "Control JAR must match the configured pinned release");
            var settings =
                    new ServiceControl.Settings(
                            digest,
                            conf.path("executionVersion").asText(),
                            conf.path("tariffVersion").asText(),
                            conf.path("inputCreditRate").asLong(),
                            conf.path("outputCreditRate").asLong(),
                            conf.path("maximumReservation").asLong(),
                            conf.path("globalConcurrency").asInt(2),
                            conf.path("tenantConcurrency").asInt(1),
                            conf.path("liveApproved").asBoolean(false),
                            conf.path("execution"),
                            conf.path("sources"));
            URI publicUrl = URI.create(conf.path("publicUrl").asText());
            var database =
                    new ServiceDatabase(
                            "jdbc:h2:file:"
                                    + data.resolve("control").toString()
                                    + ";DB_CLOSE_ON_EXIT=FALSE",
                            Base64.getDecoder().decode(environment("UNDERTOW_DATABASE_KEY")));
            var github =
                    new GitHubAppGateway(
                            conf.path("appId").asText(),
                            conf.path("clientId").asText(),
                            environment("UNDERTOW_GITHUB_CLIENT_SECRET"),
                            Path.of(conf.path("appKeyFile").asText()),
                            publicUrl);
            var control = new ServiceControl(database, github, settings, Clock.systemUTC());
            List<String> workerCommand = command(conf.path("workerCommand"));
            List<String> publisherCommand = command(conf.path("publisherCommand"));
            String mode = conf.path("executionMode").asText("collect");
            ServiceOrchestrator.Runner runner =
                    workerCommand.isEmpty()
                            ? null
                            : (repo, manifest, output) -> {
                                runProcess(
                                        workerCommand,
                                        List.of(
                                                repo.toString(),
                                                manifest.toString(),
                                                output.toString()),
                                        Map.of(),
                                        360);
                                return (ObjectNode)
                                        Json.parse(
                                                Json.read(
                                                        output.resolve("service-artifact.json"),
                                                        4000000));
                            };
            ServiceOrchestrator.Publisher publisher =
                    publisherCommand.isEmpty()
                            ? null
                            : (lease, repo, artifact, workspace) -> {
                                Path git = workspace.resolve("source.git");
                                github.prepare(
                                        repo.path("installation").asLong(),
                                        repo.path("id").asLong(),
                                        (ObjectNode) lease.path("snapshot"),
                                        git);
                                ObjectNode manifest =
                                        (ObjectNode)
                                                artifact.path("files")
                                                        .path("effective-policy.json");
                                HostedWorker.validate(manifest, git, artifact);
                                Path artifactPath = workspace.resolve("artifact.json");
                                Json.write(artifactPath, artifact);
                                Path manifestPath = workspace.resolve("manifest.json");
                                Json.write(manifestPath, manifest);
                                control.verifyExecutionAccess(lease);
                                String token =
                                        github.installationToken(
                                                repo.path("installation").asLong(),
                                                repo.path("id").asLong(),
                                                true);
                                runProcess(
                                        publisherCommand,
                                        List.of(
                                                git.toString(),
                                                manifestPath.toString(),
                                                artifactPath.toString(),
                                                repo.path("name").asText(),
                                                Integer.toString(lease.path("pr").asInt()),
                                                publicUrl
                                                        .resolve(
                                                                "/?tenant="
                                                                        + lease.path("tenant")
                                                                                .asText()
                                                                        + "&review="
                                                                        + lease.path("id").asText())
                                                        .toString(),
                                                conf.path("botLogin").asText(),
                                                conf.path("botId").asText(),
                                                workspace
                                                        .resolve("publication-output/receipt.json")
                                                        .toString(),
                                                Boolean.toString(
                                                        lease.path("publicationAttempts").asInt()
                                                                > 1),
                                                publicUrl
                                                        .resolve("/internal/publication/check")
                                                        .toString(),
                                                lease.path("tenant").asText(),
                                                lease.path("id").asText(),
                                                lease.path("fence").asText()),
                                        Map.of(
                                                "GITHUB_TOKEN",
                                                token,
                                                "UNDERTOW_PUBLICATION_PERMIT",
                                                lease.path("publicationPermit").asText()),
                                        90);
                                return (ObjectNode)
                                        Json.parse(
                                                Json.read(
                                                        workspace.resolve(
                                                                "publication-output/receipt.json"),
                                                        1000000));
                            };
            var orchestrator =
                    new ServiceOrchestrator(
                            control, data.resolve("workspaces"), runner, publisher, mode);
            try (var http =
                    new ServiceHttpServer(
                            new InetSocketAddress(bind, port),
                            publicUrl,
                            control,
                            github,
                            environment("UNDERTOW_WEBHOOK_SECRET"),
                            environment("UNDERTOW_OPERATOR_TOKEN"))) {
                http.start();
                System.out.println(
                        "Undertow internal control API listening on " + bind + ":" + http.port());
                var schedulers = Executors.newScheduledThreadPool(settings.globalConcurrency());
                try {
                    for (int i = 0; i < settings.globalConcurrency(); i++) {
                        schedulers.scheduleWithFixedDelay(
                                () -> {
                                    try {
                                        orchestrator.tick();
                                    } catch (Exception e) {
                                        System.err.println(
                                                "Service scheduler could not complete this cycle; durable work retained");
                                    }
                                },
                                0,
                                2,
                                TimeUnit.SECONDS);
                    }
                    new java.util.concurrent.CountDownLatch(1).await();
                } finally {
                    schedulers.shutdownNow();
                }
            }
            return 0;
        }
    }

    @Command(
            name = "service-worker",
            mixinStandardHelpOptions = true,
            description = "Review prepared immutable snapshots in a credential-limited process")
    public static final class Worker implements Callable<Integer> {
        @Option(names = "--repo", required = true)
        Path repository;

        @Option(names = "--manifest", required = true)
        Path manifest;

        @Option(names = "--output", required = true)
        Path output;

        @Option(names = "--replay")
        Path replay;

        @Option(names = "--mode", defaultValue = "collect")
        String mode;

        @Override
        public Integer call() throws Exception {
            ObjectNode input = (ObjectNode) Json.parse(Json.read(manifest, 1500000));
            if (!input.path("engineDigest").asText().equals(runningDigest()))
                throw new IllegalArgumentException("Worker release digest mismatch");
            var reviewer = HostedWorker.reviewer(input, repository);
            ModelClient model;
            if (mode.equals("live"))
                model =
                        new GeminiClient(
                                reviewer.policy().model(),
                                environment("GEMINI_API_KEY"),
                                reviewer.policy().limits().runTimeoutSeconds() * 1000,
                                Schemas.resource("tools"));
            else if (mode.equals("replay") && replay != null) model = new ReplayClient(replay);
            else if (mode.equals("collect")) model = new ReplayClient(List.of());
            else
                throw new IllegalArgumentException(
                        "Choose collect, live, or replay with an operator fixture");
            try (model) {
                HostedWorker.run(input, repository, output, model, mode);
            }
            return 0;
        }
    }

    @Command(
            name = "service-publisher",
            mixinStandardHelpOptions = true,
            description =
                    "Validate retained artifacts and publish with a repository-scoped App credential")
    public static final class Publish implements Callable<Integer> {
        @Option(names = "--repo", required = true)
        Path repository;

        @Option(names = "--manifest", required = true)
        Path manifest;

        @Option(names = "--artifact", required = true)
        Path artifact;

        @Option(names = "--repository", required = true)
        String name;

        @Option(names = "--pr", required = true)
        int pr;

        @Option(names = "--details-url", required = true)
        String detailsUrl;

        @Option(names = "--bot-login", required = true)
        String botLogin;

        @Option(names = "--bot-id", required = true)
        long botId;

        @Option(names = "--receipt", required = true)
        Path receipt;

        @Option(names = "--recovery", defaultValue = "false")
        boolean recovery;

        @Option(names = "--permit-url", required = true)
        URI permitUrl;

        @Option(names = "--tenant", required = true)
        String tenant;

        @Option(names = "--review", required = true)
        String review;

        @Option(names = "--fence", required = true)
        long fence;

        @Override
        public Integer call() throws Exception {
            if (botLogin.equals("github-actions[bot]"))
                throw new IllegalArgumentException(
                        "Hosted publication requires the Undertow App identity");
            ObjectNode input = (ObjectNode) Json.parse(Json.read(manifest, 1500000)),
                    bundle = (ObjectNode) Json.parse(Json.read(artifact, 4000000));
            if (!input.path("engineDigest").asText().equals(runningDigest()))
                throw new IllegalArgumentException("Publisher release digest mismatch");
            HostedWorker.validate(input, repository, bundle);
            var snapshot = input.path("snapshot");
            if (!snapshot.path("name").asText().equals(name) || snapshot.path("pr").asInt() != pr)
                throw new IllegalArgumentException("Publisher PR binding mismatch");
            var git =
                    new GitRepository(
                            repository,
                            snapshot.path("diffBase").asText(),
                            snapshot.path("head").asText(),
                            snapshot.path("policyCommit").asText(),
                            snapshot.path("target").asText());
            var policy =
                    ServicePolicy.resolve(
                            ServicePolicy.files(input.path("files")),
                            input.path("execution"),
                            input.path("sources"));
            var client =
                    new GitHubClient(
                                    name,
                                    environment("GITHUB_TOKEN"),
                                    null,
                                    new GitHubClient.BotIdentity(botLogin, botId))
                            .recoveryOnly(recovery);
            if (!permitUrl.getScheme().equals("https")
                    || permitUrl.getUserInfo() != null
                    || !permitUrl.getPath().equals("/internal/publication/check"))
                throw new IllegalArgumentException("Invalid publisher permit endpoint");
            String permit = environment("UNDERTOW_PUBLICATION_PERMIT");
            var http =
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(5))
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .build();
            client.guardWrites(
                    () -> {
                        ObjectNode claims =
                                Json.MAPPER
                                        .createObjectNode()
                                        .put("tenant", tenant)
                                        .put("review", review)
                                        .put("fence", fence);
                        var request =
                                java.net.http.HttpRequest.newBuilder(permitUrl)
                                        .timeout(java.time.Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + permit)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                                        Json.stringify(claims)))
                                        .build();
                        var response =
                                http.send(
                                        request,
                                        java.net.http.HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() != 200)
                            throw new IOException("Publisher permit revoked or unavailable");
                    });
            var result =
                    client.publish(
                            GitHubClient.ref(name, pr),
                            Json.MAPPER.treeToValue(
                                    bundle.path("files").path("report.json"), Report.class),
                            git,
                            policy,
                            EvidenceStore.restore(bundle.path("files").path("evidence.json")),
                            detailsUrl);
            Json.write(receipt, result);
            return 0;
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing required credential " + name);
        return value;
    }

    public static String runningDigest() throws Exception {
        java.io.File source =
                new org.springframework.boot.system.ApplicationHome(ServiceCommands.class)
                        .getSource();
        if (source == null) throw new IllegalArgumentException("Cannot identify running release");
        Path jar = source.toPath();
        if (!Files.isRegularFile(jar))
            throw new IllegalArgumentException(
                    "Hosted commands require the packaged immutable Undertow JAR");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> command(JsonNode node) {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > 20)
            throw new IllegalArgumentException("Invalid trusted runner command");
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank())
                throw new IllegalArgumentException("Invalid runner argument");
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static void runProcess(
            List<String> command,
            List<String> arguments,
            Map<String, String> credentials,
            int timeout)
            throws Exception {
        List<String> full = new ArrayList<>(command);
        full.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(full).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        builder.environment().putAll(credentials);
        Process process = builder.start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var read = executor.submit(() -> process.getInputStream().readNBytes(1000001));
            byte[] bytes = read.get(timeout, TimeUnit.SECONDS);
            if (bytes.length > 1000000
                    || !process.waitFor(timeout, TimeUnit.SECONDS)
                    || process.exitValue() != 0)
                throw new IOException("Isolated service process failed");
        } finally {
            process.destroyForcibly();
            executor.shutdownNow();
        }
    }
}
