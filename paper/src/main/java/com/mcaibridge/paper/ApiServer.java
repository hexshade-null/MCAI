package com.mcaibridge.paper;

import com.google.gson.Gson;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * 插件内置 HTTP API（JDK HttpServer，零依赖）：
 * POST /mcai/skin        — bridge 上传皮肤 {name, model, png(b64)}
 * GET  /mcai/skinimg/... — 客户端拉取皮肤 PNG（textures url 指向这里）
 */
public class ApiServer {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private static final GsonHolder GSON = new GsonHolder();

    private final JavaPlugin plugin;
    private final SkinService skins;
    private HttpServer server;

    ApiServer(JavaPlugin plugin, SkinService skins, int port) {
        this.plugin = plugin;
        this.skins = skins;
        this.port = port;
    }

    private final int port;

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/mcai/skin", ex -> {
            try {
                if (!"POST".equals(ex.getRequestMethod())) {
                    respond(ex, 405, "{\"error\":\"method\"}");
                    return;
                }
                if (!tokenOk(ex)) {
                    respond(ex, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                byte[] body;
                try (InputStream in = ex.getRequestBody()) {
                    body = in.readAllBytes();
                }
                var req = GSON.get().fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                String name = req.get("name").getAsString();
                String model = req.has("model") ? req.get("model").getAsString() : "classic";
                byte[] png = Base64.getDecoder().decode(req.get("png").getAsString());
                if (png.length < 100 || png.length > 200_000) {
                    respond(ex, 400, "{\"error\":\"bad png size\"}");
                    return;
                }
                String url = skins.store(name, model, png);
                respond(ex, 200, "{\"ok\":true,\"url\":\"" + url + "\"}");
            } catch (Exception e) {
                log.warn("皮肤上传处理异常: {}", e.toString());
                respond(ex, 500, "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/mcai/chat", ex -> {
            try {
                if (!"POST".equals(ex.getRequestMethod())) {
                    respond(ex, 405, "{\"error\":\"method\"}");
                    return;
                }
                if (!tokenOk(ex)) {
                    respond(ex, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                byte[] body;
                try (InputStream in = ex.getRequestBody()) {
                    body = in.readAllBytes();
                }
                var req = GSON.get().fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                String from = req.get("from").getAsString();
                String text = req.get("text").getAsString();
                Bukkit.getScheduler().runTask(plugin, () ->
                        org.bukkit.Bukkit.broadcastMessage("<" + from + "> " + text));
                respond(ex, 200, "{\"ok\":true}");
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/mcai/where", ex -> {
            try {
                if (!tokenOk(ex)) {
                    respond(ex, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                var q = ex.getRequestURI().getQuery();
                String name = q == null ? "" : java.net.URLDecoder.decode(
                        java.util.Arrays.stream(q.split("&")).filter(s -> s.startsWith("name="))
                                .findFirst().map(s -> s.substring(5)).orElse(""), StandardCharsets.UTF_8);
                org.bukkit.entity.Player p = name.isBlank() ? null : Bukkit.getPlayerExact(name);
                if (p == null) {
                    respond(ex, 404, "{\"error\":\"player not found\"}");
                    return;
                }
                var loc = p.getLocation();
                respond(ex, 200, "{\"name\":\"" + p.getName() + "\",\"x\":" + loc.getX()
                        + ",\"y\":" + loc.getY() + ",\"z\":" + loc.getZ() + "}");
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/mcai/skinimg", ex -> {
            try {
                String path = ex.getRequestURI().getPath(); // /mcai/skinimg/<name>.png
                String file = path.substring(path.lastIndexOf('/') + 1);
                if (!file.endsWith(".png") || file.contains("..")) {
                    respond(ex, 400, "bad request");
                    return;
                }
                Path png = skins.png(file.substring(0, file.length() - 4));
                if (!Files.exists(png)) {
                    respond(ex, 404, "not found");
                    return;
                }
                byte[] bytes = Files.readAllBytes(png);
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                respond(ex, 500, "error");
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mcai-api");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("MCAI API 监听端口 {}", port);
    }

    void stop() {
        if (server != null) server.stop(0);
    }

    private boolean tokenOk(HttpExchange ex) {
        String expected = skins.token();
        if (expected == null || expected.isBlank()) return true;
        return expected.equals(ex.getRequestHeaders().getFirst("X-MCAI-Token"));
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 延迟持有 Gson，避免类初始化顺序问题。 */
    private static class GsonHolder {
        private final com.google.gson.Gson gson = new com.google.gson.Gson();

        com.google.gson.Gson get() {
            return gson;
        }
    }
}
