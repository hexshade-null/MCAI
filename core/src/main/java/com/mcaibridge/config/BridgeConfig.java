package com.mcaibridge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桥接配置模型：全局段 + 多玩家档案（players[]，向后兼容旧单档案字段）。
 * 支持 YAML + ${ENV_VAR} 环境变量插值。
 */
public class BridgeConfig {
    private static final Logger log = LoggerFactory.getLogger(BridgeConfig.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** 单个 AI 玩家档案（GUI 卡片列表 / --profile 的数据源）。 */
    public static class PlayerProfile {
        public String name = "BridgeBot";
        public String auth = "offline";            // offline | microsoft
        public String microsoftClientId = "";
        public String serverHost = "localhost";
        public int serverPort = 25565;
        public String aiModel = "glm-5.3-flash";
        public String aiApiKey = "";
        public String skinFile = "";               // 64x64/64x32 PNG；空=无皮肤
        public String skinModel = "classic";       // classic | slim

        public static PlayerProfile fromMap(Map<String, Object> m) {
            PlayerProfile p = new PlayerProfile();
            p.name = str(m, "name", p.name);
            p.auth = str(m, "auth", p.auth);
            p.microsoftClientId = str(m, "microsoft_client_id", p.microsoftClientId);
            p.serverHost = str(m, "host", p.serverHost);
            p.serverPort = (int) num(m, "port", p.serverPort);
            p.aiModel = str(m, "model", p.aiModel);
            p.aiApiKey = str(m, "api_key", p.aiApiKey);
            p.skinFile = str(m, "skin_file", p.skinFile);
            p.skinModel = str(m, "skin_model", p.skinModel);
            return p;
        }
    }

    // 兼容旧字段：作为默认档案的镜像（由 merge() 填充后供 MCBot/ChatHandler 等读取）
    public String botName = "BridgeBot";
    public String authMethod = "offline";
    public String microsoftClientId = "";
    public String microsoftTokenFile = System.getProperty("user.home") + "/.mcaibridge/microsoft-token.json";
    public String serverHost = "localhost";
    public int serverPort = 25565;
    public String aiBaseUrl = "https://api.z.ai/api/paas/v4/chat/completions";
    public String aiModel = "glm-5.3-flash";
    public String aiApiKey = "";
    public String aiSystemPrompt = "你是Minecraft服务器里的AI玩家，用中文简短回答（一两句话），语气友好自然。";
    public String replyPrefix = "[%s] ";
    public String replyVia = "chat";                 // chat | plugin | both（plugin=经伴生插件广播）
    public int aiTimeoutSeconds = 30;
    public int reconnectMaxAttempts = 5;
    public String probeName = "TestPlayer";
    public long probeTimeoutMs = 25000;

    // skin 上传（paper 插件端点）
    public String skinUploadUrl = "http://localhost:8788/mcai/skin";
    public String skinToken = "changeme";
    /** 生效的皮肤文件/模型：由 merge() 从档案填充（GUI/headless 直接读取）。 */
    public String skinFile = "";
    public String skinModel = "classic";
    // voice（bridge 侧 HTTP 服务）
    public boolean voiceEnabled = false;
    public int voiceServerPort = 8787;
    public String voiceToken = "changeme";
    public String asrProvider = "mock";            // mock | whisper-http
    public String asrBaseUrl = "https://api.groq.com/openai/v1";
    public String asrModel = "whisper-large-v3";
    public String asrApiKey = "";
    public String ttsProvider = "zai";             // zai | edge | off
    public String ttsBaseUrl = "https://api.z.ai/api/paas/v4";
    public String ttsModel = "glm-tts";
    public String ttsVoice = "tongtong";           // zai: tongtong/chuichui/xiaochen...；edge: zh-CN-XiaoxiaoNeural
    public String ttsApiKey = "";                  // 留空时回落使用 ai.api_key
    public boolean ttsFallbackText = true;

    public List<PlayerProfile> players = new ArrayList<>();

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
        cfg.normalizeProfiles();
        return cfg;
    }

    /** 按 --profile 名字取档案；找不到或未指定则取第一个。 */
    public PlayerProfile profile(String name) {
        for (PlayerProfile p : players) {
            if (p.name.equals(name)) return p;
        }
        return players.isEmpty() ? new PlayerProfile() : players.get(0);
    }

    /** 把档案字段合并进一份配置副本，供 MCBot/ChatHandler/AIBrain 等现有类直接使用。 */
    public BridgeConfig merge(PlayerProfile p) {
        BridgeConfig c = cloneCfg();
        if (p != null) {
            c.botName = p.name;
            c.authMethod = p.auth;
            c.microsoftClientId = p.microsoftClientId;
            c.serverHost = p.serverHost;
            c.serverPort = p.serverPort;
            c.aiModel = p.aiModel;
            c.aiApiKey = p.aiApiKey;
            c.skinFile = p.skinFile;
            c.skinModel = p.skinModel;
        }
        return c;
    }

    private BridgeConfig cloneCfg() {
        BridgeConfig c = new BridgeConfig();
        c.aiBaseUrl = aiBaseUrl;
        c.aiSystemPrompt = aiSystemPrompt;
        c.replyPrefix = replyPrefix;
        c.replyVia = replyVia;
        c.aiTimeoutSeconds = aiTimeoutSeconds;
        c.reconnectMaxAttempts = reconnectMaxAttempts;
        c.probeName = probeName;
        c.probeTimeoutMs = probeTimeoutMs;
        c.skinUploadUrl = skinUploadUrl;
        c.skinToken = skinToken;
        c.voiceEnabled = voiceEnabled;
        c.voiceServerPort = voiceServerPort;
        c.voiceToken = voiceToken;
        c.asrProvider = asrProvider;
        c.asrBaseUrl = asrBaseUrl;
        c.asrModel = asrModel;
        c.asrApiKey = asrApiKey;
        c.ttsProvider = ttsProvider;
        c.ttsBaseUrl = ttsBaseUrl;
        c.ttsModel = ttsModel;
        c.ttsVoice = ttsVoice;
        c.ttsApiKey = ttsApiKey;
        c.ttsFallbackText = ttsFallbackText;
        c.botName = botName;
        c.authMethod = authMethod;
        c.microsoftClientId = microsoftClientId;
        c.microsoftTokenFile = microsoftTokenFile;
        c.serverHost = serverHost;
        c.serverPort = serverPort;
        c.aiModel = aiModel;
        c.aiApiKey = aiApiKey;
        return c;
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> root) {
        Map<String, Object> bot = (Map<String, Object>) root.get("bot");
        Map<String, Object> server = (Map<String, Object>) root.get("server");
        Map<String, Object> ai = (Map<String, Object>) root.get("ai");
        Map<String, Object> conn = (Map<String, Object>) root.get("connection");
        Map<String, Object> probe = (Map<String, Object>) root.get("probe");
        Map<String, Object> skin = (Map<String, Object>) root.get("skin");
        Map<String, Object> voice = (Map<String, Object>) root.get("voice");
        if (bot != null) {
            botName = str(bot, "name", botName);
            authMethod = str(bot, "auth", authMethod);
            microsoftClientId = str(bot, "microsoft_client_id", microsoftClientId);
            String tf = str(bot, "microsoft_token_file", "");
            if (!tf.isBlank()) microsoftTokenFile = tf.replaceFirst("^~", System.getProperty("user.home"));
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
            replyVia = str(ai, "reply_via", replyVia);
            aiTimeoutSeconds = (int) num(ai, "timeout_seconds", aiTimeoutSeconds);
        }
        if (conn != null) {
            reconnectMaxAttempts = (int) num(conn, "reconnect_max_attempts", reconnectMaxAttempts);
        }
        if (probe != null) {
            probeName = str(probe, "name", probeName);
            probeTimeoutMs = num(probe, "timeout_ms", probeTimeoutMs);
        }
        if (skin != null) {
            skinUploadUrl = str(skin, "upload_url", skinUploadUrl);
            skinToken = str(skin, "token", skinToken);
            skinFile = str(skin, "file", skinFile);
            skinModel = str(skin, "model", skinModel);
        }
        if (voice != null) {
            voiceEnabled = bool(voice, "enabled", voiceEnabled);
            voiceServerPort = (int) num(voice, "server_port", voiceServerPort);
            voiceToken = str(voice, "token", voiceToken);
            Map<String, Object> asr = (Map<String, Object>) voice.get("asr");
            Map<String, Object> tts = (Map<String, Object>) voice.get("tts");
            if (asr != null) {
                asrProvider = str(asr, "provider", asrProvider);
                asrBaseUrl = str(asr, "base_url", asrBaseUrl);
                asrModel = str(asr, "model", asrModel);
                asrApiKey = str(asr, "api_key", asrApiKey);
            }
            if (tts != null) {
                ttsProvider = str(tts, "provider", ttsProvider);
                ttsBaseUrl = str(tts, "base_url", ttsBaseUrl);
                ttsModel = str(tts, "model", ttsModel);
                ttsVoice = str(tts, "voice", ttsVoice);
                ttsApiKey = str(tts, "api_key", ttsApiKey);
                ttsFallbackText = bool(tts, "fallback_text", ttsFallbackText);
            }
        }
        Object ps = root.get("players");
        if (ps instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pm = (Map<String, Object>) m;
                    PlayerProfile p = PlayerProfile.fromMap(pm);
                    // 未写字段时回退全局旧字段
                    if (pm.get("host") == null) p.serverHost = serverHost;
                    if (pm.get("port") == null) p.serverPort = serverPort;
                    if (pm.get("model") == null) p.aiModel = aiModel;
                    if (pm.get("api_key") == null) p.aiApiKey = aiApiKey;
                    if (pm.get("auth") == null) p.auth = authMethod;
                    players.add(p);
                }
            }
        }
    }

    /** 无 players[] 时由旧单档案字段合成一个档案，保证向后兼容。 */
    private void normalizeProfiles() {
        if (players.isEmpty()) {
            PlayerProfile p = new PlayerProfile();
            p.name = botName;
            p.auth = authMethod;
            p.microsoftClientId = microsoftClientId;
            p.serverHost = serverHost;
            p.serverPort = serverPort;
            p.aiModel = aiModel;
            p.aiApiKey = aiApiKey;
            p.skinFile = skinFile;
            p.skinModel = skinModel;
            players.add(p);
        }
    }

    private void interpolate() {
        aiBaseUrl = interp(aiBaseUrl);
        aiApiKey = interp(aiApiKey);
        aiSystemPrompt = interp(aiSystemPrompt);
        replyPrefix = interp(replyPrefix);
        microsoftClientId = interp(microsoftClientId);
        serverHost = interp(serverHost);
        asrApiKey = interp(asrApiKey);
        ttsApiKey = interp(ttsApiKey);
        asrBaseUrl = interp(asrBaseUrl);
        skinToken = interp(skinToken);
        voiceToken = interp(voiceToken);
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

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : def;
    }
}
