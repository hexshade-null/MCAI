package com.mcaibridge.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时中英双语词表：t(key) 取词，setLang 后由 MainWindow 重建界面生效。
 */
public final class I18n {
    public static final String ZH = "zh";
    public static final String EN = "en";

    private static volatile String lang = ZH;
    private static final Map<String, Map<String, String>> TABLES = new LinkedHashMap<>();

    static {
        Map<String, String> zh = new LinkedHashMap<>();
        zh.put("app.title", "MCAI");
        zh.put("app.subtitle", "Minecraft AI 玩家 · 言出法随 · 皮肤 · 语音");
        zh.put("btn.quickConnect", "快速连接");
        zh.put("btn.newPlayer", "+ 新建玩家");
        zh.put("btn.saveConfig", "保存配置");
        zh.put("btn.settings", "设置");
        zh.put("btn.connect", "连接");
        zh.put("btn.disconnect", "断开");
        zh.put("btn.edit", "编辑");
        zh.put("btn.skin", "皮肤");
        zh.put("btn.delete", "删除");
        zh.put("btn.save", "保存");
        zh.put("btn.cancel", "取消");
        zh.put("btn.browse", "浏览…");
        zh.put("btn.manageServers", "管理服务器…");
        zh.put("status.offline", "离线");
        zh.put("status.online", "在线");
        zh.put("status.connecting", "连接中…");
        zh.put("status.notConnected", "未连接");
        zh.put("log.title", "运行日志");
        zh.put("log.ready", "就绪。点击卡片上的“连接”让 AI 玩家进入服务器。");
        zh.put("profile.title", "AI 玩家档案");
        zh.put("profile.name", "玩家名字:");
        zh.put("profile.server", "服务器:");
        zh.put("profile.serverCustom", "自定义…");
        zh.put("profile.host", "地址:");
        zh.put("profile.port", "端口:");
        zh.put("profile.auth", "登录方式:");
        zh.put("profile.apiKey", "API Key:");
        zh.put("profile.model", "AI 模型:");
        zh.put("profile.baseUrl", "Base URL:");
        zh.put("profile.baseUrlHint", "留空用全局地址");
        zh.put("profile.skinFile", "皮肤文件:");
        zh.put("profile.skinModel", "皮肤模型:");
        zh.put("profile.svc", "启用语音 (SVC)");
        zh.put("profile.preview", "皮肤预览:");
        zh.put("profile.emptyName", "玩家名字不能为空");
        zh.put("server.title", "服务器列表");
        zh.put("server.name", "名称:");
        zh.put("server.host", "地址:");
        zh.put("server.port", "端口:");
        zh.put("server.add", "+ 添加");
        zh.put("settings.title", "总设置");
        zh.put("settings.voice", "语音 (Simple Voice Chat 中继)");
        zh.put("settings.voiceEnabled", "启用语音模块");
        zh.put("settings.voicePort", "bridge 端口:");
        zh.put("settings.voiceToken", "令牌:");
        zh.put("settings.asr", "语音识别 ASR");
        zh.put("settings.tts", "语音合成 TTS");
        zh.put("settings.provider", "provider:");
        zh.put("settings.baseUrl", "base_url:");
        zh.put("settings.model", "model:");
        zh.put("settings.voiceSel", "音色:");
        zh.put("settings.apiKey", "API Key:");
        zh.put("settings.appearance", "外观");
        zh.put("settings.theme", "主题:");
        zh.put("settings.themeDark", "深色");
        zh.put("settings.themeLight", "浅色");
        zh.put("settings.themeAuto", "跟随系统");
        zh.put("settings.language", "语言 / Language:");
        zh.put("settings.saved", "设置已保存（部分项重启连接后生效）");
        TABLES.put(ZH, zh);

        Map<String, String> en = new LinkedHashMap<>();
        en.put("app.title", "MCAI");
        en.put("app.subtitle", "Minecraft AI Player · Intent Actions · Skin · Voice");
        en.put("btn.quickConnect", "Quick Connect");
        en.put("btn.newPlayer", "+ New Player");
        en.put("btn.saveConfig", "Save Config");
        en.put("btn.settings", "Settings");
        en.put("btn.connect", "Connect");
        en.put("btn.disconnect", "Disconnect");
        en.put("btn.edit", "Edit");
        en.put("btn.skin", "Skin");
        en.put("btn.delete", "Delete");
        en.put("btn.save", "Save");
        en.put("btn.cancel", "Cancel");
        en.put("btn.browse", "Browse…");
        en.put("btn.manageServers", "Manage Servers…");
        en.put("status.offline", "Offline");
        en.put("status.online", "Online");
        en.put("status.connecting", "Connecting…");
        en.put("status.notConnected", "Not connected");
        en.put("log.title", "Logs");
        en.put("log.ready", "Ready. Click \"Connect\" on a card to let the AI player join the server.");
        en.put("profile.title", "AI Player Profile");
        en.put("profile.name", "Player name:");
        en.put("profile.server", "Server:");
        en.put("profile.serverCustom", "Custom…");
        en.put("profile.host", "Host:");
        en.put("profile.port", "Port:");
        en.put("profile.auth", "Auth:");
        en.put("profile.apiKey", "API Key:");
        en.put("profile.model", "AI model:");
        en.put("profile.baseUrl", "Base URL:");
        en.put("profile.baseUrlHint", "empty = global URL");
        en.put("profile.skinFile", "Skin file:");
        en.put("profile.skinModel", "Skin model:");
        en.put("profile.svc", "Enable voice (SVC)");
        en.put("profile.preview", "Skin preview:");
        en.put("profile.emptyName", "Player name cannot be empty");
        en.put("server.title", "Server List");
        en.put("server.name", "Name:");
        en.put("server.host", "Host:");
        en.put("server.port", "Port:");
        en.put("server.add", "+ Add");
        en.put("settings.title", "Settings");
        en.put("settings.voice", "Voice (Simple Voice Chat relay)");
        en.put("settings.voiceEnabled", "Enable voice module");
        en.put("settings.voicePort", "Bridge port:");
        en.put("settings.voiceToken", "Token:");
        en.put("settings.asr", "Speech-to-text (ASR)");
        en.put("settings.tts", "Text-to-speech (TTS)");
        en.put("settings.provider", "provider:");
        en.put("settings.baseUrl", "base_url:");
        en.put("settings.model", "model:");
        en.put("settings.voiceSel", "voice:");
        en.put("settings.apiKey", "API Key:");
        en.put("settings.appearance", "Appearance");
        en.put("settings.theme", "Theme:");
        en.put("settings.themeDark", "Dark");
        en.put("settings.themeLight", "Light");
        en.put("settings.themeAuto", "Auto (system)");
        en.put("settings.language", "Language / 语言:");
        en.put("settings.saved", "Settings saved (some take effect after reconnect)");
        TABLES.put(EN, en);
    }

    private I18n() {
    }

    public static String lang() {
        return lang;
    }

    public static void setLang(String l) {
        lang = EN.equals(l) ? EN : ZH;
    }

    public static String t(String key) {
        Map<String, String> table = TABLES.get(lang);
        String v = table != null ? table.get(key) : null;
        if (v == null) v = TABLES.get(ZH).get(key);
        return v != null ? v : key;
    }
}
