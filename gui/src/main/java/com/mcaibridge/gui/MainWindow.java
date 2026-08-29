package com.mcaibridge.gui;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.auth.MicrosoftAuth;
import com.mcaibridge.auth.OfflineAuth;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.core.AIBrain;
import com.mcaibridge.core.ChatHandler;
import com.mcaibridge.core.MCBot;
import com.mcaibridge.core.PlayerController;
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
import javafx.scene.layout.Pane;
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
 * PCL 风格主窗口：Logo 区 + 快速开始 + AI 玩家卡片列表 + 可折叠日志。
 * 支持多档案：每张卡片一个 AI 玩家，独立连接。
 */
public class MainWindow extends Application {
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private BridgeConfig baseCfg;
    private Path configPath = Path.of("config.yml");
    private LogPanel logPanel;
    private FlowPane cardsPane;
    private final Map<String, BotRuntime> running = new HashMap<>();
    private VoiceServer voiceServer;

    private static class BotRuntime {
        MCBot bot;
        ChatHandler chatHandler;
        AIBrain brain;
    }

    @Override
    public void start(Stage stage) throws Exception {
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

        BorderPane root = new BorderPane();
        root.setTop(buildTop());
        root.setCenter(buildCards());
        root.setBottom(buildLog());

        Scene scene = new Scene(root, 780, 640);
        var css = getClass().getResource("/pcl.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("MCAI Bridge - Minecraft AI 玩家");
        stage.setScene(scene);
        stage.show();
        logPanel.append("就绪。点击卡片上的“连接”让 AI 玩家进入服务器。");

        stage.setOnCloseRequest(e -> shutdownAll());
    }

    private VBox buildTop() {
        Label title = new Label("MCAI Bridge");
        title.getStyleClass().add("logo-title");
        Label sub = new Label("Minecraft AI 玩家接入工具 · 皮肤 · 语音");
        sub.getStyleClass().add("logo-sub");
        HBox logo = new HBox(12, title, sub);
        logo.getStyleClass().add("logo-bar");

        Button quick = new Button("快速连接");
        quick.getStyleClass().add("btn-primary");
        quick.setOnAction(e -> {
            if (!baseCfg.players.isEmpty()) connect(baseCfg.players.get(0));
        });
        Button add = new Button("+ 新建玩家");
        add.getStyleClass().add("btn-ghost");
        add.setOnAction(e -> {
            PlayerProfile p = new PlayerProfile();
            editDialog(p, true);
        });
        Button save = new Button("保存配置");
        save.getStyleClass().add("btn-ghost");
        save.setOnAction(e -> saveConfig());
        HBox quickBar = new HBox(10, quick, add, save);
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
                    BotRuntime rt = running.get(p.name);
                    if (rt != null && rt.bot.getState() == MCBot.State.CONNECTED) {
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
        TitledPane pane = new TitledPane("运行日志", logPanel);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        VBox.setVgrow(pane, Priority.ALWAYS);
        VBox box = new VBox(pane);
        box.setPadding(new Insets(6));
        return pane;
    }

    // ---- 连接管理 ----

    private void connect(PlayerProfile p) {
        if (running.containsKey(p.name)) return;
        BridgeConfig cfg = baseCfg.merge(p);
        logPanel.append("[" + p.name + "] 正在认证并连接 " + cfg.serverHost + ":" + cfg.serverPort + " …");
        Thread t = new Thread(() -> {
            try {
                if (cfg.skinFile != null && !cfg.skinFile.isBlank()) {
                    try {
                        byte[] png = SkinManager.loadValidated(Path.of(cfg.skinFile));
                        SkinManager.upload(cfg.skinUploadUrl, cfg.skinToken, cfg.botName, cfg.skinModel, png);
                    } catch (Exception e) {
                        logPanel.append("[" + p.name + "] 皮肤不可用: " + e.getMessage());
                    }
                }
                AuthResult auth = "microsoft".equals(cfg.authMethod)
                        ? MicrosoftAuth.login(MicrosoftAuth::showDeviceCodeDialog)
                        : OfflineAuth.login(cfg.botName);
                Platform.runLater(() -> logPanel.append("[" + p.name + "] 认证完成: " + auth.source()));

                BotRuntime rt = new BotRuntime();
                rt.brain = new AIBrain(cfg);
                rt.bot = new MCBot(cfg, auth, new MCBot.Listener() {
                    @Override
                    public void onLog(String line) {
                        Platform.runLater(() -> logPanel.append("[" + p.name + "] " + line));
                    }

                    @Override
                    public void onStateChange(MCBot.State state, String detail) {
                        Platform.runLater(() -> updateCard(p.name, state, detail));
                    }
                });
                rt.chatHandler = new ChatHandler(cfg, rt.bot, rt.brain);
                rt.bot.setChatHandler(rt.chatHandler);
                PlayerController ctl = new PlayerController(cfg, rt.bot);
                ctl.start();
                rt.bot.setController(ctl);
                rt.chatHandler.setController(ctl);
                running.put(p.name, rt);
                ensureVoiceServer(cfg, rt.brain);
                rt.bot.connect();
            } catch (Exception ex) {
                log.warn("连接失败: {}", ex.toString(), ex);
                Platform.runLater(() -> logPanel.append("[" + p.name + "] 连接失败: " + ex.getMessage()));
                running.remove(p.name);
            }
        }, "mcai-connect-" + p.name);
        t.setDaemon(true);
        t.start();
    }

    private void disconnect(PlayerProfile p) {
        BotRuntime rt = running.remove(p.name);
        if (rt != null) rt.bot.shutdown();
    }

    private void updateCard(String name, MCBot.State state, String detail) {
        for (var node : cardsPane.getChildren()) {
            if (node instanceof ProfileCard card && card.profile().name.equals(name)) {
                card.setState(state, detail);
            }
        }
    }

    private void ensureVoiceServer(BridgeConfig cfg, AIBrain brain) {
        if (!cfg.voiceEnabled || voiceServer != null) return;
        try {
            voiceServer = new VoiceServer(cfg, VoiceEngines.asr(cfg), VoiceEngines.tts(cfg));
            voiceServer.start();
        } catch (Exception e) {
            logPanel.append("语音服务启动失败: " + e.getMessage());
        }
    }

    // ---- 对话框与持久化 ----

    private void editDialog(PlayerProfile p, boolean isNew) {
        ProfileDialog dialog = new ProfileDialog(p);
        dialog.showAndWait();
        if (dialog.isSaved()) {
            if (isNew) baseCfg.players.add(p);
            rebuildCards();
        }
    }

    private void saveConfig() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("bot:\n  name: \"").append(baseCfg.players.get(0).name).append("\"\n");
            sb.append("server:\n  host: \"").append(baseCfg.players.get(0).serverHost)
                    .append("\"\n  port: ").append(baseCfg.players.get(0).serverPort).append("\n");
            sb.append("ai:\n  model: \"").append(baseCfg.players.get(0).aiModel)
                    .append("\"\n  api_key: \"").append(baseCfg.players.get(0).aiApiKey.replace("\"", "\\\"")).append("\"\n");
            sb.append("skin:\n  upload_url: \"").append(baseCfg.skinUploadUrl).append("\"\n  token: \"").append(baseCfg.skinToken).append("\"\n");
            sb.append("voice:\n  enabled: ").append(baseCfg.voiceEnabled).append("\n  server_port: ")
                    .append(baseCfg.voiceServerPort).append("\n  token: \"").append(baseCfg.voiceToken).append("\"\n");
            sb.append("players:\n");
            for (PlayerProfile p : baseCfg.players) {
                sb.append("  - name: \"").append(p.name).append("\"\n");
                sb.append("    auth: \"").append(p.auth).append("\"\n");
                sb.append("    host: \"").append(p.serverHost).append("\"\n    port: ").append(p.serverPort).append("\n");
                sb.append("    model: \"").append(p.aiModel).append("\"\n");
                sb.append("    skin_file: \"").append(p.skinFile.replace("\\", "\\\\")).append("\"\n");
                sb.append("    skin_model: \"").append(p.skinModel).append("\"\n");
            }
            Files.writeString(configPath, sb.toString());
            logPanel.append("配置已保存到 " + configPath.toAbsolutePath());
        } catch (Exception e) {
            logPanel.append("配置保存失败: " + e.getMessage());
        }
    }

    private void shutdownAll() {
        for (BotRuntime rt : running.values()) rt.bot.shutdown();
        if (voiceServer != null) voiceServer.stop();
    }
}
