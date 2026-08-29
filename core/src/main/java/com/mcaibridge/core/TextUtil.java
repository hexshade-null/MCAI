package com.mcaibridge.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

/** adventure Component → 纯文本（递归展平，含 Translatable 参数）。 */
public final class TextUtil {

    private TextUtil() {
    }

    public static String plain(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder(64);
        append(sb, component);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Component c) {
        if (c instanceof TextComponent tc) {
            sb.append(tc.content());
        } else if (c instanceof TranslatableComponent tr) {
            for (net.kyori.adventure.text.TranslationArgument arg : tr.arguments()) append(sb, arg.asComponent());
        }
        for (Component child : c.children()) append(sb, child);
    }
}
