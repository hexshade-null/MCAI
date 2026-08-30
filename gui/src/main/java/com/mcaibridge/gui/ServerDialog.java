package com.mcaibridge.gui;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.config.BridgeConfig.ServerEntry;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;

/**
 * 服务器列表编辑对话框：像 MC 客户端一样维护 servers[]，角色按名引用。
 */
public class ServerDialog extends Stage {
    private final BridgeConfig cfg;
    private final TableView<ServerEntry> table = new TableView<>();
    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("25565");

    private boolean changed;

    public ServerDialog(BridgeConfig cfg) {
        this.cfg = cfg;
        setTitle(I18n.t("server.title"));

        TableColumn<ServerEntry, String> nameCol = new TableColumn<>(I18n.t("server.name"));
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        TableColumn<ServerEntry, String> addrCol = new TableColumn<>(I18n.t("profile.server"));
        addrCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().host + ":" + c.getValue().port));
        nameCol.setPrefWidth(140);
        addrCol.setPrefWidth(220);
        table.getColumns().add(nameCol);
        table.getColumns().add(addrCol);
        table.getItems().addAll(cfg.servers);
        table.setPrefHeight(200);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> loadToFields(b));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(10, 0, 0, 0));
        nameField.setPrefWidth(140);
        hostField.setPrefWidth(180);
        form.add(new Label(I18n.t("server.name")), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label(I18n.t("server.host")), 0, 1);
        form.add(hostField, 1, 1);
        form.add(new Label(I18n.t("server.port")), 0, 2);
        form.add(portField, 1, 2);

        Button add = new Button(I18n.t("server.add"));
        add.getStyleClass().add("btn-primary");
        add.setOnAction(e -> addOrUpdate());
        Button del = new Button(I18n.t("btn.delete"));
        del.getStyleClass().add("btn-danger");
        del.setOnAction(e -> deleteSelected());
        HBox actions = new HBox(8, add, del);
        actions.setPadding(new Insets(10, 0, 0, 0));

        Button close = new Button(I18n.t("btn.save"));
        close.getStyleClass().add("btn-ghost");
        close.setOnAction(e -> {
            changed = true;
            close();
        });
        HBox bottom = new HBox(close);
        bottom.setPadding(new Insets(14, 0, 0, 0));

        VBox content = new VBox(8, table, new Label(I18n.t("server.name") + " / " + I18n.t("server.host") + " / " + I18n.t("server.port")), form, actions, bottom);
        content.setPadding(new Insets(16));
        setScene(new Scene(content));
        getScene().getStylesheets().add(getClass().getResource("/pcl.css").toExternalForm());
        applyTheme(getScene());
    }

    private void loadToFields(ServerEntry s) {
        if (s == null) return;
        nameField.setText(s.name);
        hostField.setText(s.host);
        portField.setText(String.valueOf(s.port));
    }

    private void addOrUpdate() {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        if (name.isEmpty() || host.isEmpty()) return;
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return;
        }
        ServerEntry existing = cfg.findServer(name);
        if (existing == null) {
            existing = new ServerEntry();
            existing.name = name;
            cfg.servers.add(existing);
        }
        existing.host = host;
        existing.port = port;
        refreshTable();
        changed = true;
    }

    private void deleteSelected() {
        ServerEntry sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) {
            cfg.servers.remove(sel);
            refreshTable();
            changed = true;
        }
    }

    private void refreshTable() {
        var sel = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(new ArrayList<>(cfg.servers));
        if (sel != null) table.getSelectionModel().select(sel);
    }

    /** 保存后 servers[] 是否有变化（调用方据此重建卡片）。 */
    public boolean isChanged() {
        return changed;
    }

    static void applyTheme(Scene scene) {
        String theme = Prefs.effectiveTheme();
        scene.getRoot().getStyleClass().removeIf(c -> c.startsWith("theme-"));
        scene.getRoot().getStyleClass().add("theme-" + theme);
    }
}
