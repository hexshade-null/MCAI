package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ChoiceBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 总设置对话框：语音模块配置（TTS/ASR/端口/令牌）+ 外观（主题/语言）+ 服务器列表入口。
 * 角色级语音开关在各角色档案里（svc），这里只管全局。
 */
public class SettingsDialog extends Stage {
    private final BridgeConfig cfg;

    private final CheckBox voiceEnabled = new CheckBox(I18n.t("settings.voiceEnabled"));
    private final TextField voicePort = new TextField();
    private final TextField voiceToken = new TextField();
    private final TextField asrProvider = new TextField();
    private final TextField asrBaseUrl = new TextField();
    private final TextField asrModel = new TextField();
    private final PasswordField asrKey = new PasswordField();
    private final TextField ttsProvider = new TextField();
    private final TextField ttsBaseUrl = new TextField();
    private final TextField ttsModel = new TextField();
    private final TextField ttsVoice = new TextField();
    private final PasswordField ttsKey = new PasswordField();
    private final ChoiceBox<String> themeChoice = new ChoiceBox<>();
    private final ChoiceBox<String> langChoice = new ChoiceBox<>();

    private boolean voiceChanged;
    private boolean appearanceChanged;

    public SettingsDialog(BridgeConfig cfg) {
        this.cfg = cfg;
        setTitle(I18n.t("settings.title"));

        voiceEnabled.setSelected(cfg.voiceEnabled);
        voicePort.setText(String.valueOf(cfg.voiceServerPort));
        voiceToken.setText(cfg.voiceToken);
        asrProvider.setText(cfg.asrProvider);
        asrBaseUrl.setText(cfg.asrBaseUrl);
        asrModel.setText(cfg.asrModel);
        asrKey.setText(cfg.asrApiKey);
        ttsProvider.setText(cfg.ttsProvider);
        ttsBaseUrl.setText(cfg.ttsBaseUrl);
        ttsModel.setText(cfg.ttsModel);
        ttsVoice.setText(cfg.ttsVoice);
        ttsKey.setText(cfg.ttsApiKey);

        themeChoice.getItems().addAll(
                I18n.t("settings.themeAuto"), I18n.t("settings.themeLight"), I18n.t("settings.themeDark"));
        themeChoice.getSelectionModel().select(switch (Prefs.theme()) {
            case Prefs.THEME_LIGHT -> 1;
            case Prefs.THEME_DARK -> 2;
            default -> 0;
        });
        themeChoice.setOnAction(e -> {
            int i = themeChoice.getSelectionModel().getSelectedIndex();
            Prefs.setTheme(i == 1 ? Prefs.THEME_LIGHT : i == 2 ? Prefs.THEME_DARK : Prefs.THEME_AUTO);
            appearanceChanged = true;
        });

        langChoice.getItems().addAll("中文", "English");
        langChoice.getSelectionModel().select(I18n.EN.equals(Prefs.lang()) ? 1 : 0);
        langChoice.setOnAction(e -> {
            I18n.setLang(langChoice.getSelectionModel().getSelectedIndex() == 1 ? I18n.EN : I18n.ZH);
            Prefs.setLang(I18n.lang());
            appearanceChanged = true;
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int r = 0;

        Label voiceTitle = new Label(I18n.t("settings.voice"));
        voiceTitle.getStyleClass().add("section-title");
        grid.add(voiceTitle, 0, r++, 2, 1);
        grid.add(voiceEnabled, 1, r++);
        grid.add(new Label(I18n.t("settings.voicePort")), 0, r);
        grid.add(voicePort, 1, r++);
        grid.add(new Label(I18n.t("settings.voiceToken")), 0, r);
        grid.add(voiceToken, 1, r++);
        grid.add(asrSection(r), 0, r++, 2, 1);
        grid.add(field(I18n.t("settings.provider"), asrProvider), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.baseUrl"), asrBaseUrl), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.model"), asrModel), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.apiKey"), asrKey), 0, r, 2, 1);
        r++;
        Label ttsTitle = new Label(I18n.t("settings.tts"));
        ttsTitle.getStyleClass().add("section-title");
        grid.add(ttsTitle, 0, r++, 2, 1);
        grid.add(field(I18n.t("settings.provider"), ttsProvider), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.baseUrl"), ttsBaseUrl), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.model"), ttsModel), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.voiceSel"), ttsVoice), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.apiKey"), ttsKey), 0, r, 2, 1);
        r++;

        grid.add(new Separator(), 0, r++, 2, 1);
        Label appear = new Label(I18n.t("settings.appearance"));
        appear.getStyleClass().add("section-title");
        grid.add(appear, 0, r++, 2, 1);
        grid.add(field(I18n.t("settings.theme"), themeChoice), 0, r, 2, 1);
        r++;
        grid.add(field(I18n.t("settings.language"), langChoice), 0, r, 2, 1);
        r++;

        Button servers = new Button(I18n.t("btn.manageServers"));
        servers.getStyleClass().add("btn-ghost");
        servers.setOnAction(e -> {
            ServerDialog d = new ServerDialog(cfg);
            d.showAndWait();
        });
        grid.add(servers, 1, r++);

        Button save = new Button(I18n.t("btn.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            apply();
            close();
        });
        Button cancel = new Button(I18n.t("btn.cancel"));
        cancel.getStyleClass().add("btn-ghost");
        cancel.setOnAction(e -> close());
        HBox buttons = new HBox(10, save, cancel);
        buttons.setPadding(new Insets(0, 14, 14, 14));

        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(grid);
        sp.setFitToWidth(true);
        sp.setPrefHeight(560);
        VBox root = new VBox(sp, buttons);
        setScene(new Scene(root, 520, 620));
        getScene().getStylesheets().add(getClass().getResource("/pcl.css").toExternalForm());
        ServerDialog.applyTheme(getScene());
    }

    private Label asrSection(int row) {
        Label l = new Label(I18n.t("settings.asr"));
        l.getStyleClass().add("section-title");
        return l;
    }

    private HBox field(String labelText, javafx.scene.control.Control input) {
        Label l = new Label(labelText);
        l.getStyleClass().add("form-label");
        l.setMinWidth(90);
        input.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
        return new HBox(10, l, input);
    }

    private void apply() {
        boolean newVoiceOn = voiceEnabled.isSelected();
        int newPort = parse(voicePort.getText(), cfg.voiceServerPort);
        voiceChanged = newVoiceOn != cfg.voiceEnabled || newPort != cfg.voiceServerPort
                || !voiceToken.getText().equals(cfg.voiceToken)
                || !asrProvider.getText().trim().equals(cfg.asrProvider)
                || !ttsProvider.getText().trim().equals(cfg.ttsProvider);
        cfg.voiceEnabled = newVoiceOn;
        cfg.voiceServerPort = newPort;
        cfg.voiceToken = voiceToken.getText().trim();
        cfg.asrProvider = asrProvider.getText().trim();
        cfg.asrBaseUrl = asrBaseUrl.getText().trim();
        cfg.asrModel = asrModel.getText().trim();
        cfg.asrApiKey = asrKey.getText().trim();
        cfg.ttsProvider = ttsProvider.getText().trim();
        cfg.ttsBaseUrl = ttsBaseUrl.getText().trim();
        cfg.ttsModel = ttsModel.getText().trim();
        cfg.ttsVoice = ttsVoice.getText().trim();
        cfg.ttsApiKey = ttsKey.getText().trim();
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean isVoiceChanged() {
        return voiceChanged;
    }

    public boolean isAppearanceChanged() {
        return appearanceChanged;
    }
}
