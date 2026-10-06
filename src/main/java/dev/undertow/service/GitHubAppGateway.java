package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.github.GitHubClient;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Fixed GitHub.com destinations. App signing and fetch credentials stay in preparation. */
public final class GitHubAppGateway implements ServiceGateway {
    private final String appId;
    private final String clientId;
    private final String clientSecret;
    private final URI publicUrl;
    private final PrivateKey appKey;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public GitHubAppGateway(
            String appId, String clientId, String clientSecret, Path pkcs8Key, URI publicUrl)
            throws Exception {
        if (!appId.matches("[0-9]{1,20}") || clientId.isBlank() || clientSecret.isBlank())
            throw new IllegalArgumentException("GitHub App configuration required");
        this.appId = appId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.publicUrl = publicUrl;
        String pem = Files.readString(pkcs8Key);
        String keyType = "PRIVATE KEY";
        String header = "-----BEGIN " + keyType + "-----";
        String footer = "-----END " + keyType + "-----";
        if (!pem.startsWith(header))
            throw new IllegalArgumentException(
                    "Convert App key to unencrypted PKCS#8 PEM before starting");
        byte[] der =
                Base64.getDecoder()
                        .decode(pem.replace(header, "").replace(footer, "").replaceAll("\\s", ""));
        appKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public String authorizeUrl(String state) {
        return "https://github.com/login/oauth/authorize?client_id="
                + encode(clientId)
                + "&state="
                + encode(state)
                + "&redirect_uri="
                + encode(publicUrl.resolve("/auth/callback").toString());
    }

    private String jwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload =
                base64(
                        Json.stringify(
                                Json.MAPPER
                                        .createObjectNode()
                                        .put("iat", now - 60)
                                        .put("exp", now + 540)
                                        .put("iss", appId)));
        String message = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(appKey);
        signer.update(message.getBytes(StandardCharsets.US_ASCII));
        return message
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode request(String method, URI uri, String token, JsonNode body) throws Exception {
        if (!SetHosts.allowed(uri))
            throw new IllegalArgumentException("Unapproved GitHub destination");
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header(
                                "Accept",
                                uri.getHost().equals("github.com")
                                        ? "application/json"
                                        : "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "Undertow-Service");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var publisher =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.stringify(body));
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<java.io.InputStream> response =
                http.send(
                        request.method(method, publisher).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            byte[] data = stream.readNBytes(2000001);
            if (response.statusCode() / 100 != 2)
                throw new ServiceException(
                        response.statusCode() == 401
                                        || response.statusCode() == 403
                                        || response.statusCode() == 404
                                ? 403
                                : 503,
                        "github_access_or_transport_failure");
            if (data.length > 2000000) throw new IOException("GitHub response exceeds limit");
            return Json.MAPPER.readTree(data);
        }
    }

    private static final class SetHosts {
        private static boolean allowed(URI uri) {
            return uri.getScheme().equals("https")
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && (uri.getHost().equals("api.github.com")
                            || uri.getHost().equals("github.com"));
        }
    }

    private JsonNode api(String method, String path, String token, JsonNode body) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//"))
            throw new IllegalArgumentException("Invalid API path");
        return request(method, URI.create("https://api.github.com" + path), token, body);
    }

    @Override
    public ObjectNode signIn(String code) throws Exception {
        if (code == null || !code.matches("[A-Za-z0-9_-]{1,200}"))
            throw new ServiceException(401, "invalid_oauth_code");
        JsonNode reply =
                request(
                        "POST",
                        URI.create("https://github.com/login/oauth/access_token"),
                        null,
                        Json.MAPPER
                                .createObjectNode()
                                .put("client_id", clientId)
                                .put("client_secret", clientSecret)
                                .put("code", code)
                                .put(
                                        "redirect_uri",
                                        publicUrl.resolve("/auth/callback").toString()));
        String token = reply.path("access_token").asText();
        if (token.isBlank()) throw new ServiceException(401, "github_sign_in_failed");
        JsonNode user = api("GET", "/user", token, null);
        return Json.MAPPER
                .createObjectNode()
                .put("id", user.path("id").asLong())
                .put("login", user.path("login").asText())
                .put("token", token)
                .put(
                        "expires",
                        Instant.now().toEpochMilli()
                                + Math.min(28800, reply.path("expires_in").asLong(28800)) * 1000);
    }

    @Override
    public boolean userAccess(String token, long repoId, boolean write) throws Exception {
        try {
            JsonNode repo = api("GET", "/repositories/" + repoId, token, null);
            if (repo.path("id").asLong() != repoId) return false;
            if (!write) return repo.path("permissions").path("pull").asBoolean();
            return repo.path("permissions").path("push").asBoolean()
                    || repo.path("permissions").path("maintain").asBoolean()
                    || repo.path("permissions").path("admin").asBoolean();
        } catch (ServiceException e) {
            if (e.status() == 403) return false;
            throw e;
        }
    }

    @Override
    public ObjectNode installation(String userToken, long installationId) throws Exception {
        JsonNode selected = null;
        for (int page = 1; page <= 10; page++) {
            JsonNode installations =
                    api("GET", "/user/installations?per_page=100&page=" + page, userToken, null);
            for (JsonNode installation : installations.path("installations"))
                if (installation.path("id").asLong() == installationId) selected = installation;
            if (installations.path("installations").size() < 100) break;
        }
        if (selected == null || selected.path("app_id").asLong() != Long.parseLong(appId))
            throw new ServiceException(403, "installation_authority_denied");
        JsonNode verified = api("GET", "/app/installations/" + installationId, jwt(), null);
        if (!verified.path("suspended_at").isNull())
            throw new ServiceException(403, "installation_suspended");
        long account = verified.path("account").path("id").asLong();
        if (account != selected.path("account").path("id").asLong())
            throw new ServiceException(403, "installation_identity_mismatch");
        boolean admin;
        if (verified.path("account").path("type").asText().equals("User"))
            admin = api("GET", "/user", userToken, null).path("id").asLong() == account;
        else {
            String login = verified.path("account").path("login").asText();
            if (!login.matches("[A-Za-z0-9-]{1,100}"))
                throw new ServiceException(403, "invalid_account");
            JsonNode membership = api("GET", "/user/memberships/orgs/" + login, userToken, null);
            admin =
                    membership.path("state").asText().equals("active")
                            && membership.path("role").asText().equals("admin");
        }
        return Json.MAPPER
                .createObjectNode()
                .put("id", installationId)
                .put("accountId", account)
                .put("canAdminister", admin);
    }

    public String installationToken(long installationId, long repoId, boolean publication)
            throws Exception {
        return installationToken(installationId, repoId, publication, false);
    }

    private String installationToken(
            long installationId, long repoId, boolean publication, boolean actions)
            throws Exception {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.putArray("repository_ids").add(repoId);
        ObjectNode permissions =
                body.putObject("permissions").put("metadata", "read").put("contents", "read");
        permissions.put("pull_requests", publication ? "write" : "read");
        if (actions) permissions.put("actions", "read");
        JsonNode reply =
                api("POST", "/app/installations/" + installationId + "/access_tokens", jwt(), body);
        String token = reply.path("token").asText();
        if (token.isBlank()) throw new ServiceException(403, "installation_token_unavailable");
        return token;
    }

    @Override
    public ObjectNode ciProvenance(long installation, long repository, long workflow, String head)
            throws Exception {
        sha(head);
        String token = installationToken(installation, repository, false, true);
        String name =
                api("GET", "/repositories/" + repository, token, null).path("full_name").asText();
        GitHubClient.ref(name, 1);
        JsonNode runs =
                api(
                        "GET",
                        "/repos/"
                                + name
                                + "/actions/workflows/"
                                + workflow
                                + "/runs?head_sha="
                                + head
                                + "&status=completed&per_page=20",
                        token,
                        null);
        for (JsonNode run : runs.path("workflow_runs")) {
            if (run.path("repository").path("id").asLong() != repository
                    || run.path("workflow_id").asLong() != workflow
                    || !run.path("head_sha").asText().equals(head)
                    || !java.util.Set.of("push", "workflow_dispatch")
                            .contains(run.path("event").asText())) continue;
            long runId = run.path("id").asLong();
            JsonNode artifacts =
                    api(
                            "GET",
                            "/repos/" + name + "/actions/runs/" + runId + "/artifacts?per_page=100",
                            token,
                            null);
            for (JsonNode artifact : artifacts.path("artifacts")) {
                if (!artifact.path("name").asText().equals("single-module-evidence")
                        || artifact.path("expired").asBoolean()
                        || !artifact.path("digest").asText().matches("sha256:[a-f0-9]{64}"))
                    continue;
                return Json.MAPPER
                        .createObjectNode()
                        .put("status", "verified")
                        .put("workflowId", workflow)
                        .put("runId", runId)
                        .put("artifactId", artifact.path("id").asLong())
                        .put("digest", artifact.path("digest").asText())
                        .put("head", head)
                        .put("repositoryId", repository)
                        .put("name", name);
            }
        }
        return Json.MAPPER.createObjectNode().put("status", "missing").put("workflowId", workflow);
    }

    @Override
    public ObjectNode importCi(
            long installation, long repository, ObjectNode snapshot, Path workspace)
            throws Exception {
        JsonNode origin = snapshot.path("ciOrigin");
        if (!origin.path("status").asText().equals("verified"))
            return Json.MAPPER.createObjectNode();
        if (origin.path("repositoryId").asLong() != repository
                || !origin.path("head").asText().equals(snapshot.path("head").asText()))
            throw new ServiceException(422, "ci_origin_mismatch");
        Path importer = workspace.resolve("ci-importer");
        Files.createDirectories(importer);
        for (String file : List.of("import-ci.py", "ci-formats.py"))
            try (var resource = GitHubAppGateway.class.getResourceAsStream("/service/ci/" + file)) {
                if (resource == null) throw new IOException("Missing trusted CI importer");
                Files.copy(resource, importer.resolve(file));
            }
        Path bundle = importer.resolve("bundle.json"), receipt = importer.resolve("receipt.json");
        List<String> command =
                List.of(
                        "python3",
                        importer.resolve("import-ci.py").toString(),
                        "--repository",
                        origin.path("name").asText(),
                        "--run-id",
                        origin.path("runId").asText(),
                        "--workflow-id",
                        origin.path("workflowId").asText(),
                        "--base",
                        snapshot.path("diffBase").asText(),
                        "--head",
                        snapshot.path("head").asText(),
                        "--output",
                        bundle.toString(),
                        "--receipt",
                        receipt.toString());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        builder.environment()
                .put("GITHUB_TOKEN", installationToken(installation, repository, false, true));
        Process process = builder.start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var read = executor.submit(() -> process.getInputStream().readNBytes(100001));
            byte[] bytes = read.get(120, TimeUnit.SECONDS);
            if (bytes.length > 100000
                    || !process.waitFor(120, TimeUnit.SECONDS)
                    || process.exitValue() != 0) throw new IOException("Trusted CI import failed");
        } finally {
            process.destroyForcibly();
            executor.shutdownNow();
        }
        String bundleText = Json.read(bundle, 180000), receiptText = Json.read(receipt, 16000);
        JsonNode receiptNode = Json.parse(receiptText);
        if (receiptNode.path("artifact_id").asLong() != origin.path("artifactId").asLong()
                || !receiptNode.path("head_sha").asText().equals(origin.path("head").asText()))
            throw new ServiceException(422, "ci_origin_changed");
        return Json.MAPPER.createObjectNode().put("bundle", bundleText).put("receipt", receiptText);
    }

    @Override
    public ObjectNode repository(long installationId, long repoId) throws Exception {
        JsonNode repo =
                api(
                        "GET",
                        "/repositories/" + repoId,
                        installationToken(installationId, repoId, false),
                        null);
        String name = repo.path("full_name").asText();
        GitHubClient.ref(name, 1);
        if (repo.path("id").asLong() != repoId)
            throw new ServiceException(403, "repository_identity_mismatch");
        return Json.MAPPER
                .createObjectNode()
                .put("id", repoId)
                .put("name", name)
                .put("accountId", repo.path("owner").path("id").asLong());
    }

    @Override
    public ObjectNode snapshot(long installationId, long repoId, int number) throws Exception {
        String token = installationToken(installationId, repoId, false);
        JsonNode repo = api("GET", "/repositories/" + repoId, token, null);
        String name = repo.path("full_name").asText();
        GitHubClient.ref(name, number);
        String prefix = "/repos/" + name;
        JsonNode pr = api("GET", prefix + "/pulls/" + number, token, null);
        if (pr.path("base").path("repo").path("id").asLong() != repoId)
            throw new ServiceException(403, "repository_identity_mismatch");
        String branch = pr.path("base").path("ref").asText();
        if (branch.isBlank() || branch.length() > 255)
            throw new ServiceException(422, "invalid_target_branch");
        JsonNode ref = api("GET", prefix + "/git/ref/heads/" + encode(branch), token, null);
        String head = pr.path("head").path("sha").asText(),
                target = ref.path("object").path("sha").asText();
        sha(head);
        sha(target);
        JsonNode comparison = api("GET", prefix + "/compare/" + target + "..." + head, token, null);
        String base = comparison.path("merge_base_commit").path("sha").asText();
        sha(base);
        String author = pr.path("user").path("login").asText();
        if (!author.matches("[A-Za-z0-9_-]{1,100}(\\[bot\\])?"))
            throw new ServiceException(422, "invalid_author");
        JsonNode permission =
                api(
                        "GET",
                        prefix + "/collaborators/" + encode(author) + "/permission",
                        token,
                        null);
        boolean maintainer =
                java.util.Set.of("write", "maintain", "admin")
                        .contains(permission.path("permission").asText());
        return Json.MAPPER
                .createObjectNode()
                .put("repositoryId", repoId)
                .put("accountId", repo.path("owner").path("id").asLong())
                .put("name", name)
                .put("pr", number)
                .put("head", head)
                .put("target", target)
                .put("diffBase", base)
                .put("policyCommit", target)
                .put("branch", branch)
                .put("open", pr.path("state").asText().equals("open"))
                .put("draft", pr.path("draft").asBoolean())
                .put("internal", pr.path("head").path("repo").path("id").asLong() == repoId)
                .put("maintainerAuthor", maintainer)
                .put("ciIdentity", "missing");
    }

    private static void sha(String value) {
        if (!value.matches("[a-f0-9]{40}|[a-f0-9]{64}"))
            throw new ServiceException(422, "missing_pinned_commit");
    }

    @Override
    public Map<String, String> policyFiles(long installationId, long repoId, String commit)
            throws Exception {
        sha(commit);
        String token = installationToken(installationId, repoId, false);
        String name = api("GET", "/repositories/" + repoId, token, null).path("full_name").asText();
        GitHubClient.ref(name, 1);
        JsonNode tree =
                api("GET", "/repos/" + name + "/git/trees/" + commit + "?recursive=1", token, null);
        if (tree.path("truncated").asBoolean())
            throw new ServiceException(422, "policy_tree_truncated");
        Map<String, String> files = new TreeMap<>();
        for (JsonNode entry : tree.path("tree")) {
            String path = entry.path("path").asText();
            if (!path.startsWith(".undertow/") || !entry.path("type").asText().equals("blob"))
                continue;
            if (!java.util.Set.of("100644", "100755").contains(entry.path("mode").asText())
                    || entry.path("size").asLong() > 256000
                    || files.size() >= 100) throw new ServiceException(422, "unsafe_policy_blob");
            String blobSha = entry.path("sha").asText();
            sha(blobSha);
            JsonNode blob = api("GET", "/repos/" + name + "/git/blobs/" + blobSha, token, null);
            if (!blob.path("encoding").asText().equals("base64"))
                throw new ServiceException(422, "unsupported_policy_encoding");
            byte[] bytes = Base64.getMimeDecoder().decode(blob.path("content").asText());
            if (bytes.length > 256000) throw new ServiceException(422, "policy_too_large");
            files.put(path, new String(bytes, StandardCharsets.UTF_8));
        }
        return ServicePolicy.files(Json.MAPPER.valueToTree(files));
    }

    @Override
    public void prepare(long installationId, long repoId, ObjectNode snapshot, Path destination)
            throws Exception {
        ObjectNode current = repository(installationId, repoId);
        if (current.path("accountId").asLong() != snapshot.path("accountId").asLong())
            throw new ServiceException(403, "repository_transferred");
        String name = current.path("name").asText();
        GitHubClient.ref(name, snapshot.path("pr").asInt());
        String token = installationToken(installationId, repoId, false);
        Files.createDirectories(destination);
        git(destination, null, "init", "--bare", "--quiet");
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "fetch",
                                "--no-tags",
                                "--quiet",
                                "https://github.com/" + name + ".git"));
        for (String field : List.of("head", "target", "diffBase")) {
            String commit = snapshot.path(field).asText();
            sha(commit);
            args.add(commit);
        }
        git(destination, token, args.toArray(String[]::new));
        String base =
                git(
                        destination,
                        null,
                        "merge-base",
                        snapshot.path("target").asText(),
                        snapshot.path("head").asText());
        if (!base.equals(snapshot.path("diffBase").asText()))
            throw new ServiceException(409, "merge_base_mismatch");
    }

    private static String git(Path root, String token, String... args) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "credential.helper=",
                                "-C",
                                root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (token != null) {
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
            builder.environment()
                    .put(
                            "GIT_CONFIG_VALUE_0",
                            "Authorization: Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    ("x-access-token:" + token)
                                                            .getBytes(StandardCharsets.UTF_8)));
        }
        Process process = builder.start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var read = executor.submit(() -> process.getInputStream().readNBytes(256001));
            byte[] bytes = read.get(120, TimeUnit.SECONDS);
            if (bytes.length > 256000
                    || !process.waitFor(120, TimeUnit.SECONDS)
                    || process.exitValue() != 0)
                throw new IOException("Snapshot Git operation failed");
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } finally {
            process.destroyForcibly();
            executor.shutdownNow();
        }
    }

    public GitHubClient publisher(
            long installation,
            long repository,
            String name,
            GitHubClient.BotIdentity identity,
            boolean recovery)
            throws Exception {
        ObjectNode repo = repository(installation, repository);
        if (!repo.path("name").asText().equals(name))
            throw new ServiceException(403, "repository_name_changed");
        return new GitHubClient(
                        name, installationToken(installation, repository, true), null, identity)
                .recoveryOnly(recovery);
    }
}
