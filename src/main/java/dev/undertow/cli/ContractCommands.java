package dev.undertow.cli;

import dev.undertow.contracts.ContractPolicy;
import dev.undertow.contracts.ContractVerifier;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public final class ContractCommands {
    private ContractCommands() {}

    public static final class Inputs {
        @Option(names = "--repo", defaultValue = ".")
        Path repo;

        @Option(names = "--base", required = true, description = "Trusted comparison baseline")
        String base;

        @Option(names = "--head", defaultValue = "HEAD")
        String head;

        @Option(
                names = "--policy-commit",
                description = "Trusted owner-approved policy; defaults to base")
        String policy;

        @Option(names = "--target-head", description = "Pinned target tip; defaults to base")
        String target;

        @Option(names = "--manifest", defaultValue = ".undertow/contracts.yaml")
        String manifest;

        GitRepository git() throws java.io.IOException {
            return new GitRepository(
                    repo,
                    base,
                    head,
                    policy == null ? base : policy,
                    target == null ? base : target);
        }
    }

    @Command(
            name = "contracts",
            mixinStandardHelpOptions = true,
            subcommands = {
                Validate.class,
                ListContracts.class,
                Export.class,
                AssuranceCommands.Init.class,
                AssuranceCommands.Preview.class,
                AssuranceCommands.Freshness.class,
                AssuranceCommands.Mcp.class,
                AssuranceCommands.RegressionPlan.class,
                AssuranceCommands.Reproduce.class,
                AssuranceCommands.Pilot.class,
                AssuranceCommands.Release.class,
                AssuranceCommands.Attest.class,
                AssuranceCommands.Rollout.class
            },
            description = "Read and validate inert owner-approved contracts without a model")
    public static final class Contracts {}

    @Command(name = "validate", mixinStandardHelpOptions = true)
    public static final class Validate implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Option(
                names = "--candidate",
                description = "Validate proposed policy separately; never grants authority")
        String candidate;

        public Integer call() throws Exception {
            var git = inputs.git();
            var policy = new ContractPolicy(git, inputs.manifest);
            System.out.println(
                    "Trusted contract policy valid: " + Json.stringify(policy.binding()));
            if (candidate != null) {
                var proposed = new GitRepository(inputs.repo, candidate, candidate);
                new ContractPolicy(proposed, inputs.manifest);
                System.out.println(
                        "Candidate valid: "
                                + proposed.head()
                                + "; current authority remains "
                                + git.policyCommit());
            }
            return 0;
        }
    }

    @Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "Read contracts as structured JSON before coding")
    public static final class ListContracts implements Callable<Integer> {
        @Mixin Inputs inputs;

        public Integer call() throws Exception {
            var policy = new ContractPolicy(inputs.git(), inputs.manifest);
            var result = Json.MAPPER.createObjectNode();
            result.set("policy", policy.binding());
            result.set("manifest", policy.manifest());
            System.out.println(
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return 0;
        }
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            description = "Export approved oracle identity for the trusted CI importer")
    public static final class Export implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Option(names = "--output", required = true)
        Path output;

        public Integer call() throws Exception {
            Json.write(output, new ContractPolicy(inputs.git(), inputs.manifest).binding());
            return 0;
        }
    }

    @Command(
            name = "verify",
            mixinStandardHelpOptions = true,
            description =
                    "Verify exact contract bindings using existing CI evidence; shadow mode, no model or execution")
    public static final class Verify implements Callable<Integer> {
        @Mixin Inputs inputs;

        @Option(names = "--ci-bundle")
        Path bundle;

        @Option(
                names = "--ci-receipt",
                description = "Trusted importer receipt; keep outside candidate control")
        Path receipt;

        @Option(names = "--output", defaultValue = ".undertow/verification")
        Path output;

        @Option(names = "--sarif", description = "Write an optional SARIF 2.1.0 adapter result")
        Path sarif;

        public Integer call() throws Exception {
            var git = inputs.git();
            var policy = new ContractPolicy(git, inputs.manifest);
            var result = ContractVerifier.verify(git, policy, bundle, receipt);
            ContractVerifier.write(output, result);
            if (sarif != null)
                Json.write(sarif, dev.undertow.contracts.ContractSarif.render(result, policy));
            System.out.println(
                    "Contract verification: "
                            + result.path("verification").asText()
                            + "; shadow mode; "
                            + output.resolve("contracts.json"));
            return 0;
        }
    }
}
