package com.mcaibridge.gui;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.auth.MicrosoftAuth;
import com.mcaibridge.auth.OfflineAuth;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.core.BotFactory;
import com.mcaibridge.core.MCBot;
import com.mcaibridge.skin.SkinManager;
import com.mcaibridge.voice.VoiceEngines;
import com.mcaibridge.voice.VoiceServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCAI 主窗口：Logo 区 + 快捷操作 + AI 玩家卡片列表 + 可折叠日志。
 * iOS 17 视觉（明暗主题/中英双语运行时切换）；语音按角色 svc 开关门控。
 */
public class MainWindow extends Application {
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private BridgeConfig baseCfg;
    private Path configPath = Path.of("config.yml");
    private Stage stage;
    private LogPanel logPanel;
    private FlowPane cardsPane;
    private final Map<String, BotFactory.Handles> running = new HashMap<>();
    private VoiceServer voiceServer;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        List<String> args = getParameters().getRaw();
        for (int i = 0; i < args.size(); i++) {
            if ("--config".equals(args.get(i)) && i + 1 < args.size()) {
                configPath = Path.of(args.get(++i));
            }
        }
        try {
            baseCfg = BridgeConfig.load(configPath);
        } catch (Exception e) {
            baseCfg = new BridgeConfig();
            log.warn("配置加载失败，使用默认: {}", e.toString());
        }
        I18n.setLang(Prefs.lang());

        BorderPane root = new BorderPane();
        root.getStyleClass().add("theme-" + Prefs.effectiveTheme());
        root.setTop(buildTop());
        root.setCenter(buildCards());
        root.setBottom(buildLog());

        Scene scene = new Scene(root, 800, 660);
        var css = getClass().getResource("/pcl.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle(I18n.t("app.title") + " — Minecraft AI");
        stage.setScene(scene);
        stage.show();
        logPanel.append(I18n.t("log.ready"));

        stage.setOnCloseRequest(e -> shutdownAll());
    }

    private VBox buildTop() {
        Label title = new Label(I18n.t("app.title"));
        title.getStyleClass().add("logo-title");
        Label sub = new Label(I18n.t("app.subtitle"));
        sub.getStyleClass().add("logo-sub");
        HBox logo = new HBox(12, title, sub);
        logo.getStyleClass().add("logo-bar");
        HBox.setHgrow(sub, Priority.ALWAYS);

        Button quick = new Button(I18n.t("btn.quickConnect"));
        quick.getStyleClass().add("btn-primary");
        quick.setOnAction(e -> {
            if (!baseCfg.players.isEmpty()) connect(baseCfg.players.get(0));
        });
        Button add = new Button(I18n.t("btn.newPlayer"));
        add.getStyleClass().add("btn-ghost");
        add.setOnAction(e -> editDialog(new PlayerProfile(), true));
        Button settings = new Button(I18n.t("btn.settings"));
        settings.getStyleClass().add("btn-ghost");
        settings.setOnAction(e -> openSettings());
        Button save = new Button(I18n.t("btn.saveConfig"));
        save.getStyleClass().add("btn-ghost");
        save.setOnAction(e -> saveConfig());
        HBox quickBar = new HBox(10, quick, add, settings, save);
        quickBar.getStyleClass().add("quick-bar");
        return new VBox(logo, quickBar);
    }

    private ScrollPane buildCards() {
        cardsPane = new FlowPane();
        cardsPane.getStyleClass().add("flow-cards");
        cardsPane.setHgap(14);
        cardsPane.setVgap(14);
        rebuildCards();
        ScrollPane sp = new ScrollPane(cardsPane);
        sp.setFitToWidth(true);
        return sp;
    }

    private void rebuildCards() {
        cardsPane.getChildren().clear();
        for (PlayerProfile p : baseCfg.players) {
            cardsPane.getChildren().add(new ProfileCard(p, new ProfileCard.Callback() {
                @Override
                public void onConnect(PlayerProfile p) {
                    BotFactory.Handles h = running.get(p.name);
                    if (h != null && h.bot().getState() == MCBot.State.CONNECTED) {
                        disconnect(p);
                    } else {
                        connect(p);
                    }
                }

                @Override
                public void onDisconnect(PlayerProfile p) {
                    disconnect(p);
                }

                @Override
                public void onEdit(PlayerProfile p) {
                    editDialog(p, false);
                }

                @Override
                public void onDelete(PlayerProfile p) {
                    disconnect(p);
                    baseCfg.players.remove(p);
                    rebuildCards();
                }
            }));
        }
    }

    private TitledPane buildLog() {
        logPanel = new LogPanel();
        TitledPane pane = new TitledPane(I18n.t("log.title"), logPanel);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        VBox.setVgrow(pane, Priority.ALWAYS);
        VBox box = new VBox(pane);
        box.setPadding(new Insets(6));
        return pane;
    }

    // ---- 设置 / 主题 / 语言 ----

    private void openSettings() {
        SettingsDialog d = new SettingsDialog(baseCfg);
        d.showAndWait();
        if (d.isAppearanceChanged()) {
            applyThemeAndLanguage();
        }
        if (d.isVoiceChanged()) {
            restartVoiceServer();
        }
    }

    /** 主题/语言变更：重建整个场景。 */
    private void applyThemeAndLanguage() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("theme-" + Prefs.effectiveTheme());
        root.setTop(buildTop());
        root.setCenter(buildCards());
        root.setBottom(buildLog());
        Scene scene = new Scene(root, stage.getScene().getWidth(), stage.getScene().getHeight());
        var css = getClass().getResource("/pcl.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle(I18n.t("app.title") + " — Minecraft AI");
        stage.setScene(scene);
    }

    // ---- 连接管理 ----

    private void connect(PlayerProfile p) {
        if (running.containsKey(p.name)) return;
        BridgeConfig cfg = baseCfg.merge(p);
        logPanel.append("[" + p.name + "] " + cfg.serverHost + ":" + cfg.serverPort + " …");
        Thread t = new Thread(() -> {
            try {
                if (cfg.skinFile != null && !cfg.skinFile.isBlank()) {
                    try {
                        byte[] png = SkinManager.loadValidated(Path.of(cfg.skinFile));
                        SkinManager.upload(cfg.skinUploadUrl, cfg.skinToken, cfg.botName, cfg.skinModel, png);
                    } catch (Exception e) {
                        logPanel.append("[" + p.name + "] skin: " + e.getMessage());
                    }
                }
                AuthResult auth = "microsoft".equals(cfg.authMethod)
                        ? MicrosoftAuth.login(MicrosoftAuth::showDeviceCodeDialog, java.nio.file.Path.of(cfg.microsoftTokenFile))
                        : OfflineAuth.login(cfg.botName);
                Platform.runLater(() -> logPanel.append("[" + p.name + "] auth: " + auth.source()));

                MCBot bot = new MCBot(cfg, auth, new MCBot.Listener() {
                    @Override
                    public void onLog(String line) {
                        Platform.runLater(() -> logPanel.append("[" + p.name + "] " + line));
                    }

                    @Override
                    public void onStateChange(MCBot.State state, String detail) {
                        Platform.runLater(() -> updateCard(p.name, state, detail));
                    }
                });
                BotFactory.Handles h = BotFactory.assemble(cfg, bot);
                running.put(p.name, h);
                ensureVoiceServer(cfg);
                bot.connect();
            } catch (Exception ex) {
                log.warn("连接失败: {}", ex.toString(), ex);
                Platform.runLater(() -> logPanel.append("[" + p.name + "] " + ex.getMessage()));
                running.remove(p.name);
            }
        }, "mcai-connect-" + p.name);
        t.setDaemon(true);
        t.start();
    }

    private void disconnect(PlayerProfile p) {
        BotFactory.Handles h = running.remove(p.name);
        if (h != null) h.bot().shutdown();
    }

    private void updateCard(String name, MCBot.State state, String detail) {
        for (var node : cardsPane.getChildren()) {
            if (node instanceof ProfileCard card && card.profile().name.equals(name)) {
                card.setState(state, detail);
            }
        }
    }

    /** 语音门控：全局开启 + 至少一个在线角色 svc=true。 */
    private void ensureVoiceServer(BridgeConfig merged) {
        if (!merged.voiceEnabled || !merged.svc || voiceServer != null) return;
        try {
            voiceServer = new VoiceServer(merged, VoiceEngines.asr(merged), VoiceEngines.tts(merged));
            voiceServer.start();
            logPanel.append("[voice] port " + merged.voiceServerPort);
        } catch (Exception e) {
            logPanel.append("[voice] " + e.getMessage());
        }
    }

    private void restartVoiceServer() {
        if (voiceServer != null) {
            voiceServer.stop();
            voiceServer = null;
        }
        for (Map.Entry<String, BotFactory.Handles> e : running.entrySet()) {
            BridgeConfig cfg = baseCfg.merge(baseCfg.profile(e.getKey()));
            ensureVoiceServer(cfg);
            break; // 用第一个在线角色的配置起服务即可（端口/引擎是全局的）
        }
        logPanel.append(I18n.t("settings.saved"));
    }

    // ---- 对话框与持久化 ----

    private void editDialog(PlayerProfile p, boolean isNew) {
        ProfileDialog dialog = new ProfileDialog(baseCfg, p);
        dialog.showAndWait();
        if (dialog.isSaved()) {
            if (isNew) baseCfg.players.add(p);
            rebuildCards();
        }
    }

    /** 完整写出新格式配置（servers[] + players[] + voice + survival），保持人类可读。 */
    private void saveConfig() {
        try {
            StringBuilder sb = new StringBuilder();
            PlayerProfile first = baseCfg.players.isEmpty() ? new PlayerProfile() : baseCfg.players.get(0);
            sb.append("bot:\n  name: \"").append(first.name).append("\"\n");
            sb.append("server:\n  host: \"").append(first.serverHost)
                    .append("\"\n  port: ").append(first.serverPort).append("\n");
            if (!baseCfg.servers.isEmpty()) {
                sb.append("servers:\n");
                for (BridgeConfig.ServerEntry s : baseCfg.servers) {
                    sb.append("  - { name: \"").append(s.name.replace("\"", "'")).append("\", host: \"")
                            .append(s.host).append("\", port: ").append(s.port).append(" }\n");
                }
            }
            sb.append("ai:\n  model: \"").append(first.aiModel)
                    .append("\"\n  api_key: \"").append(first.aiApiKey.replace("\"", "\\\"")).append("\"\n");
            sb.append("skin:\n  upload_url: \"").append(baseCfg.skinUploadUrl).append("\"\n  token: \"").append(baseCfg.skinToken).append("\"\n");
            sb.append("survival:\n  auto_respawn: ").append(baseCfg.autoRespawn)
                    .append("\n  auto_eat: ").append(baseCfg.autoEat)
                    .append("\n  eat_below_food: ").append(baseCfg.eatBelowFood)
                    .append("\n  dig_delay_ms: ").append(baseCfg.digDelayMs).append("\n");
            sb.append("voice:\n  enabled: ").append(baseCfg.voiceEnabled)
                    .append("\n  server_port: ").append(baseCfg.voiceServerPort)
                    .append("\n  token: \"").append(baseCfg.voiceToken).append("\"\n");
            sb.append("players:\n");
            for (PlayerProfile p : baseCfg.players) {
                sb.append("  - name: \"").append(p.name).append("\"\n");
                sb.append("    auth: \"").append(p.auth).append("\"\n");
                if (p.server != null && !p.server.isBlank()) {
                    sb.append("    server: \"").append(p.server.replace("\"", "'")).append("\"\n");
                }
                sb.append("    host: \"").append(p.serverHost).append("\"\n    port: ").append(p.serverPort).append("\n");
                sb.append("    model: \"").append(p.aiModel).append("\"\n");
                sb.append("    svc: ").append(p.svc).append("\n");
                sb.append("    skin_file: \"").append(p.skinFile.replace("\\", "\\\\")).append("\"\n");
                sb.append("    skin_model: \"").append(p.skinModel).append("\"\n");
            }
            Files.writeString(configPath, sb.toString());
            logPanel.append("config.yml ✓ " + configPath.toAbsolutePath());
        } catch (Exception e) {
            logPanel.append("save failed: " + e.getMessage());
        }
    }

    private void shutdownAll() {
        for (BotFactory.Handles h : running.values()) h.bot().shutdown();
        if (voiceServer != null) voiceServer.stop();
    }
}
