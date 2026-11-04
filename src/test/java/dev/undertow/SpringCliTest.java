package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undertow.cli.UndertowApplication;
import dev.undertow.reporting.Json;
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
    void helpAndUsageErrorsKeepTheirExitCodesWithoutCreatingClients() {
        try (var context = UndertowApplication.start()) {
            var command = context.getBean(CommandLine.class);
            var output = new StringWriter();
            command.setOut(new PrintWriter(output));
            command.setErr(new PrintWriter(output));

            assertThat(command.execute()).isZero();
            assertThat(output.toString()).contains("review", "publish", "investigate-dependency");
            assertThat(command.execute("--help")).isZero();
            assertThat(command.execute("--version")).isZero();
            assertThat(output.toString()).contains("Undertow 0.1.0-alpha.1");
            assertThat(command.execute("review")).isEqualTo(2);
            assertThat(command.execute("--spring.config.location=untrusted")).isEqualTo(2);
        }
    }

    @Test
    void eachInvocationHasFreshOptionsAndCollectNeedsNoCredential() throws Exception {
        var repo = new TestRepository(temp.resolve("repo"));
        Path report = temp.resolve("report");
        try (var context = UndertowApplication.start()) {
            var first = context.getBean(CommandLine.class);
            assertThat(
                            first.execute(
                                    "review",
                                    "--repo",
                                    repo.root.toString(),
                                    "--base",
                                    repo.base,
                                    "--head",
                                    repo.head,
                                    "--mode",
                                    "collect",
                                    "--output",
                                    report.toString()))
                    .isZero();
            assertThat(
                            Json.parse(Files.readString(report.resolve("report.json")))
                                    .path("model_calls")
                                    .asInt())
                    .isZero();

            var second = context.getBean(CommandLine.class);
            second.setErr(new PrintWriter(new StringWriter()));
            assertThat(second).isNotSameAs(first);
            assertThat(second.execute("review")).isEqualTo(2);
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
