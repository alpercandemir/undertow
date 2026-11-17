package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undertow.cli.UndertowApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExploreCommandTest {
    @TempDir Path temp;

    @Test
    void packagedRuntimeIgnoresWorkingDirectoryAndPythonPathModules() throws Exception {
        Path marker = temp.resolve("untrusted-module-loaded");
        String payload =
                "from pathlib import Path\nPath("
                        + "'untrusted-module-loaded'"
                        + ").write_text('loaded')\nraise SystemExit(99)\n";
        Files.writeString(temp.resolve("search.py"), payload);
        Files.writeString(temp.resolve("oracle.py"), payload);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder =
                new ProcessBuilder(
                                javaExecutable,
                                "-cp",
                                System.getProperty("java.class.path"),
                                UndertowApplication.class.getName(),
                                "explore",
                                "--example",
                                "refund-retry",
                                "--java-home",
                                System.getProperty("java.home"),
                                "--runtime",
                                "0",
                                "--output",
                                temp.resolve("output").toString())
                        .directory(temp.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PYTHONPATH", temp.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isEqualTo(2);
        assertThat(output).contains("limits exceed experiment limits");
        assertThat(marker).doesNotExist();
        assertThat(temp.resolve("output")).doesNotExist();
    }
}
