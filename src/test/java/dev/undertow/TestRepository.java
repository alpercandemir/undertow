package dev.undertow;

import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TestRepository {
    final Path root;
    final String base;
    String head;

    TestRepository(Path directory) throws Exception {
        root = directory;
        Files.createDirectories(root);
        for (String path :
                List.of(
                        "config/review.yaml",
                        "config/business-context.yaml",
                        "config/dependency-sources.yaml",
                        "config/languages/java/rules.yaml",
                        "CODING-SKILL.md")) {
            Path to = root.resolve(path);
            Files.createDirectories(to.getParent());
            Files.copy(Path.of(path), to);
        }
        write("src/Pay.java", "class Pay { int amount() { return 1; } }\n");
        command("init", "-q");
        command("add", ".");
        command("commit", "-qm", "base");
        base = command("rev-parse", "HEAD");
        write("src/Pay.java", "class Pay { int amount() { return 2; } }\n");
        commit();
    }

    void write(String path, String source) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    void commit() throws Exception {
        command("add", ".");
        command("commit", "-qm", "head");
        head = command("rev-parse", "HEAD");
    }

    String command(String... args) throws Exception {
        List<String> cmd =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=Test",
                                "-c",
                                "user.email=test@undertow.invalid",
                                "-C",
                                root.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output =
                new String(
                        p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException("Git fixture failed: " + output);
        }
        return output.trim();
    }

    GitRepository git() throws IOException {
        return new GitRepository(root, base, head);
    }
}
