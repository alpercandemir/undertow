package dev.undertow.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Explicit host execution command, separate from the read-only MCP registry. */
@Command(
        name = "explore",
        mixinStandardHelpOptions = true,
        description = "Find and replay bounded behavior failures in an owned Java component")
public final class ExploreCommand implements Callable<Integer> {
    private static final long WRAPPER_TIMEOUT_SECONDS = 900;
    private static final Set<String> ENVIRONMENT_KEYS =
            Set.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "SYSTEMROOT");
    private static final List<String> RESOURCES =
            List.of(
                    "explore.py",
                    "search.py",
                    "oracle.py",
                    "scenario.json",
                    "sequence_strategy.py",
                    "retry_behavior.py",
                    "retry-scenario.json",
                    "ticket_behavior.py",
                    "ticket-scenario.json",
                    "java/ExecutionAdapter.java",
                    "java/ScenarioProtocol.java",
                    "java/RefundAdapter.java",
                    "java/ScenarioRunner.java",
                    "java/TicketAdapter.java",
                    "java/TicketScenarioRunner.java",
                    "fixtures/refund-extraction/pom.xml",
                    "fixtures/refund-extraction/src/main/java/example/refunds/RefundService.java",
                    "fixtures/ticket-transfer/pom.xml",
                    "fixtures/ticket-transfer/src/main/java/example/tickets/TicketTransfer.java");

    @Option(
            names = "--subject",
            description = "Owned Maven component matching the reviewed adapter")
    Path subject;

    @Option(names = "--behavior", description = "refund-cap, refund-retry, or ticket-transfer")
    String behavior;

    @Option(names = "--example", description = "Intentionally defective bundled example profile")
    String example;

    @Option(names = "--replay", description = "Replay JSON from a previous explore run")
    Path replay;

    @Option(
            names = "--adopt",
            description = "Copy a reviewed run's draft into --subject; never overwrite")
    Path adopt;

    @Option(names = "--java-home", description = "JDK 21+ for the selected component")
    Path javaHome;

    @Option(names = "--output", required = true, description = "New local artifact directory")
    Path output;

    @Option(names = "--seed", defaultValue = "17")
    long seed;

    @Option(names = "--scenarios", defaultValue = "100")
    int scenarios;

    @Option(names = "--max-length", defaultValue = "12")
    int maxLength;

    @Option(names = "--runtime", defaultValue = "30")
    double runtime;

    @Override
    public Integer call() {
        Path directory = null;
        int result = 2;
        try {
            directory = Files.createTempDirectory("undertow-explore-runtime-");
            extract(directory);
            result = execute(directory);
        } catch (IOException failure) {
            System.err.println(
                    "Explore could not start or access its local runtime: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Explore execution was interrupted.");
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                } catch (IOException failure) {
                    System.err.println(
                            "Explore temporary-runtime cleanup failed: " + failure.getMessage());
                    result = 2;
                }
            }
        }
        return result;
    }

    private static void extract(Path directory) throws IOException {
        for (String name : RESOURCES) {
            Path target = directory.resolve(name);
            Files.createDirectories(target.getParent());
            try (var input = ExploreCommand.class.getResourceAsStream("/behavior-search/" + name)) {
                if (input == null) {
                    throw new IOException("Packaged explore runtime is incomplete: " + name);
                }
                Files.copy(input, target);
            }
        }
    }

    private int execute(Path directory) throws IOException, InterruptedException {
        var arguments = new ArrayList<String>();
        arguments.add("python3");
        arguments.add("-I");
        arguments.add(directory.resolve("explore.py").toString());
        add(arguments, "--subject", subject);
        add(arguments, "--behavior", behavior);
        add(arguments, "--example", example);
        add(arguments, "--replay", replay);
        add(arguments, "--adopt", adopt);
        add(arguments, "--java-home", javaHome);
        add(arguments, "--output", output);
        add(arguments, "--seed", seed);
        add(arguments, "--scenarios", scenarios);
        add(arguments, "--max-length", maxLength);
        add(arguments, "--runtime", runtime);
        var builder = new ProcessBuilder(arguments).inheritIO();
        builder.environment().keySet().retainAll(ENVIRONMENT_KEYS);
        Process process = builder.start();
        try {
            if (!process.waitFor(WRAPPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("Explore execution exceeded its host deadline.");
                return 2;
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (process.isAlive()) {
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException restrictedHost) {
                    // Some hosts restrict process enumeration. This path is already an
                    // interrupted/timed-out execution; terminate the parent and retain exit 2.
                    System.err.println(
                            "Explore descendant cleanup unavailable: "
                                    + restrictedHost.getMessage());
                }
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static void add(List<String> arguments, String name, Object value) {
        if (value != null) {
            arguments.add(name);
            arguments.add(value.toString());
        }
    }
}
