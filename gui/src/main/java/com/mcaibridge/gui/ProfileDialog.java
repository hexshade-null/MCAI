package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.skin.SkinManager;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 档案编辑对话框（含皮肤选择 + 平面预览）。
 */
public class ProfileDialog extends Stage {
    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final ChoiceBox<String> authChoice = new ChoiceBox<>();
    private final PasswordField apiKeyField = new PasswordField();
    private final TextField modelField = new TextField();
    private final TextField skinPathField = new TextField();
    private final ChoiceBox<String> skinModelChoice = new ChoiceBox<>();
    private final SkinPreview preview = new SkinPreview();

    private byte[] loadedSkinPng;
    private boolean saved;

    public ProfileDialog(PlayerProfile profile) {
        setTitle("AI 玩家档案");
        authChoice.getItems().addAll("offline", "microsoft");
        skinModelChoice.getItems().addAll("classic", "slim");

        nameField.setText(profile.name);
        hostField.setText(profile.serverHost);
        portField.setText(String.valueOf(profile.serverPort));
        authChoice.setValue(profile.auth);
        apiKeyField.setText(profile.aiApiKey);
        modelField.setText(profile.aiModel);
        skinPathField.setText(profile.skinFile);
        skinModelChoice.setValue(profile.skinModel);
        reloadPreview();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        int r = 0;
        grid.add(new Label("玩家名字:"), 0, r); grid.add(nameField, 1, r++);
        grid.add(new Label("服务器地址:"), 0, r); grid.add(hostField, 1, r++);
        grid.add(new Label("端口:"), 0, r); grid.add(portField, 1, r++);
        grid.add(new Label("登录方式:"), 0, r); grid.add(authChoice, 1, r++);
        grid.add(new Label("API Key:"), 0, r); grid.add(apiKeyField, 1, r++);
        grid.add(new Label("AI 模型:"), 0, r); grid.add(modelField, 1, r++);

        Button browse = new Button("浏览…");
        browse.setOnAction(e -> chooseSkin());
        skinPathField.setPrefWidth(260);
        skinPathField.textProperty().addListener((o, a, b) -> reloadPreview());
        HBox skinRow = new HBox(8, skinPathField, browse, new Label("模型:"), skinModelChoice);
        grid.add(new Label("皮肤文件:"), 0, r); grid.add(skinRow, 1, r++);
        VBox previewBox = new VBox(4, new Label("皮肤预览:"), preview);
        grid.add(previewBox, 1, r++);

        Button save = new Button("保存");
        save.getStyleClass().add("btn-primary");
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        save.setOnAction(e -> {
            if (applyTo(profile)) {
                saved = true;
                close();
            }
        });
        cancel.setOnAction(e -> close());
        HBox buttons = new HBox(10, save, cancel);
        buttons.setPadding(new Insets(0, 16, 16, 16));

        BorderPane root = new BorderPane();
        root.setCenter(grid);
        root.setBottom(buttons);
        setScene(new Scene(root));
        getScene().getStylesheets().add(getClass().getResource("/pcl.css").toExternalForm());
    }

    public boolean isSaved() {
        return saved;
    }

    private void chooseSkin() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择皮肤 PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft 皮肤", "*.png"));
        var file = fc.showOpenDialog(this);
        if (file != null) skinPathField.setText(file.getAbsolutePath());
    }

    private void reloadPreview() {
        String path = skinPathField.getText();
        if (path == null || path.isBlank()) {
            loadedSkinPng = null;
        } else {
            try {
                loadedSkinPng = SkinManager.loadValidated(Path.of(path));
            } catch (IOException | IllegalArgumentException e) {
                loadedSkinPng = null;
            }
        }
        preview.render(loadedSkinPng);
    }

    private boolean applyTo(PlayerProfile p) {
        if (nameField.getText().isBlank()) {
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                    "玩家名字不能为空").showAndWait();
            return false;
        }
        p.name = nameField.getText().trim();
        p.serverHost = hostField.getText().trim();
        try {
            p.serverPort = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        p.auth = authChoice.getValue();
        p.aiApiKey = apiKeyField.getText().trim();
        p.aiModel = modelField.getText().trim();
        p.skinFile = skinPathField.getText().trim();
        p.skinModel = skinModelChoice.getValue();
        return true;
    }
}
