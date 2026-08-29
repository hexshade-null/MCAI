package com.mcaibridge.paper;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * MCAI Bridge 伴生插件：
 * 1) 皮肤：接收 bridge 上传的皮肤，玩家登录时注入 textures 属性（离线服皮肤）
 * 2) 语音：Simple Voice Chat 中继 —— 玩家语音 → bridge(ASR→AI→TTS) → 广播播放
 */
public class BridgePlugin extends JavaPlugin {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger("MCAIBridge");
    private final BridgePlugin thisBridge = this;

    private ApiServer apiServer;
    private SkinService skinService;
    private Shutdownable voiceRelay;

    /** 语音中继的关闭接口（避免 BridgePlugin 直接依赖 SVC 类型）。 */
    interface Shutdownable {
        void shutdown();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        int requestedPort = cfg.getInt("api.port", 8788);
        int port = findFreePort(requestedPort);
        String token = cfg.getString("api.token", "changeme");
        skinService = new SkinService(this, token, port);

        getServer().getPluginManager().registerEvents(new SkinLoginListener(skinService), this);

        try {
            apiServer = new ApiServer(this, skinService, port);
            apiServer.start();
        } catch (IOException e) {
            log.error("API 服务启动失败: {}", e.toString());
        }

        // 语音：依赖 Simple Voice Chat 插件。全部走反射，避免无 SVC 环境下的类加载失败
        try {
            var svcPlugin = getServer().getPluginManager().getPlugin("voicechat");
            if (svcPlugin == null) {
                log.warn("未检测到 Simple Voice Chat 插件，语音中继不可用（皮肤功能不受影响）");
            } else {
                Class<?> svcClass = Class.forName("de.maxhenkel.voicechat.api.BukkitVoicechatService");
                Object service = getServer().getServicesManager().load(svcClass);
                Class<?> vrClass = Class.forName("com.mcaibridge.paper.VoiceRelay");
                Object relay = vrClass.getDeclaredConstructor(org.bukkit.plugin.java.JavaPlugin.class).newInstance(this);
                voiceRelay = (Shutdownable) relay;
                Object pluginTyped = vrClass.cast(relay);
                svcClass.getMethod("registerPlugin", Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin"))
                        .invoke(service, pluginTyped);
                log.info("已注册 Simple Voice Chat 语音中继 addon");
            }
        } catch (Throwable t) {
            log.warn("语音中继初始化失败: {}", t.toString());
        }
        log.info("MCAI Bridge 伴生插件已启用（API 端口 {}）", port);
    }

    @Override
    public void onDisable() {
        if (apiServer != null) apiServer.stop();
        if (voiceRelay != null) voiceRelay.shutdown();
    }
    SkinService skinService() {
        return skinService;
    }

    /** 找一个可用端口：优先配置端口，被占用则向后搜索。 */
    static int findFreePort(int preferred) {
        for (int p = preferred; p < preferred + 20; p++) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.bind(new InetSocketAddress(p));
                return p;
            } catch (IOException ignored) {
            }
        }
        return preferred;
    }
}
