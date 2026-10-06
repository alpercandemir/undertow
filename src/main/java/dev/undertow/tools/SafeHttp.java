package dev.undertow.tools;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

public final class SafeHttp {
    private static final Set<String> OFFICIAL_HOSTS =
            Set.of("github.com", "raw.githubusercontent.com", "square.github.io", "api.osv.dev");
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public String get(String url, Set<String> hosts) throws IOException, InterruptedException {
        URI uri = validate(url, hosts);
        return send(
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "text/html,application/json,text/plain")
                        .GET()
                        .build());
    }

    public String postOsv(String body) throws IOException, InterruptedException {
        URI uri = validate("https://api.osv.dev/v1/query", Set.of("api.osv.dev"));
        return send(
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    public static URI validate(String url, Set<String> hosts) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid evidence URL", e);
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || !hosts.contains(uri.getHost())
                || !OFFICIAL_HOSTS.contains(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getFragment() != null) {
            throw new IOException("URL outside trusted source registry");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || address.getHostAddress().startsWith("fc")
                    || address.getHostAddress().startsWith("fd")) {
                throw new IOException("Private evidence address rejected");
            }
        }
        return uri;
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Evidence HTTP status " + response.statusCode());
            }
            int limit = request.method().equals("GET") ? 512000 : 128000;
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length > limit) {
                throw new IOException("Evidence exceeds size limit");
            }
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
