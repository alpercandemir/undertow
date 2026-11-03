package dev.undertow.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.contracts.AssurancePolicy;
import dev.undertow.contracts.ContractMcp;
import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.ContractVerifier;
import dev.undertow.contracts.LocalPreview;
import dev.undertow.contracts.PilotStudy;
import dev.undertow.contracts.RegressionEvidence;
import dev.undertow.contracts.ReleaseAssurance;
import dev.undertow.contracts.RolloutAssurance;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public final class AssuranceCommands {
    private AssuranceCommands() {}

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
                    "Capture immutable tracked/staged content identity without release authority")
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
                            + "; verification incomplete; release not evaluated");
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

        @Option(names = "--output", defaultValue = ".undertow/assurance")
        Path output;

        JsonNode verify(GitRepository git, ContractPolicy policy) throws java.io.IOException {
            var result = ContractVerifier.verify(git, policy, bundle, receipt);
            ContractVerifier.write(output, result);
            return result;
        }
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

    @Command(
            name = "pilot",
            mixinStandardHelpOptions = true,
            description =
                    "Measure independent human-labeled shadow observations; never enable a gate")
    public static final class Pilot implements Callable<Integer> {
        @Option(names = "--study", required = true)
        Path study;

        @Option(names = "--output", required = true)
        Path output;

        public Integer call() throws Exception {
            Json.write(output, PilotStudy.measure(Json.parse(Json.read(study, 4000000))));
            return 0;
        }
    }

    public static final class ReleaseInputs {
        @Mixin Checks checks;

        @Option(names = "--release-policy", defaultValue = ".undertow/release.yaml")
        String releasePolicy;

        JsonNode decide(GitRepository git, ContractPolicy policy) throws java.io.IOException {
            var verification = checks.verify(git, policy);
            var decision =
                    ReleaseAssurance.decide(
                            verification,
                            AssurancePolicy.load(git, releasePolicy, "release-policy"),
                            Instant.now());
            Json.write(checks.output.resolve("release.json"), decision);
            return decision;
        }
    }

    @Command(
            name = "release",
            mixinStandardHelpOptions = true,
            description =
                    "Recompute verification and apply explicitly enabled owner policy; held exits 3")
    public static final class Release implements Callable<Integer> {
        @Mixin ReleaseInputs inputs;

        public Integer call() throws Exception {
            var git = inputs.checks.inputs.git();
            var decision =
                    inputs.decide(git, new ContractPolicy(git, inputs.checks.inputs.manifest));
            System.out.println("Release: " + decision.path("decision").asText());
            return decision.path("decision").asText().equals("held") ? 3 : 0;
        }
    }

    public static final class ArtifactInputs {
        @Mixin ReleaseInputs inputs;

        @Option(names = "--artifact", required = true)
        Path artifact;

        GitRepository git() throws java.io.IOException {
            return inputs.checks.inputs.git();
        }

        Path output() {
            return inputs.checks.output;
        }

        JsonNode attest(GitRepository git) throws java.io.IOException {
            var decision =
                    inputs.decide(git, new ContractPolicy(git, inputs.checks.inputs.manifest));
            var record =
                    ReleaseAssurance.attest(
                            git, decision, artifact, inputs.checks.bundle, inputs.checks.receipt);
            Json.write(inputs.checks.output.resolve("attestation.json"), record);
            return record;
        }
    }

    @Command(
            name = "attest",
            mixinStandardHelpOptions = true,
            description = "Bind the eligible merge candidate to its CI-tested artifact digest")
    public static final class Attest implements Callable<Integer> {
        @Mixin ArtifactInputs inputs;

        public Integer call() throws Exception {
            var record = inputs.attest(inputs.git());
            System.out.println("Attestation: " + record.path("state").asText());
            return record.path("state").asText().equals("issued") ? 0 : 3;
        }
    }

    @Command(
            name = "rollout",
            mixinStandardHelpOptions = true,
            description =
                    "Check trusted artifact-bound monitoring, containment, and reconciliation exports")
    public static final class Rollout implements Callable<Integer> {
        @Mixin ArtifactInputs inputs;

        @Option(names = "--operations-policy", defaultValue = ".undertow/operations.yaml")
        String operations;

        @Option(names = "--observations", required = true)
        Path observations;

        @Option(names = "--monitor-receipt", required = true)
        Path receipt;

        public Integer call() throws Exception {
            var git = inputs.git();
            var result =
                    RolloutAssurance.evaluate(
                            inputs.attest(git),
                            AssurancePolicy.load(git, operations, "operations-policy"),
                            Json.read(observations, 1000000),
                            Json.parse(Json.read(receipt, 16000)),
                            Instant.now());
            Json.write(inputs.output().resolve("rollout.json"), result);
            System.out.println("Rollout: " + result.path("decision").asText());
            return List.of("accepted", "recovered").contains(result.path("decision").asText())
                    ? 0
                    : 3;
        }
    }
}
