package dev.undertow.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

/** Small stdio MCP transport over the same verifier. Host fixes every authority-bearing input. */
public final class ContractMcp {
    private final GitRepository git;
    private final ContractPolicy policy;
    private final Path bundle;
    private final Path receipt;
    private boolean initialized;

    public ContractMcp(GitRepository git, ContractPolicy policy, Path bundle, Path receipt) {
        this.git = git;
        this.policy = policy;
        this.bundle = bundle;
        this.receipt = receipt;
    }

    public void serve(InputStream input, PrintStream output) throws IOException {
        String line;
        while ((line = line(input)) != null) {
            JsonNode response;
            try {
                response = handle(Json.parse(line));
            } catch (IllegalArgumentException | IOException e) {
                response = error(null, -32700, "Invalid request");
            }
            if (response != null) output.println(Json.stringify(response));
        }
    }

    public JsonNode handle(JsonNode request) throws IOException {
        JsonNode id = request.get("id");
        if (!request.isObject()
                || !request.path("jsonrpc").asText().equals("2.0")
                || !request.path("method").isTextual()
                || (id != null && !id.isTextual() && !id.isIntegralNumber()))
            return error(null, -32600, "Invalid request");
        String method = request.path("method").asText();
        if (id == null) return null;
        var reply = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", id);
        ObjectNode result = Json.MAPPER.createObjectNode();
        if (method.equals("initialize")) {
            String requested = request.path("params").path("protocolVersion").asText();
            String version =
                    Set.of("2024-11-05", "2025-03-26", "2025-06-18").contains(requested)
                            ? requested
                            : "2025-06-18";
            result.put("protocolVersion", version);
            result.putObject("capabilities").putObject("tools").put("listChanged", false);
            result.putObject("serverInfo")
                    .put("name", "undertow-contracts")
                    .put("version", "1.0.0");
            initialized = true;
        } else if (!initialized) return error(id, -32002, "Initialize first");
        else if (method.equals("ping")) {
            /* Empty result. */
        } else if (method.equals("tools/list")) {
            var tools = result.putArray("tools");
            for (String name : java.util.List.of("contracts_read", "contracts_verify")) {
                var tool =
                        tools.addObject()
                                .put("name", name)
                                .put(
                                        "description",
                                        name.equals("contracts_read")
                                                ? "Read pinned approved contract intent"
                                                : "Verify host-bound CI evidence in shadow mode");
                tool.putObject("inputSchema")
                        .put("type", "object")
                        .put("additionalProperties", false)
                        .putObject("properties");
                tool.putObject("annotations")
                        .put("readOnlyHint", true)
                        .put("destructiveHint", false)
                        .put("openWorldHint", false);
            }
        } else if (method.equals("tools/call")) {
            String name = request.path("params").path("name").asText();
            JsonNode arguments = request.path("params").path("arguments");
            if ((!arguments.isMissingNode() && (!arguments.isObject() || !arguments.isEmpty()))
                    || !Set.of("contracts_read", "contracts_verify").contains(name))
                return error(id, -32602, "Unknown tool or unsupported arguments");
            JsonNode value;
            if (name.equals("contracts_read")) {
                var read = Json.MAPPER.createObjectNode();
                read.set("policy", policy.binding());
                read.set("manifest", policy.manifest());
                value = read;
            } else value = ContractVerifier.verify(git, policy, bundle, receipt);
            result.putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", Json.stringify(value));
            result.set("structuredContent", value);
            result.put("isError", false);
        } else return error(id, -32601, "Method not found");
        reply.set("result", result);
        return reply;
    }

    private static JsonNode error(JsonNode id, int code, String message) {
        var reply = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", id == null ? Json.MAPPER.nullNode() : id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }

    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (bytes.size() >= 64000) throw new IOException("MCP message exceeds limit");
            bytes.write(next);
        }
        return next == -1 && bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
    }
}
