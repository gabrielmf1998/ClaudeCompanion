package com.kxinha.claudecompanion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.server.MinecraftServer;

/**
 * Minimal MCP server (streamable HTTP transport, JSON responses only) bound to 127.0.0.1.
 * Every Claude session gets its own path so the tools know which player they act for.
 */
public final class McpBridge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String[] PROTOCOLS = {"2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"};
    private static final int MAX_BODY = 1 << 20;

    private final MinecraftServer server;
    private final GameTools tools;
    private final String token;
    private final Map<String, UUID> contexts = new ConcurrentHashMap<>();
    private HttpServer http;
    private ExecutorService pool;

    public McpBridge(MinecraftServer server) {
        this.server = server;
        this.tools = new GameTools(server);
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        this.token = HexFormat.of().formatHex(b);
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 16);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "claudecompanion-mcp");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/mcp/", this::handle);
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    /** Registers a session key. playerId null means the server console. */
    public void bind(String key, UUID playerId) {
        contexts.put(key, playerId == null ? GameTools.CONSOLE : playerId);
    }

    public void unbind(String key) {
        contexts.remove(key);
    }

    /** JSON for --mcp-config. */
    public String mcpConfig(String key) {
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        JsonObject mc = new JsonObject();
        mc.addProperty("type", "http");
        mc.addProperty("url", "http://127.0.0.1:" + port() + "/mcp/" + key);
        mc.add("headers", headers);
        JsonObject servers = new JsonObject();
        servers.add("minecraft", mc);
        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return GSON.toJson(root);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, null);
                return;
            }
            String key = ex.getRequestURI().getPath().substring("/mcp/".length());
            UUID player = contexts.get(key);
            if (player == null) {
                send(ex, 404, null);
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "POST");
                send(ex, 405, null);
                return;
            }
            JsonElement body;
            try (InputStream in = ex.getRequestBody()) {
                byte[] data = in.readNBytes(MAX_BODY + 1);
                if (data.length > MAX_BODY) {
                    send(ex, 413, null);
                    return;
                }
                body = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                send(ex, 400, rpcError(null, -32700, "Parse error"));
                return;
            }
            if (body.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement el : body.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject(), player);
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) send(ex, 202, null);
                else send(ex, 200, out);
            } else {
                JsonObject r = dispatch(body.getAsJsonObject(), player);
                if (r == null) send(ex, 202, null);
                else send(ex, 200, r);
            }
        } catch (Exception e) {
            ClaudeCompanion.LOG.warn("MCP request failed", e);
        }
    }

    private JsonObject dispatch(JsonObject req, UUID player) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";
        if (id == null || id.isJsonNull()) return null;
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> {
                    JsonObject o = new JsonObject();
                    o.add("tools", tools.list());
                    yield o;
                }
                case "tools/call" -> tools.call(player,
                        params.has("name") ? params.get("name").getAsString() : "",
                        params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject());
                default -> null;
            };
            if (result == null) return rpcError(id, -32601, "Method not found: " + method);
            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", "2.0");
            resp.add("id", id);
            resp.add("result", result);
            return resp;
        } catch (Exception e) {
            return rpcError(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private JsonObject initialize(JsonObject params) {
        String wanted = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOLS[1];
        String chosen = PROTOCOLS[1];
        for (String p : PROTOCOLS) if (p.equals(wanted)) chosen = p;
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "minecraft");
        info.addProperty("version", ClaudeCompanion.platform().modVersion());
        JsonObject o = new JsonObject();
        o.addProperty("protocolVersion", chosen);
        o.add("capabilities", caps);
        o.add("serverInfo", info);
        o.addProperty("instructions", "Tools that act on the live Minecraft world the player is in. They run on the server thread with operator power.");
        return o;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        return resp;
    }

    private static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] data = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
