package com.mcaibridge.world;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.core.MCBot;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 生存辅助（纯本地端）：
 * - 死亡自动重生：CombatKill 后延时回 PERFORM_RESPAWN（解决"连重生都不会"）。
 * - 低饥饿自动吃：跟踪血量/饥饿 + 快捷栏物品（玩家背包 containerId=0，快捷栏=槽位 36..44），
 *   找到食物则切换手持并右键进食。
 */
public class SurvivalManager {
    private static final Logger log = LoggerFactory.getLogger(SurvivalManager.class);
    private static final long EAT_COOLDOWN_MS = 4000;
    private static final long RESPAWN_DELAY_MS = 800;
    /** 玩家背包容器里快捷栏的起始槽位（36..44 = 快捷栏 0..8）。 */
    private static final int HOTBAR_CONTAINER_START = 36;

    private final BridgeConfig cfg;
    private final MCBot bot;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcai-survival");
        t.setDaemon(true);
        return t;
    });

    private volatile int entityId = -1;
    private volatile float health = 20f;
    private volatile int food = 20;
    private final int[] hotbar = new int[9];   // 物品协议 id，0=空
    private volatile int heldSlot = 0;         // 当前手持快捷栏位
    private volatile long lastEat;
    private volatile boolean dead;
    private volatile Runnable deathListener;

    public SurvivalManager(BridgeConfig cfg, MCBot bot) {
        this.cfg = cfg;
        this.bot = bot;
    }

    public void setDeathListener(Runnable r) {
        this.deathListener = r;
    }

    public void handle(Packet packet) {
        if (packet instanceof ClientboundLoginPacket p) {
            entityId = p.getEntityId();
        } else if (packet instanceof ClientboundPlayerCombatKillPacket p) {
            if (p.getPlayerId() == entityId && !dead) {
                dead = true;
                log.info("已死亡: {}，{}", plain(p.getMessage()),
                        cfg.autoRespawn ? RESPAWN_DELAY_MS + "ms 后自动重生" : "等待手动重生");
                Runnable dl = deathListener;
                if (dl != null) dl.run();
                if (cfg.autoRespawn) {
                    scheduler.schedule(this::respawn, RESPAWN_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
        } else if (packet instanceof ClientboundSetHealthPacket p) {
            health = p.getHealth();
            food = p.getFood();
            if (health <= 0f) dead = true;
            else if (dead) dead = false; // 重生成功
            maybeEat();
        } else if (packet instanceof ClientboundContainerSetContentPacket p) {
            if (p.getContainerId() == 0) acceptInventory(p.getItems());
        } else if (packet instanceof ClientboundContainerSetSlotPacket p) {
            if (p.getContainerId() == 0) acceptSlot(p.getSlot(), p.getItem());
        } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket p) {
            heldSlot = p.getSlot();
        }
    }

    /** 当前手持物品协议 id（0=空手）。 */
    public int heldItem() {
        int s = heldSlot;
        return (s >= 0 && s < 9) ? hotbar[s] : 0;
    }

    private void acceptSlot(int slot, ItemStack item) {
        if (slot < HOTBAR_CONTAINER_START || slot >= HOTBAR_CONTAINER_START + 9) return;
        hotbar[slot - HOTBAR_CONTAINER_START] = item != null ? item.getId() : 0;
        logHotbar();
    }

    private void acceptInventory(ItemStack[] items) {
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            int slot = HOTBAR_CONTAINER_START + i;
            int id = (items != null && slot < items.length && items[slot] != null) ? items[slot].getId() : 0;
            if (hotbar[i] != id) {
                hotbar[i] = id;
                changed = true;
            }
        }
        if (changed) logHotbar();
    }

    private void logHotbar() {
        StringBuilder sb = new StringBuilder("快捷栏: ");
        for (int i = 0; i < 9; i++) sb.append(i).append("=").append(hotbar[i]).append(" ");
        log.debug(sb.toString());
    }

    private void maybeEat() {
        if (!cfg.autoEat || dead) return;
        boolean hungry = food <= cfg.eatBelowFood;
        boolean hurtAndHungry = health <= 10f && food < 20;
        if (!hungry && !hurtAndHungry) return;
        eatNow();
    }

    /** 立即尝试进食（意图解析的 eat 动作也走这里）。 */
    public boolean eatNow() {
        if (System.currentTimeMillis() - lastEat < EAT_COOLDOWN_MS) return false;
        int slot = findFoodSlot();
        if (slot < 0) {
            log.info("想进食但快捷栏没有可吃的食物");
            return false;
        }
        lastEat = System.currentTimeMillis();
        bot.send(new ServerboundSetCarriedItemPacket(slot));
        heldSlot = slot;
        bot.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, 0f, 0f));
        bot.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        log.info("进食: 快捷栏槽位 {} (物品 id {})", slot, hotbar[slot]);
        return true;
    }

    private int findFoodSlot() {
        for (int i = 0; i < 9; i++) {
            if (hotbar[i] != 0 && isFood(hotbar[i])) return i;
        }
        return -1;
    }

    public boolean isFood(int itemId) {
        if (cfg.foodItemIds != null && !cfg.foodItemIds.isEmpty()) {
            return cfg.foodItemIds.contains(itemId);
        }
        return FoodIds.DEFAULT.contains(itemId);
    }

    private void respawn() {
        try {
            bot.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
            log.info("已发送重生请求");
        } catch (Exception e) {
            log.warn("重生请求失败: {}", e.toString());
        }
    }

    private static String plain(Object component) {
        if (component == null) return "";
        try {
            return com.mcaibridge.core.TextUtil.plain((net.kyori.adventure.text.Component) component);
        } catch (Exception e) {
            return component.toString();
        }
    }

    public float getHealth() {
        return health;
    }

    /** 自己的协议实体 id（登录包记录；疾跑命令包/被击退过滤都要用）。 */
    public int entityId() {
        return entityId;
    }

    public int getFood() {
        return food;
    }

    public boolean isDead() {
        return dead;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
