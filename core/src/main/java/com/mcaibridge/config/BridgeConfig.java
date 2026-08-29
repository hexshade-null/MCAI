package com.mcaibridge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桥接配置模型。支持 YAML + ${ENV_VAR} 环境变量插值。
 */
public class BridgeConfig {
    private static final Logger log = LoggerFactory.getLogger(BridgeConfig.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    // bot
    public String botName = "BridgeBot";
    public String authMethod = "offline";            // offline | microsoft
    public String microsoftClientId = "";
    // server
    public String serverHost = "localhost";
    public int serverPort = 25565;
    // ai
    public String aiBaseUrl = "https://api.z.ai/api/paas/v4/chat/completions";
    public String aiModel = "glm-5.3-flash";
    public String aiApiKey = "";
    public String aiSystemPrompt = "你是Minecraft服务器里的AI玩家，用中文简短回答（一两句话），语气友好自然。";
    public String replyPrefix = "[%s] ";             // %s 会被替换为 bot 名字
    public int aiTimeoutSeconds = 30;
    // connection
    public int reconnectMaxAttempts = 5;
    // probe（自检）
    public String probeName = "TestPlayer";
    public long probeTimeoutMs = 25000;

    public static BridgeConfig load(Path path) throws IOException {
        BridgeConfig cfg = new BridgeConfig();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                Map<String, Object> root = new Yaml().load(in);
                if (root != null) cfg.apply(root);
            }
        } else {
            log.warn("配置文件不存在: {}，使用默认值", path);
        }
        cfg.interpolate();
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> root) {
        Map<String, Object> bot = (Map<String, Object>) root.get("bot");
        Map<String, Object> server = (Map<String, Object>) root.get("server");
        Map<String, Object> ai = (Map<String, Object>) root.get("ai");
        Map<String, Object> conn = (Map<String, Object>) root.get("connection");
        Map<String, Object> probe = (Map<String, Object>) root.get("probe");
        if (bot != null) {
            botName = str(bot, "name", botName);
            authMethod = str(bot, "auth", authMethod);
            microsoftClientId = str(bot, "microsoft_client_id", microsoftClientId);
        }
        if (server != null) {
            serverHost = str(server, "host", serverHost);
            serverPort = (int) num(server, "port", serverPort);
        }
        if (ai != null) {
            aiBaseUrl = str(ai, "base_url", aiBaseUrl);
            aiModel = str(ai, "model", aiModel);
            aiApiKey = str(ai, "api_key", aiApiKey);
            aiSystemPrompt = str(ai, "system_prompt", aiSystemPrompt);
            replyPrefix = str(ai, "reply_prefix", replyPrefix);
            aiTimeoutSeconds = (int) num(ai, "timeout_seconds", aiTimeoutSeconds);
        }
        if (conn != null) {
            reconnectMaxAttempts = (int) num(conn, "reconnect_max_attempts", reconnectMaxAttempts);
        }
        if (probe != null) {
            probeName = str(probe, "name", probeName);
            probeTimeoutMs = num(probe, "timeout_ms", probeTimeoutMs);
        }
    }

    /** 递归插值 ${ENV_VAR}；未定义的环境变量置空并告警。 */
    private void interpolate() {
        aiBaseUrl = interp(aiBaseUrl);
        aiApiKey = interp(aiApiKey);
        aiSystemPrompt = interp(aiSystemPrompt);
        replyPrefix = interp(replyPrefix);
        microsoftClientId = interp(microsoftClientId);
        serverHost = interp(serverHost);
    }

    private String interp(String value) {
        if (value == null) return "";
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            if (env == null) {
                log.warn("环境变量 {} 未设置，替换为空串", m.group(1));
                env = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(env));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static long num(Map<String, Object> map, String key, long def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }
}
