package com.mcaibridge.core;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.auth.MicrosoftAuth;
import com.mcaibridge.auth.OfflineAuth;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.skin.SkinManager;
import com.mcaibridge.voice.VoiceEngines;
import com.mcaibridge.voice.VoiceServer;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * 无头模式：不启动 GUI，直接连接服务器并在控制台运行（自动化测试用）。
 * 启动时：上传皮肤到 paper 插件 → 连接 → 按配置开启语音服务。
 */
public final class HeadlessRunner {
    private static final Logger log = LoggerFactory.getLogger(HeadlessRunner.class);

    private HeadlessRunner() {
    }

    public static int run(BridgeConfig cfg) throws Exception {
        GameProfile.Property skinProp = buildSkinProperty(cfg);
        uploadSkin(cfg);

        AuthResult auth = switch (cfg.authMethod) {
            case "microsoft" -> MicrosoftAuth.login(MicrosoftAuth::showDeviceCodeDialog, java.nio.file.Path.of(cfg.microsoftTokenFile));
            default -> OfflineAuth.login(cfg.botName, skinProp);
        };

        MCBot bot = new MCBot(cfg, auth, new MCBot.Listener() {
            @Override
            public void onLog(String line) {
                System.out.println("[bridge] " + line);
            }

            @Override
            public void onStateChange(MCBot.State state, String detail) {
                System.out.println("[bridge] 状态: " + state + " (" + detail + ")");
            }
        });
        AIBrain brain = new AIBrain(cfg);
        PlayerController controller = new PlayerController(cfg, bot);
        controller.start();
        ChatHandler chatHandler = new ChatHandler(cfg, bot, brain);
        chatHandler.setController(controller);
        bot.setChatHandler(chatHandler);
        bot.setController(controller);

        VoiceServer voiceServer = startVoiceIfEnabled(cfg, brain);

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (voiceServer != null) voiceServer.stop();
            bot.shutdown();
            stop.countDown();
        }, "shutdown-hook"));
        bot.connect();

        System.out.println("[bridge] 无头模式运行中，Ctrl+C 退出。等待 @" + cfg.botName + " 触发…");
        stop.await();
        return 0;
    }

    private static GameProfile.Property buildSkinProperty(BridgeConfig cfg) {
        if (cfg.skinFile == null || cfg.skinFile.isBlank()) return null;
        try {
            byte[] png = SkinManager.loadValidated(Path.of(cfg.skinFile));
            String uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + cfg.botName).getBytes()).toString();
            String imageUrl = cfg.skinUploadUrl.replaceAll("/mcai/skin$", "/mcai/skinimg/" + cfg.botName + ".png");
            String value = SkinManager.buildTexturesValue(png, cfg.skinModel, cfg.botName, imageUrl, uuid);
            return new GameProfile.Property("textures", value);
        } catch (Exception e) {
            log.warn("皮肤文件不可用({}): {}", cfg.skinFile, e.toString());
            return null;
        }
    }

    private static void uploadSkin(BridgeConfig cfg) {
        if (cfg.skinFile == null || cfg.skinFile.isBlank()) return;
        try {
            byte[] png = SkinManager.loadValidated(Path.of(cfg.skinFile));
            SkinManager.upload(cfg.skinUploadUrl, cfg.skinToken, cfg.botName, cfg.skinModel, png);
        } catch (Exception e) {
            log.warn("皮肤读取失败({}): {}", cfg.skinFile, e.toString());
        }
    }

    static VoiceServer startVoiceIfEnabled(BridgeConfig cfg, AIBrain brain) {
        if (!cfg.voiceEnabled) return null;
        try {
            VoiceServer server = new VoiceServer(cfg, VoiceEngines.asr(cfg), VoiceEngines.tts(cfg));
            server.start();
            return server;
        } catch (Exception e) {
            log.warn("语音服务启动失败: {}", e.toString());
            return null;
        }
    }
}
