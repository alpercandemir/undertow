package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undertow.cli.UndertowApplication;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SpringCliTest {
    @TempDir Path temp;

    @Test
    void helpExposesOnlyActiveProductCommands() {
        try (var context = UndertowApplication.start()) {
            var command = context.getBean(CommandLine.class);
            var output = new StringWriter();
            command.setOut(new PrintWriter(output));
            command.setErr(new PrintWriter(output));

            assertThat(command.execute()).isZero();
            assertThat(output.toString()).contains("explore", "contracts", "verify");
            assertThat(command.getSubcommands().keySet())
                    .containsExactlyInAnyOrder("explore", "contracts", "verify");
            assertThat(command.getSubcommands().get("contracts").getSubcommands().keySet())
                    .containsExactlyInAnyOrder(
                            "validate",
                            "list",
                            "export",
                            "init",
                            "preview",
                            "freshness",
                            "mcp",
                            "regression-plan",
                            "reproduce");
            assertThat(command.execute("--help")).isZero();
            assertThat(command.execute("--version")).isZero();
            assertThat(command.execute("explore", "--help")).isZero();
            assertThat(output.toString()).contains("Undertow 0.1.0-alpha.1");
            assertThat(command.execute("review")).isEqualTo(2);
            assertThat(command.execute("--spring.config.location=untrusted")).isEqualTo(2);
        }
    }

    @Test
    void eachInvocationHasFreshOptionsAndContractsNeedNoCredential() throws Exception {
        var helper = new ContractVerificationTest();
        helper.temp = temp;
        var fixture = helper.fixture();
        try (var context = UndertowApplication.start()) {
            var first = context.getBean(CommandLine.class);
            first.setOut(new PrintWriter(new StringWriter()));
            assertThat(
                            first.execute(
                                    "contracts",
                                    "list",
                                    "--repo",
                                    fixture.repo().root.toString(),
                                    "--base",
                                    fixture.git().policyCommit(),
                                    "--head",
                                    fixture.git().head()))
                    .isZero();
            var second = context.getBean(CommandLine.class);
            second.setErr(new PrintWriter(new StringWriter()));
            assertThat(second).isNotSameAs(first);
            assertThat(second.execute("contracts", "list")).isEqualTo(2);
            for (String retired :
                    java.util.List.of(
                            "review", "publish", "serve", "worker", "investigate-dependency")) {
                assertThat(second.execute(retired)).isEqualTo(2);
            }
            for (String retired : java.util.List.of("release", "attest", "rollout", "pilot")) {
                assertThat(second.execute("contracts", retired)).isEqualTo(2);
            }
        }
    }

    @Test
    void reviewedWorkingDirectoryCannotSupplySpringConfiguration() throws Exception {
        Files.writeString(
                temp.resolve("application.properties"),
                "spring.config.import=file:/undertow-untrusted-missing.properties\n");
        Files.createDirectories(temp.resolve("config"));
        Files.writeString(
                temp.resolve("config/application.properties"),
                "spring.config.import=file:/undertow-untrusted-missing.properties\n");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process =
                new ProcessBuilder(
                                javaExecutable,
                                "-cp",
                                System.getProperty("java.class.path"),
                                UndertowApplication.class.getName(),
                                "--help")
                        .directory(temp.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("Usage: undertow").doesNotContain("APPLICATION FAILED");
    }
}
