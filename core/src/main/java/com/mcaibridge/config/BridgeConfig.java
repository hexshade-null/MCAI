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

    /** 独立服务器条目：角色按名字引用，像 MC 客户端的服务器列表。 */
    public static class ServerEntry {
        public String name = "本地服务器";
        public String host = "localhost";
        public int port = 25565;

        public static ServerEntry fromMap(Map<String, Object> m) {
            ServerEntry s = new ServerEntry();
            s.name = str(m, "name", s.name);
            s.host = str(m, "host", s.host);
            s.port = (int) num(m, "port", s.port);
            return s;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 单个 AI 玩家档案（GUI 卡片列表 / --profile 的数据源）。 */
    public static class PlayerProfile {
        public String name = "BridgeBot";
        public String auth = "offline";            // offline | microsoft
        public String microsoftClientId = "";
        public String server = "";                 // 引用 servers[].name；空=直接用 host/port
        public String serverHost = "localhost";
        public int serverPort = 25565;
        public String aiModel = "glm-5.3-flash";
        public String aiApiKey = "";
        public String aiBaseUrl = "";              // OpenAI 兼容端点；空=用全局 ai.base_url
        public String skinFile = "";               // 64x64/64x32 PNG；空=无皮肤
        public String skinModel = "classic";       // classic | slim
        public boolean svc = false;                // 是否启用 Simple Voice Chat 语音（默认关，显式开启）

        public static PlayerProfile fromMap(Map<String, Object> m) {
            PlayerProfile p = new PlayerProfile();
            p.name = str(m, "name", p.name);
            p.auth = str(m, "auth", p.auth);
            p.microsoftClientId = str(m, "microsoft_client_id", p.microsoftClientId);
            p.server = str(m, "server", p.server);
            p.serverHost = str(m, "host", p.serverHost);
            p.serverPort = (int) num(m, "port", p.serverPort);
            p.aiModel = str(m, "model", p.aiModel);
            p.aiApiKey = str(m, "api_key", p.aiApiKey);
            p.aiBaseUrl = str(m, "base_url", p.aiBaseUrl);
            p.skinFile = str(m, "skin_file", p.skinFile);
            p.skinModel = str(m, "skin_model", p.skinModel);
            p.svc = bool(m, "svc", p.svc);
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
    /** 当前生效档案的语音开关（merge() 从 PlayerProfile.svc 填充）。 */
    public boolean svc = false;

    // 生存辅助（纯本地端）
    public boolean autoRespawn = true;      // 死亡自动重生
    public boolean autoEat = true;          // 低饥饿自动吃
    public int eatBelowFood = 10;           // 饥饿值低于该值触发进食（0-20）
    public int digDelayMs = 900;            // 挖掘 START→FINISH 间隔（空手近似值，可按工具调整）
    public boolean sprintKnockback = true;  // 攻击瞬间保持疾跑状态（原版疾跑击退）
    public boolean combatAuto = true;       // 自主战斗反应（被打反击/低血逃跑）
    public List<Integer> foodItemIds = new ArrayList<>(); // 可食用物品的协议数字 id（实测填充；空=内置表）

    public List<ServerEntry> servers = new ArrayList<>();
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
            if (p.aiBaseUrl != null && !p.aiBaseUrl.isBlank()) {
                c.aiBaseUrl = p.aiBaseUrl;         // 角色级端点覆盖全局
            }
            c.skinFile = p.skinFile;
            c.skinModel = p.skinModel;
            c.svc = p.svc;
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
        c.svc = svc;
        c.autoRespawn = autoRespawn;
        c.autoEat = autoEat;
        c.eatBelowFood = eatBelowFood;
        c.digDelayMs = digDelayMs;
        c.sprintKnockback = sprintKnockback;
        c.combatAuto = combatAuto;
        c.foodItemIds.addAll(foodItemIds);
        for (ServerEntry s : servers) {
            ServerEntry cs = new ServerEntry();
            cs.name = s.name;
            cs.host = s.host;
            cs.port = s.port;
            c.servers.add(cs);
        }
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
        Map<String, Object> survival = (Map<String, Object>) root.get("survival");
        if (survival != null) {
            autoRespawn = bool(survival, "auto_respawn", autoRespawn);
            autoEat = bool(survival, "auto_eat", autoEat);
            eatBelowFood = (int) num(survival, "eat_below_food", eatBelowFood);
            digDelayMs = (int) num(survival, "dig_delay_ms", digDelayMs);
            sprintKnockback = bool(survival, "sprint_knockback", sprintKnockback);
            combatAuto = bool(survival, "combat_auto", combatAuto);
            Object ids = survival.get("food_item_ids");
            if (ids instanceof List<?> il && !il.isEmpty()) {
                foodItemIds.clear();
                for (Object o : il) {
                    if (o instanceof Number n) foodItemIds.add(n.intValue());
                }
            }
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
        Object ss = root.get("servers");
        if (ss instanceof List<?> slist) {
            for (Object o : slist) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sm = (Map<String, Object>) m;
                    servers.add(ServerEntry.fromMap(sm));
                }
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

    /** 服务器引用解析 + 无 players[] 时由旧单档案字段合成，保证向后兼容。 */
    private void normalizeProfiles() {
        // 旧式 server: 段在未写 servers[] 时隐式成为默认服务器条目
        if (servers.isEmpty()) {
            ServerEntry s = new ServerEntry();
            s.name = "default";
            s.host = serverHost;
            s.port = serverPort;
            servers.add(s);
        }
        for (PlayerProfile p : players) {
            ServerEntry ref = findServer(p.server);
            if (ref != null) {
                p.serverHost = ref.host;
                p.serverPort = ref.port;
            } else if (p.server != null && !p.server.isBlank()) {
                log.warn("档案 {} 引用的服务器 \"{}\" 不在 servers 列表中，沿用 host/port", p.name, p.server);
            }
        }
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

    public ServerEntry findServer(String name) {
        if (name == null || name.isBlank()) return null;
        for (ServerEntry s : servers) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    private void interpolate() {
        for (ServerEntry s : servers) {
            s.host = interp(s.host);
        }
        for (PlayerProfile p : players) {
            p.aiBaseUrl = interp(p.aiBaseUrl);
            p.aiApiKey = interp(p.aiApiKey);
        }
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
