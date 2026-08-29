package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig.PlayerProfile;
import com.mcaibridge.core.MCBot;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * AI 玩家卡片：名字/服务器/状态灯 + 连接、断开、编辑、删除。
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
    private final Label statusLabel = new Label("未连接");
    private final Button connectBtn = new Button("连接");

    public PlayerProfile profile() {
        return profile;
    }

    public ProfileCard(PlayerProfile profile, Callback cb) {
        this.profile = profile;
        this.cb = cb;
        getStyleClass().addAll("card");
        setSpacing(8);
        setPrefWidth(240);

        Label name = new Label(profile.name);
        name.getStyleClass().add("card-name");
        Label server = new Label(profile.serverHost + ":" + profile.serverPort + " · " + profile.auth);
        server.getStyleClass().add("card-server");

        HBox statusRow = new HBox(6, dot, statusLabel);
        statusLabel.getStyleClass().add("status-text");
        setDot("grey");

        connectBtn.getStyleClass().add("btn-primary");
        Button edit = new Button("编辑");
        Button skin = new Button("皮肤");
        Button delete = new Button("删除");
        edit.getStyleClass().add("btn-ghost");
        skin.getStyleClass().add("btn-ghost");
        delete.getStyleClass().add("btn-ghost");

        connectBtn.setOnAction(e -> cb.onConnect(profile));
        edit.setOnAction(e -> cb.onEdit(profile));
        skin.setOnAction(e -> cb.onEdit(profile));
        delete.setOnAction(e -> cb.onDelete(profile));
        HBox buttons = new HBox(8, connectBtn, edit, skin, delete);

        getChildren().addAll(new HBox(8, name, statusRow) {{ setSpacing(8); }}, server, new Region(), buttons);
        VBox.setMargin(new HBox(), new Insets(0));

        setOnMouseEntered(e -> playScale(1.02));
        setOnMouseExited(e -> playScale(1.0));
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
                statusLabel.setText("在线");
                connectBtn.setText("断开");
            }
            case CONNECTING -> {
                setDot("yellow");
                statusLabel.setText("连接中…");
                connectBtn.setText("连接");
            }
            default -> {
                setDot("red");
                statusLabel.setText(detail == null || detail.isBlank() || "未连接".equals(detail) ? "离线" : "离线 · " + abbreviate(detail));
                connectBtn.setText("连接");
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
