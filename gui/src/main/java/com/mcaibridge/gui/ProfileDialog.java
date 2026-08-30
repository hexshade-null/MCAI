package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.skin.SkinManager;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 档案编辑对话框：名字/账户/API key/皮肤 + 服务器按名引用（servers[]）+ 语音 svc 开关。
 */
public class ProfileDialog extends Stage {
    private final TextField nameField = new TextField();
    private final ChoiceBox<String> serverChoice = new ChoiceBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final ChoiceBox<String> authChoice = new ChoiceBox<>();
    private final PasswordField apiKeyField = new PasswordField();
    private final TextField modelField = new TextField();
    private final CheckBox svcToggle = new CheckBox(I18n.t("profile.svc"));
    private final TextField skinPathField = new TextField();
    private final ChoiceBox<String> skinModelChoice = new ChoiceBox<>();
    private final SkinPreview preview = new SkinPreview();

    private final BridgeConfig baseCfg;
    private byte[] loadedSkinPng;
    private boolean saved;

    public ProfileDialog(BridgeConfig baseCfg, PlayerProfile profile) {
        this.baseCfg = baseCfg;
        setTitle(I18n.t("profile.title"));
        authChoice.getItems().addAll("offline", "microsoft");
        skinModelChoice.getItems().addAll("classic", "slim");

        List<String> serverItems = new ArrayList<>();
        for (BridgeConfig.ServerEntry s : baseCfg.servers) serverItems.add(s.name);
        serverItems.add(I18n.t("profile.serverCustom"));
        serverChoice.getItems().addAll(serverItems);

        nameField.setText(profile.name);
        hostField.setText(profile.serverHost);
        portField.setText(String.valueOf(profile.serverPort));
        authChoice.setValue(profile.auth);
        apiKeyField.setText(profile.aiApiKey);
        modelField.setText(profile.aiModel);
        svcToggle.setSelected(profile.svc);
        skinPathField.setText(profile.skinFile);
        skinModelChoice.setValue(profile.skinModel);

        BridgeConfig.ServerEntry ref = baseCfg.findServer(profile.server);
        if (ref != null) {
            serverChoice.setValue(ref.name);
        } else {
            serverChoice.setValue(I18n.t("profile.serverCustom"));
        }
        hostField.setDisable(ref != null);
        portField.setDisable(ref != null);
        serverChoice.valueProperty().addListener((o, a, b) -> {
            boolean custom = I18n.t("profile.serverCustom").equals(b);
            hostField.setDisable(!custom);
            portField.setDisable(!custom);
            if (!custom) {
                BridgeConfig.ServerEntry s = baseCfg.findServer(b);
                if (s != null) {
                    hostField.setText(s.host);
                    portField.setText(String.valueOf(s.port));
                }
            }
        });

        reloadPreview();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        int r = 0;
        grid.add(new Label(I18n.t("profile.name")), 0, r);
        grid.add(nameField, 1, r++);
        grid.add(new Label(I18n.t("profile.server")), 0, r);
        grid.add(serverChoice, 1, r++);
        grid.add(new Label(I18n.t("profile.host")), 0, r);
        grid.add(hostField, 1, r++);
        grid.add(new Label(I18n.t("profile.port")), 0, r);
        grid.add(portField, 1, r++);
        grid.add(new Label(I18n.t("profile.auth")), 0, r);
        grid.add(authChoice, 1, r++);
        grid.add(new Label(I18n.t("profile.apiKey")), 0, r);
        grid.add(apiKeyField, 1, r++);
        grid.add(new Label(I18n.t("profile.model")), 0, r);
        grid.add(modelField, 1, r++);
        grid.add(new Label(""), 0, r);
        grid.add(svcToggle, 1, r++);

        Button browse = new Button(I18n.t("btn.browse"));
        browse.setOnAction(e -> chooseSkin());
        Button manageServers = new Button(I18n.t("btn.manageServers"));
        manageServers.getStyleClass().add("btn-ghost");
        manageServers.setOnAction(e -> {
            ServerDialog d = new ServerDialog(baseCfg);
            d.showAndWait();
        });
        skinPathField.setPrefWidth(240);
        skinPathField.textProperty().addListener((o, a, b) -> reloadPreview());
        HBox skinRow = new HBox(8, skinPathField, browse, new Label(I18n.t("profile.skinModel")), skinModelChoice);
        grid.add(new Label(I18n.t("profile.skinFile")), 0, r);
        grid.add(skinRow, 1, r++);
        VBox previewBox = new VBox(4, new Label(I18n.t("profile.preview")), preview);
        grid.add(previewBox, 1, r++);
        grid.add(manageServers, 1, r++);

        Button save = new Button(I18n.t("btn.save"));
        save.getStyleClass().add("btn-primary");
        Button cancel = new Button(I18n.t("btn.cancel"));
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
        ServerDialog.applyTheme(getScene());
    }

    public boolean isSaved() {
        return saved;
    }

    private void chooseSkin() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择皮肤 PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
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
                    I18n.t("profile.emptyName")).showAndWait();
            return false;
        }
        p.name = nameField.getText().trim();
        boolean custom = I18n.t("profile.serverCustom").equals(serverChoice.getValue());
        if (custom) {
            p.server = "";
        } else {
            p.server = serverChoice.getValue();
            BridgeConfig.ServerEntry s = baseCfg.findServer(p.server);
            if (s != null) {
                p.serverHost = s.host;
                p.serverPort = s.port;
            }
        }
        if (custom || p.serverHost == null || p.serverHost.isBlank()) {
            p.serverHost = hostField.getText().trim();
            try {
                p.serverPort = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        p.auth = authChoice.getValue();
        p.aiApiKey = apiKeyField.getText().trim();
        p.aiModel = modelField.getText().trim();
        p.svc = svcToggle.isSelected();
        p.skinFile = skinPathField.getText().trim();
        p.skinModel = skinModelChoice.getValue();
        return true;
    }
}
