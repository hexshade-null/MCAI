package com.mcaibridge.paper;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * 玩家登录（异步预登录阶段）注入已存皮肤 textures 属性。
 * 此阶段修改档案，首个 PlayerInfo 包即带皮肤，无需 NMS/respawn。
 */
public class SkinLoginListener implements Listener {
    private final SkinService skins;

    SkinLoginListener(SkinService skins) {
        this.skins = skins;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            String value = skins.storedValue(event.getName());
            if (value != null) {
                event.getPlayerProfile().setProperty(new ProfileProperty("textures", value));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("MCAIBridge").warn("皮肤注入失败: {}", e.toString());
        }
    }
}
