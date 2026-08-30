package com.mcaibridge.gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * GUI 偏好（语言/主题）：持久化到 ~/.mcaibridge/ui.properties。
 * 主题 auto 表示跟随系统（macOS 读 AppleInterfaceStyle）。
 */
public final class Prefs {
    public static final String THEME_AUTO = "auto";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".mcaibridge", "ui.properties");
    private static final Properties props = new Properties();

    static {
        try (InputStream in = Files.newInputStream(FILE)) {
            props.load(in);
        } catch (IOException ignored) {
        }
    }

    private Prefs() {
    }

    public static String lang() {
        return props.getProperty("lang", I18n.ZH);
    }

    public static void setLang(String lang) {
        props.setProperty("lang", lang);
        save();
    }

    public static String theme() {
        return props.getProperty("theme", THEME_AUTO);
    }

    public static void setTheme(String theme) {
        props.setProperty("theme", theme);
        save();
    }

    /** 实际生效的主题（auto 解析为明/暗）。 */
    public static String effectiveTheme() {
        String t = theme();
        if (THEME_DARK.equals(t) || THEME_LIGHT.equals(t)) return t;
        return systemDark() ? THEME_DARK : THEME_LIGHT;
    }

    /** macOS 深色模式检测；非 macOS 或检测失败返回 false。 */
    public static boolean systemDark() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes()).trim();
                return p.waitFor() == 0 && out.equalsIgnoreCase("Dark");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "MCAI UI preferences");
            }
        } catch (IOException ignored) {
        }
    }
}
