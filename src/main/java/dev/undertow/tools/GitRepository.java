package dev.undertow.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads object data only. Never checks out, builds, or executes reviewed code. */
public final class GitRepository {
    private final Path root;
    private final String base;
    private final String head;
    private final String policyCommit;
    private final String targetHead;
    public static final int MAX_BYTES = 256000;

    public GitRepository(Path root, String baseRef, String headRef) throws IOException {
        this(root, baseRef, headRef, baseRef, baseRef);
    }

    public GitRepository(
            Path root, String baseRef, String headRef, String policyRef, String targetRef)
            throws IOException {
        this.root = root.toRealPath();
        this.base = pin(baseRef);
        this.head = pin(headRef);
        this.policyCommit = pin(policyRef);
        this.targetHead = pin(targetRef);
    }

    public String base() {
        return base;
    }

    public String head() {
        return head;
    }

    public String policyCommit() {
        return policyCommit;
    }

    public String targetHead() {
        return targetHead;
    }

    private String pin(String ref) throws IOException {
        if (ref == null || !ref.matches("[a-zA-Z0-9_./~^@{}-]{1,150}") || ref.startsWith("-")) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
        String sha =
                command(
                                List.of(
                                        "rev-parse",
                                        "--verify",
                                        "--end-of-options",
                                        ref + "^{commit}"),
                                256)
                        .trim();
        if (!sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new IOException("Invalid commit SHA");
        }
        return sha;
    }

    public String commit(String snapshot) {
        return switch (snapshot) {
            case "base" -> base;
            case "head" -> head;
            case "policy" -> policyCommit;
            default -> throw new IllegalArgumentException("snapshot must be base or head");
        };
    }

    public static String safePath(String path) {
        if (path == null
                || path.length() > 300
                || path.startsWith("/")
                || path.startsWith("-")
                || path.contains("\\")
                || path.contains(":")
                || path.chars().anyMatch(c -> c < 32)
                || Arrays.asList(path.split("/", -1)).stream()
                        .anyMatch(s -> s.isBlank() || s.equals("..") || s.equals("."))) {
            throw new IllegalArgumentException("Unsafe repository-relative path");
        }
        return path;
    }

    public List<String> files(String snapshot) throws IOException {
        return Arrays.stream(
                        command(
                                        List.of(
                                                "ls-tree",
                                                "-r",
                                                "--name-only",
                                                "-z",
                                                commit(snapshot)),
                                        MAX_BYTES)
                                .split("\u0000"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> changedFiles() throws IOException {
        return Arrays.stream(
                        command(
                                        List.of(
                                                "diff",
                                                "--no-ext-diff",
                                                "--no-textconv",
                                                "--no-renames",
                                                "--name-only",
                                                "-z",
                                                base,
                                                head,
                                                "--"),
                                        MAX_BYTES)
                                .split("\u0000"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String source(String snapshot, String path) throws IOException {
        safePath(path);
        String sha = commit(snapshot);
        String tree = command(List.of("ls-tree", sha, "--", path), 1024);
        if (!tree.startsWith("100644 blob ") && !tree.startsWith("100755 blob ")) {
            throw new IOException("Path missing or not a regular Git blob");
        }
        String object = tree.split("[ \t]", 4)[2];
        String data = command(List.of("cat-file", "blob", object), MAX_BYTES);
        if (data.indexOf('\0') >= 0) {
            throw new IOException("Binary source is unsupported");
        }
        return data;
    }

    public String diff(String path) throws IOException {
        safePath(path);
        return command(
                List.of(
                        "diff",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--no-renames",
                        "--unified=4",
                        base,
                        head,
                        "--",
                        path),
                MAX_BYTES);
    }

    private String command(List<String> args, int limit) throws IOException {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "diff.external=",
                                "-c",
                                "core.attributesFile=/dev/null",
                                "-C",
                                root.toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k -> k.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output =
                    executor.submit(
                            () -> {
                                try (var stream = process.getInputStream()) {
                                    return stream.readNBytes(limit + 1);
                                }
                            });
            try {
                byte[] data = output.get(20, TimeUnit.SECONDS);
                if (data.length > limit) {
                    throw new IOException("Git output exceeds limit");
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    throw new IOException("Git operation timed out");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Git operation failed (" + args.getFirst() + ")");
                }
                return new String(data, StandardCharsets.UTF_8);
            } catch (TimeoutException | ExecutionException e) {
                throw new IOException("Git read failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Git read interrupted", e);
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
