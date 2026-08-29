package com.mcaibridge.gui;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

/**
 * 日志显示面板：带时间戳追加，限制最大行数防内存膨胀。
 */
public class LogPanel extends BorderPane {
    private static final int MAX_LINES = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea area = new TextArea();
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    public LogPanel() {
        area.setEditable(false);
        area.setWrapText(true);
        setCenter(area);
    }

    /** 任意线程可调用，内部切到 FX 线程。 */
    public void append(String line) {
        Platform.runLater(() -> {
            lines.addLast("[" + TIME.format(LocalTime.now()) + "] " + line);
            while (lines.size() > MAX_LINES) lines.pollFirst();
            area.setText(String.join("\n", lines));
            area.setScrollTop(Double.MAX_VALUE);
        });
    }
}
