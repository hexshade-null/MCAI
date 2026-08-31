package com.mcaibridge.core;

import com.google.gson.JsonObject;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.world.EntityTracker;
import com.mcaibridge.world.EntityTracker.TrackedEntity;
import com.mcaibridge.world.SurvivalManager;
import com.mcaibridge.world.WorldModel;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.cloudburstmc.math.vector.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 动作执行器：按顺序执行意图解析产出的动作序列（言出法随的"手"）。
 * 由 PlayerController 的 ticker 驱动（100ms 一拍）；行走目标交给 PlayerController，
 * 挖掘/攻击自行发包；每步完成或失败通过 reporter 在聊天里汇报。
 */
public class ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);
    private static final long WALK_TIMEOUT_MS = 60_000;
    private static final long FOLLOW_TIMEOUT_MS = 120_000;
    private static final long ATTACK_TIMEOUT_MS = 10_000;
    private static final double ATTACK_RANGE = 2.8;      // Paper 眼-命中箱 3.0 校验留余量
    private static final long SWING_INTERVAL_MS = 250;

    /** 单个动作：type + 原始参数。 */
    public record Action(String type, JsonObject args) {
    }

    private final BridgeConfig cfg;
    private final MCBot bot;
    private final PlayerController controller;
    private final WorldModel world;
    private final EntityTracker entities;
    private final SurvivalManager survival;
    private final com.mcaibridge.mining.MiningProgressTracker mining;

    private final ArrayDeque<Action> queue = new ArrayDeque<>();
    private Action current;

    // current 的执行状态
    private long stepStart;
    private long lastSubStep;
    private Vector3i digPos;
    private boolean digStarted;
    private int attackEntityId = -1;
    private String followName;

    /** 动作执行过程汇报（聊天回复通道），由 ChatHandler 注入。 */
    private volatile Consumer<String> reporter;

    public ActionExecutor(BridgeConfig cfg, MCBot bot, PlayerController controller,
                          WorldModel world, EntityTracker entities, SurvivalManager survival) {
        this.cfg = cfg;
        this.bot = bot;
        this.controller = controller;
        this.world = world;
        this.entities = entities;
        this.survival = survival;
        this.mining = new com.mcaibridge.mining.MiningProgressTracker(bot, world, survival);
    }

    public void setReporter(Consumer<String> reporter) {
        this.reporter = reporter;
    }

    /** 新意图覆盖旧队列；say 部分由调用方（ChatHandler）先行发送。 */
    public synchronized void submit(List<Action> actions) {
        queue.clear();
        current = null;
        if (actions != null) queue.addAll(actions);
    }

    /** 清空执行（死亡/手动停止）。 */
    public synchronized void clear() {
        queue.clear();
        current = null;
        attackEntityId = -1;
        mining.abort();
    }

    public synchronized boolean busy() {
        return current != null || !queue.isEmpty();
    }

    /** 由 PlayerController 每 100ms 调一次。 */
    public synchronized void tick() {
        if (current == null) {
            Action next = queue.poll();
            if (next == null) return;
            current = next;
            stepStart = System.currentTimeMillis();
            lastSubStep = 0;
            begin(current);
        }
        if (current == null) return;
        try {
            if (step(current)) {
                log.info("动作完成: {}", describe(current));
                current = null;
            }
        } catch (Exception e) {
            log.warn("动作执行异常 {}: {}", describe(current), e.toString());
            report("动作出错了：" + e.toString());
            current = null;
        }
    }

    // ---- 各动作 ----

    private void begin(Action a) {
        switch (a.type()) {
            case "walk_to" -> {
                double x = argD(a, "x", Double.NaN);
                double z = argD(a, "z", Double.NaN);
                if (Double.isNaN(x) || Double.isNaN(z)) {
                    report("坐标没听懂");
                    current = null;
                    return;
                }
                controller.walkTo(x, z);
            }
            case "follow" -> followName = argS(a, "target", null);
            case "stop" -> {
                controller.stopMoving();
                queue.clear();
            }
            case "dig" -> {
                Vector3i pos;
                if (a.args() != null && a.args().has("x") && a.args().has("y") && a.args().has("z")) {
                    pos = Vector3i.from((int) argD(a, "x", 0), (int) argD(a, "y", 0), (int) argD(a, "z", 0));
                } else if (controller.hasPosition()) {
                    double[] p = controller.position();
                    pos = Vector3i.from((int) Math.floor(p[0]), (int) Math.floor(p[1]) - 1, (int) Math.floor(p[2]));
                } else {
                    report("还不知道自己在哪，没法挖");
                    current = null;
                    return;
                }
                digPos = pos;
                digStarted = false;
                double[] p = controller.position();
                double dx = pos.getX() + 0.5 - p[0], dz = pos.getZ() + 0.5 - p[2];
                if (dx * dx + dz * dz <= 4.5 * 4.5) {
                    controller.stopMoving();
                    startDigging();
                } else {
                    controller.walkTo(pos.getX() + 0.5, pos.getZ() + 0.5);
                }
            }
            case "attack" -> {
                attackEntityId = resolveTargetId(argS(a, "target", ""));
                if (attackEntityId >= 0 && survival.entityId() > 0) {
                    controller.faceTo(entities.get(attackEntityId).x, entities.get(attackEntityId).y, entities.get(attackEntityId).z);
                }
            }
            case "eat" -> {
                boolean ok = survival.eatNow();
                if (!ok) report("快捷栏里没有能吃的");
                current = null;
            }
            case "command" -> {
                controller.sendCommand(argS(a, "cmd", ""));
                current = null;
            }
            default -> {
                log.warn("未知动作类型: {}", a.type());
                current = null;
            }
        }
    }

    /** 返回 true 表示动作完成（成功或失败均算）。 */
    private boolean step(Action a) {
        long now = System.currentTimeMillis();
        switch (a.type()) {
            case "walk_to" -> {
                if (!controller.isMoving()) return true;
                if (now - stepStart > WALK_TIMEOUT_MS) {
                    controller.stopMoving();
                    report("走太久了，先停下");
                    return true;
                }
                return false;
            }
            case "follow" -> {
                TrackedEntity e = resolveEntity(followName);
                if (e == null) {
                    // 找不到实体时退回插件查询跟随（旧路径）
                    if (followName != null && !controller.isMoving()) controller.follow(followName);
                    if (now - stepStart > FOLLOW_TIMEOUT_MS) {
                        controller.stopMoving();
                        report("跟随超时，停下了");
                        return true;
                    }
                    return false;
                }
                if (now - lastSubStep > 500) {
                    lastSubStep = now;
                    double d = Math.sqrt(e.dist2(controller.position()[0], controller.position()[2]));
                    if (d < 2.0) {
                        controller.stopMoving();
                    } else {
                        controller.walkTo(e.x, e.z);
                    }
                }
                if (now - stepStart > FOLLOW_TIMEOUT_MS) {
                    controller.stopMoving();
                    return true;
                }
                return false;
            }
            case "dig" -> {
                if (!digStarted) {
                    double[] p = controller.position();
                    double dx = digPos.getX() + 0.5 - p[0], dz = digPos.getZ() + 0.5 - p[2];
                    if (dx * dx + dz * dz <= 4.5 * 4.5) {
                        controller.stopMoving();
                        startDigging();
                    } else if (now - stepStart > WALK_TIMEOUT_MS) {
                        report("走不到挖掘点");
                        return true;
                    } else if (!controller.isMoving()) {
                        controller.walkTo(digPos.getX() + 0.5, digPos.getZ() + 0.5);
                    }
                    return false;
                }
                return mining.tick();
            }
            case "attack" -> {
                TrackedEntity e = attackEntityId >= 0 ? entities.get(attackEntityId) : null;
                if (e == null) {
                    report("目标不见了");
                    controller.stopMoving();
                    return true;
                }
                double[] p = controller.position();
                double d = Math.sqrt(e.dist2(p[0], p[2]));
                if (now - stepStart > ATTACK_TIMEOUT_MS) {
                    controller.stopMoving();
                    report("打了半天没打完，先停");
                    return true;
                }
                if (d > ATTACK_RANGE) {
                    if (now - lastSubStep > 400) {
                        lastSubStep = now;
                        controller.walkTo(e.x, e.z);
                    }
                    return false;
                }
                controller.stopMoving();
                // 冷却节奏：手持武器冷却 + 抖动（原版 1.9 战斗，伤害按冷却比例结算）
                long cooldown = (long) (com.mcaibridge.mining.ToolSpeedRegistry
                        .attackCooldownSeconds(survivalHeldItem()) * 1000) + 150 + (long) (Math.random() * 150);
                if (now - lastSubStep >= cooldown) {
                    lastSubStep = now;
                    // 原版疾跑击退：攻击瞬间保持疾跑状态，命中后立即取消
                    boolean sprintHit = cfg.sprintKnockback && survival.entityId() > 0;
                    controller.faceTo(e.x, e.y + 1.0, e.z);
                    if (sprintHit) {
                        com.mcaibridge.protocol.ActionStateSender.setSprinting(bot, survival.entityId(), true);
                    }
                    bot.send(new ServerboundInteractPacket(e.id, InteractAction.ATTACK, false));
                    bot.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
                    if (sprintHit) {
                        com.mcaibridge.protocol.ActionStateSender.setSprinting(bot, survival.entityId(), false);
                    }
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** 当前手持物品 id（供攻击冷却/后续挖掘速度用）。 */
    private int survivalHeldItem() {
        return survival.heldItem();
    }

    /** 进入挖掘距离后：面向方块并按公式开始挖掘。 */
    private void startDigging() {
        digStarted = true;
        controller.faceTo(digPos.getX() + 0.5, digPos.getY() + 0.5, digPos.getZ() + 0.5);
        if (!mining.begin(digPos, controller.physicsOffGround(), controller.physicsInWater())) {
            report("这个方块挖不动");
            current = null;
        }
    }

    private int resolveTargetId(String target) {
        double[] p = controller.position();
        if (target == null || target.isBlank()) {
            TrackedEntity h = entities.nearestHostile(p[0], p[1], p[2], 24, 10);
            log.info("resolveTarget: pos=({},{},{}) tracked={} nearest={}", p[0], p[1], p[2], entities.size(),
                    h != null ? h.type + "@" + h.id : "none");
            if (h == null) {
                entities.zombies().forEach(z -> log.info("  tracked-zombie id={} y={} dist2={}", z.id, z.y, z.dist2(p[0], p[2])));
            }
            return h != null ? h.id : -1;
        }
        TrackedEntity e = resolveEntity(target);
        return e != null ? e.id : -1;
    }

    private TrackedEntity resolveEntity(String target) {
        if (target == null || target.isBlank()) return null;
        TrackedEntity e = entities.findPlayer(target);
        if (e == null) e = entities.nearestOfType(target, controller.position()[0], controller.position()[2], 32);
        if (e == null) e = entities.nearestHostile(controller.position()[0], controller.position()[1], controller.position()[2], 24, 10);
        return e;
    }

    private void report(String msg) {
        Consumer<String> r = reporter;
        if (r != null) r.accept(msg);
        else log.info("[动作汇报] {}", msg);
    }

    // 供 PlayerController 行走阻挡时回调
    public void onWalkBlocked(String reason) {
        if (current != null && ("walk_to".equals(current.type()) || "follow".equals(current.type()) || "attack".equals(current.type()))) {
            report("走不过去：" + reason);
            current = null;
        }
    }

    private static double argD(Action a, String key, double def) {
        try {
            if (a.args() == null || !a.args().has(key)) return def;
            return a.args().get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    private static String argS(Action a, String key, String def) {
        try {
            if (a.args() == null || !a.args().has(key)) return def;
            return a.args().get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    private static String describe(Action a) {
        return a.type() + (a.args() != null ? a.args() : "");
    }
}
