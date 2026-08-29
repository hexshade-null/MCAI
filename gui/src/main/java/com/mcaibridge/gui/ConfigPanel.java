package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig;
import javafx.geometry.Insets;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * 配置表单：名字 / 服务器 / API Key / 登录方式 / 模型。
 */
public class ConfigPanel extends GridPane {
    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final ChoiceBox<String> authChoice = new ChoiceBox<>();
    private final PasswordField apiKeyField = new PasswordField();
    private final TextField modelField = new TextField();

    public ConfigPanel(BridgeConfig cfg) {
        setHgap(10);
        setVgap(8);
        setPadding(new Insets(12));

        authChoice.getItems().addAll("offline", "microsoft");

        addRow(0, new Label("玩家名字:"), nameField);
        addRow(1, new Label("服务器地址:"), hostField);
        addRow(2, new Label("端口:"), portField);
        addRow(3, new Label("登录方式:"), authChoice);
        addRow(4, new Label("API Key:"), apiKeyField);
        addRow(5, new Label("AI 模型:"), modelField);

        from(cfg);
    }

    public void from(BridgeConfig cfg) {
        nameField.setText(cfg.botName);
        hostField.setText(cfg.serverHost);
        portField.setText(String.valueOf(cfg.serverPort));
        authChoice.setValue(cfg.authMethod);
        apiKeyField.setText(cfg.aiApiKey);
        modelField.setText(cfg.aiModel);
    }

    /** 将表单内容写回配置对象（点连接时调用）。 */
    public void applyTo(BridgeConfig cfg) {
        cfg.botName = nameField.getText().trim();
        cfg.serverHost = hostField.getText().trim();
        try {
            cfg.serverPort = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        cfg.authMethod = authChoice.getValue();
        cfg.aiApiKey = apiKeyField.getText().trim();
        cfg.aiModel = modelField.getText().trim();
    }
}
