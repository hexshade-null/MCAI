package com.mcaibridge.core;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.auth.MicrosoftAuth;
import com.mcaibridge.auth.OfflineAuth;
import com.mcaibridge.config.BridgeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * 无头模式：不启动 GUI，直接连接服务器并在控制台运行（自动化测试用）。
 */
public final class HeadlessRunner {
    private static final Logger log = LoggerFactory.getLogger(HeadlessRunner.class);

    private HeadlessRunner() {
    }

    public static int run(BridgeConfig cfg) throws Exception {
        AuthResult auth = switch (cfg.authMethod) {
            case "microsoft" -> MicrosoftAuth.login(MicrosoftAuth::showDeviceCodeDialog);
            default -> OfflineAuth.login(cfg.botName);
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
        bot.setChatHandler(new ChatHandler(cfg, bot, brain));

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bot.shutdown();
            stop.countDown();
        }, "shutdown-hook"));
        bot.connect();

        System.out.println("[bridge] 无头模式运行中，Ctrl+C 退出。等待 @" + cfg.botName + " 触发…");
        stop.await();
        return 0;
    }
}
