package com.mcaibridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibridge.config.BridgeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AI 大脑：调用 Z.ai OpenAI 兼容接口（模型可配，默认 glm-5.3-flash）。
 * api_key 为空时进入 mock 模式，返回固定回复，保证无密钥环境全链路可测。
 */
public class AIBrain {
    private static final Logger log = LoggerFactory.getLogger(AIBrain.class);
    private static final int MAX_ATTEMPTS = 2;

    private final BridgeConfig cfg;
    private final HttpClient http;

    public AIBrain(BridgeConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (cfg.aiApiKey == null || cfg.aiApiKey.isBlank()) {
            log.warn("ai.api_key 为空 → mock 模式启用（不会调用真实 API）");
        } else {
            log.info("AI 已配置: model={}, url={}", cfg.aiModel, cfg.aiBaseUrl);
        }
    }

    public boolean isMock() {
        return cfg.aiApiKey == null || cfg.aiApiKey.isBlank();
    }

    public String chat(String question) throws Exception {
        if (isMock()) {
            return "你好！我是 " + cfg.botName + "，收到: " + question + "（mock 回复：未配置 API Key）";
        }
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callApi(question);
            } catch (IOException e) {
                last = e;
                log.warn("AI 调用失败(第 {}/{} 次): {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw last;
    }

    private String callApi(String question) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel);
        JsonArray messages = new JsonArray();
        messages.add(msg("system", cfg.aiSystemPrompt));
        messages.add(msg("user", question));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.aiBaseUrl))
                .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.aiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String detail = resp.body() == null ? "" : resp.body();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            throw new IOException("HTTP " + resp.statusCode() + ": " + detail);
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
