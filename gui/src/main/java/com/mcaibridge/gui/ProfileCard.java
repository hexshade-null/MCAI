package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.core.MCBot;
import javafx.animation.ScaleTransition;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * AI 玩家卡片：名字/服务器/状态灯/svc 徽标 + 连接、编辑、删除。
 */
public class ProfileCard extends VBox {
    public interface Callback {
        void onConnect(PlayerProfile p);

        void onDisconnect(PlayerProfile p);

        void onEdit(PlayerProfile p);

        void onDelete(PlayerProfile p);
    }

    private final PlayerProfile profile;
    private final Callback cb;
    private final Region dot = new Region();
    private final Label statusLabel = new Label();
    private final Label serverLabel = new Label();
    private final Label svcBadge = new Label("SVC");
    private final Button connectBtn = new Button();

    public PlayerProfile profile() {
        return profile;
    }

    public ProfileCard(PlayerProfile profile, Callback cb) {
        this.profile = profile;
        this.cb = cb;
        getStyleClass().addAll("card");
        setSpacing(8);
        // 宽度随内容自适应（按钮行最宽），避免文字被截断成省略号
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Region.USE_PREF_SIZE);

        Label name = new Label(profile.name);
        name.getStyleClass().add("card-name");
        serverLabel.getStyleClass().add("card-server");
        serverLabel.setText(serverText());
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setText(I18n.t("status.notConnected"));
        svcBadge.getStyleClass().add("card-badge");
        svcBadge.setVisible(profile.svc);
        svcBadge.setManaged(profile.svc);

        HBox statusRow = new HBox(6, dot, statusLabel);
        setDot("grey");

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setText(I18n.t("btn.connect"));
        Button edit = new Button(I18n.t("btn.edit"));
        Button skin = new Button(I18n.t("btn.skin"));
        Button delete = new Button(I18n.t("btn.delete"));
        edit.getStyleClass().add("btn-ghost");
        skin.getStyleClass().add("btn-ghost");
        delete.getStyleClass().add("btn-danger");

        connectBtn.setOnAction(e -> cb.onConnect(profile));
        edit.setOnAction(e -> cb.onEdit(profile));
        skin.setOnAction(e -> cb.onEdit(profile));
        delete.setOnAction(e -> cb.onDelete(profile));
        HBox buttons = new HBox(8, connectBtn, edit, skin, delete);

        HBox titleRow = new HBox(10, name, statusRow, svcBadge);
        titleRow.setSpacing(10);
        getChildren().addAll(titleRow, serverLabel, new Region(), buttons);

        setOnMouseEntered(e -> playScale(1.02));
        setOnMouseExited(e -> playScale(1.0));
    }

    private String serverText() {
        String host = profile.serverHost + ":" + profile.serverPort;
        if (profile.server != null && !profile.server.isBlank()) host += " · " + profile.server;
        return host + " · " + profile.auth;
    }

    private void playScale(double to) {
        ScaleTransition st = new ScaleTransition(javafx.util.Duration.millis(120), this);
        st.setToX(to);
        st.setToY(to);
        st.play();
    }

    public void setState(MCBot.State state, String detail) {
        switch (state) {
            case CONNECTED -> {
                setDot("green");
                statusLabel.setText(I18n.t("status.online"));
                connectBtn.setText(I18n.t("btn.disconnect"));
            }
            case CONNECTING -> {
                setDot("yellow");
                statusLabel.setText(I18n.t("status.connecting"));
                connectBtn.setText(I18n.t("btn.connect"));
            }
            default -> {
                setDot("red");
                String base = I18n.t("status.offline");
                statusLabel.setText(detail == null || detail.isBlank() || I18n.t("status.notConnected").equals(detail)
                        ? base : base + " · " + abbreviate(detail));
                connectBtn.setText(I18n.t("btn.connect"));
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 26 ? s.substring(0, 26) + "…" : s;
    }

    private void setDot(String color) {
        dot.getStyleClass().removeIf(c -> c.startsWith("status-dot-"));
        dot.getStyleClass().add("status-dot-" + color);
    }
}
