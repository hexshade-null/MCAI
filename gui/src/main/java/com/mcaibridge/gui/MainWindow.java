package com.mcaibridge.gui;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.auth.MicrosoftAuth;
import com.mcaibridge.auth.OfflineAuth;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.core.AIBrain;
import com.mcaibridge.core.ChatHandler;
import com.mcaibridge.core.MCBot;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * JavaFX 主窗口：上=配置表单，中=日志，下=连接/断开按钮。
 */
public class MainWindow extends Application {
    private BridgeConfig cfg;
    private ConfigPanel configPanel;
    private LogPanel logPanel;
    private MCBot bot;
    private Button connectButton;
    private Label statusLabel;

    @Override
    public void start(Stage stage) {
        cfg = new BridgeConfig();
        BorderPane root = new BorderPane();
        configPanel = new ConfigPanel(cfg);
        logPanel = new LogPanel();

        connectButton = new Button("连接");
        Button disconnectButton = new Button("断开");
        disconnectButton.setDisable(true);
        statusLabel = new Label("未连接");
        HBox bottom = new HBox(12, connectButton, disconnectButton, statusLabel);
        bottom.setPadding(new Insets(10));

        connectButton.setOnAction(e -> onConnect());
        disconnectButton.setOnAction(e -> {
            if (bot != null) bot.shutdown();
        });

        root.setTop(configPanel);
        root.setCenter(logPanel);
        root.setBottom(bottom);

        stage.setTitle("MCAI Bridge - Minecraft AI 玩家");
        stage.setScene(new Scene(root, 640, 560));
        stage.show();
        logPanel.append("就绪。填写配置后点击“连接”。");
    }

    private void onConnect() {
        connectButton.setDisable(true);
        configPanel.applyTo(cfg);
        Thread t = new Thread(() -> {
            try {
                AuthResult auth;
                if ("microsoft".equals(cfg.authMethod)) {
                    Platform.runLater(() -> logPanel.append("微软登录：等待设备码…"));
                    auth = MicrosoftAuth.login(MicrosoftAuth::showDeviceCodeDialog);
                } else {
                    auth = OfflineAuth.login(cfg.botName);
                }
                Platform.runLater(() -> logPanel.append("认证完成: " + auth.source() + " / " + auth.profile().getName()));
                MCBot b = new MCBot(cfg, auth, new MCBot.Listener() {
                    @Override
                    public void onLog(String line) {
                        logPanel.append(line);
                    }

                    @Override
                    public void onStateChange(MCBot.State state, String detail) {
                        Platform.runLater(() -> {
                            statusLabel.setText(state + (detail.isEmpty() ? "" : " - " + detail));
                            logPanel.append("状态: " + state + " (" + detail + ")");
                            connectButton.setDisable(state != MCBot.State.DISCONNECTED);
                        });
                    }
                });
                b.setChatHandler(new ChatHandler(cfg, b, new AIBrain(cfg)));
                bot = b;
                b.connect();
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    logPanel.append("连接失败: " + ex.getMessage());
                    connectButton.setDisable(false);
                });
            }
        }, "mcai-connect");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        if (bot != null) bot.shutdown();
    }
}
