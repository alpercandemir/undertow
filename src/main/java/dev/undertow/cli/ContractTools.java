package dev.undertow.cli;

import dev.undertow.contracts.ContractMcp;
import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.LocalPreview;
import dev.undertow.contracts.RegressionEvidence;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public final class ContractTools {
    private ContractTools() {}

    @Command(
            name = "init",
            mixinStandardHelpOptions = true,
            description = "Create a manifest from explicit owner intent; never overwrite policy")
    public static final class Init implements Callable<Integer> {
        @Option(names = "--repo", defaultValue = ".")
        Path repo;

        @Option(names = "--repository", required = true)
        String repository;

        @Option(names = "--owner", required = true)
        String owner;

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--expectation", required = true)
        String expectation;

        @Option(names = "--scope", required = true)
        List<String> scope;

        @Option(names = "--test-id", required = true)
        String test;

        @Option(names = "--test-source", required = true)
        List<String> sources;

        @Option(names = "--oracle", required = true)
        List<String> oracles;

        public Integer call() throws Exception {
            Path root = repo.toRealPath(), path = root.resolve(".undertow/contracts.yaml");
            LocalPreview.regularAncestors(root, path);
            String starter;
            try (var stream = Init.class.getResourceAsStream("/contracts/starter.json")) {
                if (stream == null) throw new java.io.IOException("Missing bundled starter");
                starter =
                        new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var manifest =
                    Json.MAPPER
                            .createObjectNode()
                            .put("schema_version", 1)
                            .put("repository", repository);
            manifest.set("oracle_paths", Json.MAPPER.valueToTree(oracles));
            var contract =
                    manifest.putArray("contracts")
                            .addObject()
                            .put("id", id)
                            .put("version", "1")
                            .put("owner", owner)
                            .put("expectation", expectation)
                            .put("verifier", "junit");
            contract.set("scope", Json.MAPPER.valueToTree(scope));
            contract.putArray("tests")
                    .addObject()
                    .put("id", test)
                    .set("sources", Json.MAPPER.valueToTree(sources));
            for (String file :
                    java.util.stream.Stream.of(scope, sources, oracles)
                            .flatMap(List::stream)
                            .toList()) GitRepository.safePath(file);
            Schemas.validate(Schemas.resource("contracts"), manifest);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path, Json.YAML.writeValueAsString(manifest), StandardOpenOption.CREATE_NEW);
            System.out.println(
                    "Created .undertow/contracts.yaml; review and commit to trusted policy. Starter v1 SHA-256 "
                            + Json.hash(starter));
            return 0;
        }
    }

    @Command(
            name = "preview",
            mixinStandardHelpOptions = true,
            description =
                    "Capture immutable tracked/staged content identity for agent-side freshness checks")
    public static final class Preview implements Callable<Integer> {
        @Mixin ContractCommands.Inputs inputs;

        @Option(names = "--staged")
        boolean staged;

        @Option(names = "--include-untracked")
        List<String> untracked = List.of();

        @Option(
                names = "--path",
                description = "Explicit tracked file scope; omission selects the entire index")
        List<String> paths = List.of();

        @Option(names = "--output", required = true)
        Path output;

        public Integer call() throws Exception {
            var git = inputs.git();
            var policy = new ContractPolicy(git, inputs.manifest);
            var capture = LocalPreview.capture(git, policy, staged, untracked, paths);
            if (!LocalPreview.fresh(git, policy, capture))
                throw new java.io.IOException("Local candidate changed during capture; retry");
            Json.write(output, capture);
            System.out.println(
                    "Advisory candidate "
                            + capture.path("candidate_sha256").asText()
                            + "; verification incomplete");
            return 0;
        }
    }

    @Command(
            name = "freshness",
            mixinStandardHelpOptions = true,
            description = "Exit 3 when captured local content, scope, HEAD, or policy changed")
    public static final class Freshness implements Callable<Integer> {
        @Mixin ContractCommands.Inputs inputs;

        @Option(names = "--preview", required = true)
        Path preview;

        public Integer call() throws Exception {
            var git = inputs.git();
            boolean fresh =
                    LocalPreview.fresh(
                            git,
                            new ContractPolicy(git, inputs.manifest),
                            Json.parse(Json.read(preview, 1000000)));
            System.out.println(fresh ? "fresh" : "stale; recapture before using local feedback");
            return fresh ? 0 : 3;
        }
    }

    public static final class Checks {
        @Mixin ContractCommands.Inputs inputs;

        @Option(names = "--ci-bundle")
        Path bundle;

        @Option(names = "--ci-receipt")
        Path receipt;
    }

    @Command(
            name = "mcp",
            mixinStandardHelpOptions = true,
            description = "Read-only stdio MCP server with host-pinned policy and evidence")
    public static final class Mcp implements Callable<Integer> {
        @Mixin Checks checks;

        public Integer call() throws Exception {
            var git = checks.inputs.git();
            new ContractMcp(
                            git,
                            new ContractPolicy(git, checks.inputs.manifest),
                            checks.bundle,
                            checks.receipt)
                    .serve(System.in, System.out);
            return 0;
        }
    }

    @Command(
            name = "regression-plan",
            mixinStandardHelpOptions = true,
            description =
                    "Prepare an approved test-artifact plan for the separate isolated CI runner")
    public static final class RegressionPlan implements Callable<Integer> {
        @Mixin ContractCommands.Inputs inputs;

        @Option(names = "--oracle-artifact", required = true)
        String artifact;

        @Option(names = "--image", required = true)
        String image;

        @Option(names = "--project-prefix", defaultValue = "")
        String prefix;

        @Option(names = "--output", required = true)
        Path output;

        public Integer call() throws Exception {
            var git = inputs.git();
            Json.write(
                    output,
                    RegressionEvidence.plan(
                            git,
                            new ContractPolicy(git, inputs.manifest),
                            artifact,
                            image,
                            prefix));
            return 0;
        }
    }

    @Command(
            name = "reproduce",
            mixinStandardHelpOptions = true,
            description =
                    "Compare repeated independent trusted executions; errors cannot reproduce defects")
    public static final class Reproduce implements Callable<Integer> {
        @Mixin ContractCommands.Inputs inputs;

        @Option(names = "--runs", required = true)
        Path runs;

        @Option(names = "--contract", required = true)
        String contract;

        @Option(names = "--test-id", required = true)
        String test;

        @Option(names = "--output", required = true)
        Path output;

        public Integer call() throws Exception {
            var git = inputs.git();
            Json.write(
                    output,
                    RegressionEvidence.compare(
                            git,
                            new ContractPolicy(git, inputs.manifest),
                            Json.parse(Json.read(runs, 1000000)),
                            runs.toAbsolutePath().getParent(),
                            contract,
                            test));
            return 0;
        }
    }
}
