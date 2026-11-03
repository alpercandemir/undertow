package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Content identities only. Local state never acquires CI or release authority. */
public final class LocalPreview {
    private static final int MAX_FILES = 1000;
    private static final int MAX_TOTAL = 4_000_000;

    private LocalPreview() {}

    public static ObjectNode capture(
            GitRepository git, ContractPolicy policy, boolean staged, List<String> untracked)
            throws IOException {
        return capture(git, policy, staged, untracked, List.of());
    }

    public static ObjectNode capture(
            GitRepository git,
            ContractPolicy policy,
            boolean staged,
            List<String> untracked,
            List<String> selected)
            throws IOException {
        if (staged && !untracked.isEmpty())
            throw new IllegalArgumentException("Staged previews cannot include untracked files");
        var entries = new TreeMap<String, String>();
        for (String path : selected) safe(path);
        for (String entry : git.indexEntries()) {
            String[] parts = entry.split("\\t", 2);
            if (!selected.isEmpty() && !selected.contains(parts[1])) continue;
            String path = safe(parts[1]);
            String[] identity = parts[0].split(" ");
            if (!Set.of("100644", "100755").contains(identity[0]) || !identity[2].equals("0")) {
                throw new IllegalArgumentException(
                        "Preview rejects symlinks, submodules, and unmerged paths");
            }
            entries.put(path, identity[0]);
        }
        if (!entries.keySet().containsAll(selected))
            throw new IllegalArgumentException("Selected tracked paths must exist in the index");
        for (String path : untracked) {
            if (entries.putIfAbsent(safe(path), "untracked") != null) {
                throw new IllegalArgumentException("Explicit untracked path is already tracked");
            }
        }
        if (entries.size() > MAX_FILES)
            throw new IllegalArgumentException("Preview file count exceeds limit");
        var files = Json.MAPPER.createObjectNode();
        int total = 0;
        for (var entry : entries.entrySet()) {
            String path = entry.getKey();
            var row = files.putObject(path).put("index_mode", entry.getValue());
            String text;
            if (staged) {
                text = git.indexSource(path);
            } else {
                Path file = git.root().resolve(path);
                regularAncestors(git.root(), file);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (entry.getValue().equals("untracked"))
                        throw new IOException("Explicit untracked file is missing");
                    row.put("state", "deleted");
                    continue;
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Preview path must be a regular file");
                try (var stream = Files.newInputStream(file)) {
                    byte[] content = stream.readNBytes(GitRepository.MAX_BYTES + 1);
                    if (content.length > GitRepository.MAX_BYTES)
                        throw new IOException("Preview file exceeds limit");
                    text =
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .decode(java.nio.ByteBuffer.wrap(content))
                                    .toString();
                }
                row.put("executable", Files.isExecutable(file));
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            total += bytes.length;
            if (text.indexOf('\0') >= 0 || total > MAX_TOTAL)
                throw new IOException("Binary or excessive preview content");
            row.put("state", "captured").put("sha256", Json.hash(text)).put("bytes", bytes.length);
        }
        var result =
                Json.MAPPER
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("kind", staged ? "staged_preview" : "working_tree_preview")
                        .put("head_sha", git.head())
                        .put("base_sha", git.base())
                        .put("release_decision", "not_evaluated")
                        .put("verification", "incomplete")
                        .put(
                                "scope",
                                selected.isEmpty()
                                        ? "all index paths plus explicitly selected untracked paths"
                                        : "only selected tracked paths and explicitly selected untracked paths");
        result.set("policy", policy.binding());
        result.set("files", files);
        result.set("selected_paths", Json.MAPPER.valueToTree(selected.stream().sorted().toList()));
        result.set("untracked", Json.MAPPER.valueToTree(untracked.stream().sorted().toList()));
        result.put("candidate_sha256", Json.hash(Json.stringify(result)));
        return result;
    }

    public static boolean fresh(GitRepository git, ContractPolicy policy, JsonNode saved)
            throws IOException {
        if (saved.path("schema_version").asInt() != 1
                || !Set.of("staged_preview", "working_tree_preview")
                        .contains(saved.path("kind").asText())
                || !saved.path("untracked").isArray())
            throw new IllegalArgumentException("Unsupported preview");
        var untracked = new java.util.ArrayList<String>();
        for (JsonNode path : saved.path("untracked")) untracked.add(path.asText());
        var selected = new java.util.ArrayList<String>();
        for (JsonNode path : saved.path("selected_paths")) selected.add(path.asText());
        return saved.equals(
                capture(
                        git,
                        policy,
                        saved.path("kind").asText().equals("staged_preview"),
                        untracked,
                        selected));
    }

    public static void regularAncestors(Path root, Path file) throws IOException {
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current))
                throw new IOException("Symlink traversal is unsupported");
        }
    }

    private static String safe(String path) {
        GitRepository.safePath(path);
        for (String part : path.split("/")) {
            String lower = part.toLowerCase(java.util.Locale.ROOT);
            if (Set.of(".git", ".aws", ".ssh", ".secrets", ".credentials", "service-data")
                            .contains(lower)
                    || (lower.startsWith(".env")
                            && !Set.of(".env.example", ".env.sample", ".env.template")
                                    .contains(lower))
                    || lower.startsWith("credentials")
                    || lower.matches(".*\\.(pem|key|p12|pfx|jks|keystore|sqlite|sqlite3)")
                    || lower.equals("id_rsa")
                    || lower.equals("id_ed25519")
                    || lower.equals("service.local.json")) {
                throw new IllegalArgumentException("Sensitive paths are excluded from previews");
            }
        }
        return path;
    }
}
