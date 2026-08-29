package com.mcaibridge.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibridge.config.BridgeConfig;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.cloudburstmc.math.vector.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家实体控制器：位置同步、行走/跟随、挖掘、执行指令。
 * 移动为客户端权威：直线走向目标（贴地 y 保持服务器同步值），复杂地形会由服务器校正（橡皮筋）。
 * 跟随目标坐标经 paper 伴生插件 /mcai/where 查询。
 */
public class PlayerController {
    private static final Logger log = LoggerFactory.getLogger(PlayerController.class);
    private static final double WALK_SPEED = 4.0;      // 格/秒（疾走 5.6）
    private static final long TICK_MS = 100;
    private static final long WHERE_INTERVAL_MS = 1000;
    private static final double FOLLOW_STOP_DIST = 2.0;

    private final BridgeConfig cfg;
    private final MCBot bot;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcai-move");
        t.setDaemon(true);
        return t;
    });

    private volatile double x, y, z, yaw, pitch;
    private volatile boolean hasPos;
    private volatile boolean onGround = true;
    private volatile String followTarget;
    private volatile Double moveTargetX, moveTargetZ;
    private volatile long lastWhereQuery;
    private final AtomicInteger idleCounter = new AtomicInteger();

    public PlayerController(BridgeConfig cfg, MCBot bot) {
        this.cfg = cfg;
        this.bot = bot;
    }

    public void start() {
        ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        ticker.shutdownNow();
    }

    /** 数据包入口：由 MCBot 转发。 */
    public void handle(Packet packet) {
        if (packet instanceof ClientboundPlayerPositionPacket p) {
            // 1.21+ 服务器要求确认传送，否则忽略后续所有移动包
            bot.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket(p.getId()));
            if (p.getRelatives().isEmpty()) {
                org.cloudburstmc.math.vector.Vector3d pos = p.getPosition();
                this.x = pos.getX();
                this.y = pos.getY();
                this.z = pos.getZ();
                this.yaw = p.getYRot();
                this.pitch = p.getXRot();
                this.hasPos = true;
                log.info("位置同步: ({}, {}, {})", x, y, z);
            }
        }
    }

    // ---- 指令 API（聊天指令调用）----

    public void follow(String playerName) {
        followTarget = playerName;
        moveTargetX = null;
        log.info("开始跟随 {}", playerName);
    }

    public void walkTo(double tx, double tz) {
        followTarget = null;
        moveTargetX = tx;
        moveTargetZ = tz;
        log.info("走向 ({}, {})", tx, tz);
    }

    public void stopMoving() {
        followTarget = null;
        moveTargetX = null;
    }

    public void digBelow() {
        if (!hasPos) return;
        Vector3i pos = Vector3i.from((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z));
        bot.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, pos, Direction.DOWN, 0));
        bot.send(new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, pos, Direction.DOWN, 0));
        bot.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        log.info("挖掘脚下方块 {}", pos);
    }

    public void sendCommand(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        bot.send(new ServerboundChatCommandPacket(c));
        log.info("执行指令: /{}", c);
    }

    public boolean hasPosition() {
        return hasPos;
    }

    public String positionString() {
        return hasPos ? String.format("(%.1f, %.1f, %.1f)", x, y, z) : "(未知)";
    }

    // ---- 内部 ----

    private void tick() {
        try {
            if (!hasPos) return;
            if (followTarget != null && System.currentTimeMillis() - lastWhereQuery > WHERE_INTERVAL_MS) {
                lastWhereQuery = System.currentTimeMillis();
                queryWhere(followTarget);
            }
            Double tx = moveTargetX, tz = moveTargetZ;
            if (tx != null && tz != null) {
                double dx = tx - x, dz = tz - z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1.0) {
                    double step = Math.min(WALK_SPEED * TICK_MS / 1000.0, dist);
                    double nx = x + dx / dist * step;
                    double nz = z + dz / dist * step;
                    x = nx;
                    z = nz;
                    yaw = (float) Math.toDegrees(Math.atan2(-(dx), dz));
                    sendMove();
                    idleCounter.set(0);
                    return;
                }
            }
            // 空闲心跳：每 ~2 秒发一次原位包，防 AFK 踢出
            if (idleCounter.incrementAndGet() >= 20) {
                idleCounter.set(0);
                sendMove();
            }
        } catch (Exception e) {
            log.debug("tick 异常: {}", e.toString());
        }
    }

    private void sendMove() {
        bot.send(new ServerboundMovePlayerPosRotPacket(onGround, false, x, y, z, (float) yaw, (float) pitch));
    }

    private void queryWhere(String name) {
        String base = cfg.skinUploadUrl.replaceAll("/mcai/skin$", "");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/mcai/where?name=" + name))
                .timeout(Duration.ofSeconds(3))
                .header("X-MCAI-Token", cfg.skinToken)
                .GET()
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            if (err != null) return;
            try {
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (o.has("x")) {
                    moveTargetX = o.get("x").getAsDouble();
                    moveTargetZ = o.get("z").getAsDouble();
                }
            } catch (Exception ignored) {
            }
        });
    }
}
