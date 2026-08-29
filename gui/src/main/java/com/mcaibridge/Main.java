package com.mcaibridge;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.core.HeadlessRunner;
import com.mcaibridge.core.ProbeRunner;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 入口。
 * 用法: java -jar mcaibridge-1.0.0-all.jar [--config config.yml] [--profile 名称] [--headless] [--probe]
 * 默认 GUI 模式；--headless 无控制台模式；--probe 纯协议自检（测试降级路径）；--profile 选择 players[] 档案。
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config.yml");
        boolean headless = false;
        boolean probe = false;
        String profileName = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 < args.length) configPath = Path.of(args[++i]);
                }
                case "--profile" -> {
                    if (i + 1 < args.length) profileName = args[++i];
                }
                case "--headless" -> headless = true;
                case "--probe" -> probe = true;
                default -> rest.add(args[i]);
            }
        }
        if (!rest.isEmpty()) {
            log.warn("忽略未知参数: {}", rest);
        }

        ensureConfigExists(configPath);
        BridgeConfig cfg = BridgeConfig.load(configPath);

        if (probe) {
            System.exit(ProbeRunner.run(cfg.merge(cfg.profile(profileName))));
        } else if (headless) {
            System.exit(HeadlessRunner.run(cfg.merge(cfg.profile(profileName))));
        } else {
            // GUI 模式：Main 是普通类（非 Application 子类），保证 fat jar 可直接 java -jar 启动
            Application.launch(com.mcaibridge.gui.MainWindow.class, args);
        }
    }

    /** 配置文件不存在时，从 classpath 的 config.template.yml 复制一份。 */
    private static void ensureConfigExists(Path configPath) throws IOException {
        if (Files.exists(configPath)) return;
        try (InputStream in = Main.class.getResourceAsStream("/config.template.yml")) {
            if (in != null) {
                Files.copy(in, configPath);
                log.info("已从模板创建配置文件: {}", configPath.toAbsolutePath());
            } else {
                log.warn("未找到内置模板，将使用默认配置运行");
            }
        }
    }
}
