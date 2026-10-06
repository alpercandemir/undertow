package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Same-origin browser sessions; machine tokens are deliberately reserved for Phase 3 P1. */
public final class ServiceHttpServer implements AutoCloseable {
    private static final int BODY_LIMIT = 1100000;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore requests = new Semaphore(32);
    private final ServiceControl control;
    private final GitHubAppGateway github;
    private final String webhookSecret;
    private final String operatorToken;
    private final URI publicUrl;

    public ServiceHttpServer(
            InetSocketAddress bind,
            URI publicUrl,
            ServiceControl control,
            GitHubAppGateway github,
            String webhookSecret,
            String operatorToken)
            throws IOException {
        if (webhookSecret == null
                || webhookSecret.length() < 32
                || operatorToken == null
                || operatorToken.length() < 32)
            throw new IllegalArgumentException("Strong webhook and operator credentials required");
        if (!publicUrl.getScheme().equals("https")
                || publicUrl.getHost() == null
                || publicUrl.getQuery() != null
                || publicUrl.getUserInfo() != null)
            throw new IllegalArgumentException("HTTPS public origin required");
        this.control = control;
        this.github = github;
        this.webhookSecret = webhookSecret;
        this.operatorToken = operatorToken;
        this.publicUrl = publicUrl;
        server = HttpServer.create(bind, 64);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!requests.tryAcquire()) {
            respond(exchange, 429, Json.MAPPER.createObjectNode().put("error", "service_busy"));
            return;
        }
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders()
                    .set(
                            "Content-Security-Policy",
                            "default-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'");
            route(exchange);
        } catch (ServiceException e) {
            respond(exchange, e.status(), Json.MAPPER.createObjectNode().put("error", e.code()));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respond(exchange, 422, Json.MAPPER.createObjectNode().put("error", "invalid_request"));
        } catch (Exception e) {
            respond(
                    exchange,
                    503,
                    Json.MAPPER.createObjectNode().put("error", "service_unavailable"));
        } finally {
            exchange.close();
            requests.release();
        }
    }

    private void route(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
        var headers = exchange.getRequestHeaders();
        if (method.equals("GET") && path.equals("/health")) {
            respond(
                    exchange,
                    200,
                    Json.MAPPER
                            .createObjectNode()
                            .put("status", "ok")
                            .put("externalPilotReady", false));
            return;
        }
        if (method.equals("GET")
                && Map.of("/", "index.html", "/app.js", "app.js", "/app.css", "app.css")
                        .containsKey(path)) {
            String resource =
                    Map.of("/", "index.html", "/app.js", "app.js", "/app.css", "app.css").get(path);
            try (var stream = ServiceHttpServer.class.getResourceAsStream("/service/" + resource)) {
                if (stream == null) throw new ServiceException(404, "not_found");
                byte[] bytes = stream.readAllBytes();
                String type = "text/html";
                if (resource.endsWith("js")) {
                    type = "application/javascript";
                } else if (resource.endsWith("css")) {
                    type = "text/css";
                }
                exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            return;
        }
        if (method.equals("GET") && path.equals("/auth/login")) {
            if (github == null) throw new ServiceException(503, "github_not_configured");
            String state = control.beginLogin();
            cookie(exchange, "undertow_oauth", state, 600);
            redirect(exchange, github.authorizeUrl(state));
            return;
        }
        if (method.equals("GET") && path.equals("/auth/callback")) {
            var params = query(exchange.getRequestURI());
            ObjectNode session =
                    control.finishLogin(
                            params.get("state"),
                            cookies(exchange).get("undertow_oauth"),
                            params.get("code"));
            cookie(exchange, "undertow_oauth", "", 0);
            cookie(
                    exchange,
                    "undertow_session",
                    session.path("session").asText(),
                    (int) Duration.ofHours(8).toSeconds());
            redirect(exchange, "/");
            return;
        }
        if (method.equals("POST") && path.equals("/v1/webhooks/github")) {
            byte[] bytes = body(exchange);
            ServiceSecurity.verifyWebhook(
                    bytes, headers.getFirst("X-Hub-Signature-256"), webhookSecret);
            JsonNode parsed = Json.MAPPER.readTree(bytes);
            if (!parsed.isObject()) throw new ServiceException(422, "invalid_event");
            respond(
                    exchange,
                    202,
                    control.receive(
                            headers.getFirst("X-GitHub-Delivery"),
                            headers.getFirst("X-GitHub-Event"),
                            (ObjectNode) parsed));
            return;
        }
        if (path.equals("/internal/publication/check") && method.equals("POST")) {
            ObjectNode input = jsonBody(exchange);
            String authorization = headers.getFirst("Authorization");
            String permit =
                    authorization != null && authorization.startsWith("Bearer ")
                            ? authorization.substring(7)
                            : null;
            control.checkPublication(
                    input.path("tenant").asText(),
                    input.path("review").asText(),
                    input.path("fence").asLong(),
                    permit);
            respond(exchange, 200, Json.MAPPER.createObjectNode().put("permitted", true));
            return;
        }
        if (path.startsWith("/operator/")) {
            operator(exchange, path, method);
            return;
        }
        boolean mutation = !method.equals("GET");
        if (mutation
                && !ServiceSecurity.matches(
                        publicUrl.toString().replaceAll("/$", ""), headers.getFirst("Origin")))
            throw new ServiceException(403, "invalid_origin");
        var session =
                control.session(
                        cookies(exchange).get("undertow_session"),
                        headers.getFirst("X-CSRF-Token"),
                        mutation);
        if (path.equals("/v1/session") && method.equals("GET")) {
            respond(
                    exchange,
                    200,
                    Json.MAPPER
                            .createObjectNode()
                            .put("user", session.user())
                            .put("csrf", session.csrf())
                            .set("tenants", Json.MAPPER.valueToTree(control.tenants(session))));
            return;
        }
        if (path.equals("/auth/logout") && method.equals("POST")) {
            control.logout(cookies(exchange).get("undertow_session"));
            cookie(exchange, "undertow_session", "", 0);
            respond(exchange, 200, Json.MAPPER.createObjectNode().put("status", "signed_out"));
            return;
        }
        ObjectNode input = mutation ? jsonBody(exchange) : Json.MAPPER.createObjectNode();
        if (path.equals("/v1/installations") && method.equals("POST")) {
            respond(exchange, 201, control.connect(session, input.path("installationId").asLong()));
            return;
        }
        String tenant = headers.getFirst("X-Undertow-Tenant");
        if (tenant == null || tenant.length() > 100)
            throw new ServiceException(422, "tenant_required");
        String[] parts = path.split("/");
        if (path.equals("/v1/repositories")) {
            if (method.equals("GET"))
                respond(
                        exchange,
                        200,
                        Json.MAPPER.valueToTree(control.repositories(session, tenant)));
            else if (method.equals("POST"))
                respond(
                        exchange,
                        201,
                        control.registerRepository(
                                session, tenant, input.path("repositoryId").asLong()));
            else throw new ServiceException(405, "method_not_allowed");
            return;
        }
        if (path.equals("/v1/rule-packages") && method.equals("POST")) {
            respond(exchange, 201, control.createPackage(session, tenant, input));
            return;
        }
        if (parts.length == 5
                && parts[2].equals("repositories")
                && parts[4].equals("settings")
                && method.equals("PATCH")) {
            respond(exchange, 200, control.configure(session, tenant, parts[3], input));
            return;
        }
        if (parts.length == 5
                && parts[2].equals("repositories")
                && parts[4].equals("reviews")
                && method.equals("GET")) {
            respond(
                    exchange,
                    200,
                    Json.MAPPER.valueToTree(control.history(session, tenant, parts[3])));
            return;
        }
        if (parts.length == 7
                && parts[2].equals("repositories")
                && parts[4].equals("pull-requests")
                && parts[6].equals("reviews")
                && method.equals("POST")) {
            respond(
                    exchange,
                    202,
                    control.requestReview(
                            session,
                            tenant,
                            parts[3],
                            Integer.parseInt(parts[5]),
                            headers.getFirst("Idempotency-Key")));
            return;
        }
        if (parts.length >= 4 && parts[2].equals("reviews")) {
            if (parts.length == 4 && method.equals("GET"))
                respond(exchange, 200, control.review(session, tenant, parts[3]));
            else if (parts.length == 5 && parts[4].equals("artifacts") && method.equals("GET"))
                respond(exchange, 200, control.artifact(session, tenant, parts[3]));
            else if (parts.length == 5 && parts[4].equals("feedback") && method.equals("POST")) {
                control.feedback(session, tenant, parts[3], input);
                respond(exchange, 201, Json.MAPPER.createObjectNode().put("status", "recorded"));
            } else throw new ServiceException(404, "not_found");
            return;
        }
        if (path.equals("/v1/credits") && method.equals("GET")) {
            respond(exchange, 200, control.credits(session, tenant));
            return;
        }
        if (path.equals("/v1/usage") && method.equals("GET")) {
            respond(exchange, 200, Json.MAPPER.valueToTree(control.usage(session, tenant)));
            return;
        }
        if (parts.length == 4 && parts[2].equals("memberships") && method.equals("PATCH")) {
            control.membership(session, tenant, parts[3], input.path("roles"));
            respond(exchange, 200, Json.MAPPER.createObjectNode().put("status", "updated"));
            return;
        }
        throw new ServiceException(404, "not_found");
    }

    private void operator(HttpExchange exchange, String path, String method) throws Exception {
        if (!ServiceSecurity.matches(
                "Bearer " + operatorToken, exchange.getRequestHeaders().getFirst("Authorization")))
            throw new ServiceException(401, "operator_required");
        if (!method.equals("POST")) throw new ServiceException(405, "method_not_allowed");
        ObjectNode input = jsonBody(exchange);
        String actor = "service_operator",
                tenant = input.path("tenant").asText(),
                reason = input.path("reason").asText();
        if (path.equals("/operator/grants")) {
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
                throw new ServiceException(422, "idempotency_key_required");
            control.grant(
                    tenant,
                    input.path("amount").asLong(),
                    input.path("expires").asLong(),
                    key,
                    actor,
                    reason);
        } else if (path.equals("/operator/reconciliation"))
            control.reconcile(
                    tenant,
                    input.path("review").asText(),
                    (ObjectNode) input.path("usage"),
                    actor,
                    reason);
        else if (path.equals("/operator/control"))
            control.operatorControl(tenant, actor, input.path("action").asText(), reason);
        else throw new ServiceException(404, "not_found");
        respond(exchange, 200, Json.MAPPER.createObjectNode().put("status", "recorded"));
    }

    private static ObjectNode jsonBody(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
            throw new ServiceException(415, "json_required");
        JsonNode node = Json.MAPPER.readTree(body(exchange));
        if (node == null || !node.isObject())
            throw new ServiceException(422, "json_object_required");
        return (ObjectNode) node;
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) throw new ServiceException(413, "request_too_large");
        return bytes;
    }

    private static Map<String, String> cookies(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null)
            for (String item : cookie.split(";")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length == 2) values.put(pair[0], pair[1]);
            }
        return values;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        if (uri.getRawQuery() != null)
            for (String item : uri.getRawQuery().split("&")) {
                String[] pair = item.split("=", 2);
                if (pair.length == 2)
                    values.put(
                            URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        return values;
    }

    private static void cookie(HttpExchange exchange, String name, String value, int age) {
        exchange.getResponseHeaders()
                .add(
                        "Set-Cookie",
                        name
                                + "="
                                + value
                                + "; Path=/; Max-Age="
                                + age
                                + "; Secure; HttpOnly; SameSite=Lax");
    }

    private static void redirect(HttpExchange exchange, String url) throws IOException {
        exchange.getResponseHeaders().set("Location", url);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void respond(HttpExchange exchange, int status, JsonNode node)
            throws IOException {
        byte[] bytes = Json.stringify(node).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }
}
