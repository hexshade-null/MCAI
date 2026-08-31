package com.mcaibridge.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.world.WorldModel;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
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
 * 行走为客户端权威：有世界模型时贴地走（读 groundY，跨 1 格台阶、拒绝撞墙/深崖），
 * 无世界模型时保持服务器同步 y 直线走（迭代二行为）。
 * 跟随目标的实体坐标由 EntityTracker 提供；找不到实体时回落 paper 插件 /mcai/where。
 */
public class PlayerController {
    private static final Logger log = LoggerFactory.getLogger(PlayerController.class);
    private static final double WALK_SPEED = 4.0;      // 格/秒（疾走 5.6）
    private static final long TICK_MS = 100;
    private static final long WHERE_INTERVAL_MS = 1000;
    private static final double ARRIVE_DIST = 1.0;

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

    private volatile WorldModel world;
    private volatile ActionExecutor executor;
    private volatile com.mcaibridge.world.SurvivalManager survival;
    private volatile com.mcaibridge.physics.PhysicsEngine physics;
    private boolean sprintState;
    private final com.mcaibridge.action.HumanizedExecutor humanize = new com.mcaibridge.action.HumanizedExecutor();
    private final com.mcaibridge.action.AimController aim = new com.mcaibridge.action.AimController(humanize);
    private final com.mcaibridge.action.MoveController moveHelper = new com.mcaibridge.action.MoveController(this);
    /** A* 寻路航点（世界坐标 [x,y,z]），空=直线模式。 */
    private final java.util.ArrayDeque<double[]> pathQueue = new java.util.ArrayDeque<>();
    private volatile com.mcaibridge.world.Pathfinder pathfinder;
    private long lastPathCalc;
    private int walkFloorY = Integer.MIN_VALUE;
    private double sidestepX, sidestepZ;
    private long sidestepUntil;
    private long fallGuardCooldownUntil;
    private volatile String walkAbortReason;
    private long skipMovesUntil;
    /** 被击退速度（SetEntityMotion(self) 原始值；M2 物理引擎消费）。 */
    private volatile org.cloudburstmc.math.vector.Vector3d knockbackVelocity;
    private static final double EYE_HEIGHT = 1.62;

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

    public void setWorld(WorldModel world) {
        this.world = world;
    }

    public void setExecutor(ActionExecutor executor) {
        this.executor = executor;
    }

    public void setSurvival(com.mcaibridge.world.SurvivalManager survival) {
        this.survival = survival;
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
            bot.send(new ServerboundAcceptTeleportationPacket(p.getId()));
            if (p.getRelatives().isEmpty()) {
                org.cloudburstmc.math.vector.Vector3d pos = p.getPosition();
                this.x = pos.getX();
                this.y = pos.getY();
                this.z = pos.getZ();
                this.yaw = p.getYRot();
                this.pitch = p.getXRot();
                this.hasPos = true;
                log.info("位置同步: ({}, {}, {})", x, y, z);
                aim.snap((float) yaw, (float) pitch);
                syncPhysics();
                // 传送确认竞态防护：旧位置的移动包会让服务器拒绝传送并弹回
                skipMovesUntil = System.currentTimeMillis() + 300;
            }
        } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket p) {
            com.mcaibridge.world.SurvivalManager sm = survival;
            if (sm != null && p.getEntityId() == sm.entityId()) {
                knockbackVelocity = p.getMovement();
                log.info("收到自身击退速度: ({}, {}, {})", p.getMovement().getX(), p.getMovement().getY(), p.getMovement().getZ());
            }
        }
    }

    /** 服务器位置校正/重生：物理引擎硬重置。 */
    private void syncPhysics() {
        if (world == null) return;
        if (physics == null) {
            physics = new com.mcaibridge.physics.PhysicsEngine(world, x, y, z);
            log.info("物理引擎已启用（50ms/tick，重力+碰撞+流体）");
        } else {
            physics.teleport(x, y, z);
        }
    }

    /**
     * 面向目标（自然转向，攻击前等待 isAimed()）。
     */
    public void faceTo(double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - (y + EYE_HEIGHT), dz = tz - z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float tyaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tpitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        aim.aimAt(tyaw, tpitch);
    }

    /** 瞄准是否到位（误差 <4°）。 */
    public boolean isAimed() {
        return aim.isAimed();
    }

    /** 脱困跳。 */
    public void jumpNow() {
        if (physics != null) physics.requestJump();
    }

    /** 卡住侧移（MoveController 用）：1.2 秒横向偏置 + 跳，不清除最终目标。 */
    public void sidestep(double dx, double dz) {
        sidestepX = dx;
        sidestepZ = dz;
        sidestepUntil = System.currentTimeMillis() + 1200;
        pathQueue.clear();
        jumpNow();
    }

    /** 行走被安全护栏中止的原因（一次消费）。 */
    public String consumeWalkAbort() {
        String r = walkAbortReason;
        walkAbortReason = null;
        return r;
    }

    /** 兼容旧调用：等价 sidestep。 */
    public void nudgeTo(double x, double z) {
        double[] p = position();
        sidestep(x - p[0], z - p[2]);
    }

    /** 被击退速度（未收到时 null）。 */
    public org.cloudburstmc.math.vector.Vector3d consumeKnockback() {
        org.cloudburstmc.math.vector.Vector3d v = knockbackVelocity;
        knockbackVelocity = null;
        return v;
    }

    /** 物理状态（挖掘 ÷5 罚系数判定用；无物理时 false）。 */
    public boolean physicsInWater() {
        return physics != null && physics.isInWater();
    }

    public boolean physicsOffGround() {
        return physics != null && !physics.isOnGround();
    }

    // ---- 指令 API（动作执行器/意图解析调用）----

    public void follow(String playerName) {
        followTarget = playerName;
        moveTargetX = null;
        log.info("开始跟随 {}", playerName);
    }

    public void walkTo(double tx, double tz) {
        followTarget = null;
        moveTargetX = tx;
        moveTargetZ = tz;
        computePathIfPossible(tx, tz, false);
    }

    /** A* 寻路（同目标 1.5s 内不重算；不可达回落直线）。 */
    private void computePathIfPossible(double tx, double tz, boolean force) {
        long now = System.currentTimeMillis();
        if (world == null || physics == null) return;
        if (!force && now - lastPathCalc < 1500) return;
        if (!force && !pathQueue.isEmpty()) return; // 现有航点仍有效
        lastPathCalc = now;
        double[] p = position();
        int sx = (int) Math.floor(p[0]), sy = (int) Math.floor(p[1]), sz = (int) Math.floor(p[2]);
        int gx = (int) Math.floor(tx), gz = (int) Math.floor(tz);
        int gy = world.groundY(gx, gz, sy);
        gy = gy == WorldModel.UNKNOWN ? sy : gy + 1;
        // 行走底线：只容许一次合法 ≤3 格下落，再低视为坠入洞穴立即停止
        walkFloorY = Math.min(sy, gy) - 3;
        if (pathfinder == null) pathfinder = new com.mcaibridge.world.Pathfinder(world);
        var path = pathfinder.find(sx, sy, sz, gx, gy, gz);
        pathQueue.clear();
        if (path == null) return; // 不可达：直线模式（物理碰撞兜底）
        for (var v : path) {
            if (pathQueue.size() >= 200) break;
            pathQueue.add(new double[]{v.getX() + 0.5, v.getY(), v.getZ() + 0.5});
        }
        log.debug("寻路: {} 段航点 → ({}, {})", pathQueue.size(), gx, gz);
    }

    public void stopMoving() {
        followTarget = null;
        moveTargetX = null;
        moveTargetZ = null;
        pathQueue.clear();
    }

    public boolean isMoving() {
        return moveTargetX != null && moveTargetZ != null;
    }

    /** 是否在跟随某玩家（插件查询模式）。 */
    public boolean isFollowing() {
        return followTarget != null;
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

    /** 当前位置 [x, y, z]；未同步时各分量为 0。 */
    public double[] position() {
        return new double[]{x, y, z};
    }

    public String positionString() {
        return hasPos ? String.format("(%.1f, %.1f, %.1f)", x, y, z) : "(未知)";
    }

    // ---- 内部 ----

    private void tick() {
        try {
            ActionExecutor ex = executor;
            if (ex != null) ex.tick();
            if (!hasPos) return;
            if (System.currentTimeMillis() < skipMovesUntil) return; // 传送后静默期，防竞态弹回

            // 击退速度注入物理引擎
            var kb = consumeKnockback();
            if (kb != null && physics != null) {
                physics.setVelocity(kb.getX(), kb.getY(), kb.getZ());
            }

            Double tx = moveTargetX, tz = moveTargetZ;
            boolean moving = tx != null && tz != null;
            if (moving) {
                double dx = tx - x, dz = tz - z;
                if (dx * dx + dz * dz <= ARRIVE_DIST * ARRIVE_DIST) {
                    moveTargetX = null;
                    moveTargetZ = null;
                    pathQueue.clear();
                    moving = false;
                }
            }

            if (physics != null) {
                // 物理路径：意图 → 50ms×2 积分 → 采点发包
                // 坠落底线：低于本次行走允许的最低层就停下（掉出底线说明走进了不该走的路）
                if (moving && y < walkFloorY) {
                    log.info("行走中止：y={} 低于底线 {}（疑似坠落）", (int) y, walkFloorY);
                    walkAbortReason = "跌落过低";
                    stopMoving();
                    moving = false;
                }
                moveHelper.tick(moving, x, y, z);
                if (moving) {
                    // A* 航点：出队到首个未达航点；沿途耗尽且离目标远则重算
                    double[] wp = null;
                    while (!pathQueue.isEmpty()) {
                        double[] f = pathQueue.peek();
                        double dxw = f[0] - x, dzw = f[2] - z;
                        if (dxw * dxw + dzw * dzw < 0.36 && Math.abs(f[1] - y) < 1.6) {
                            pathQueue.poll();
                        } else {
                            wp = f;
                            break;
                        }
                    }
                    double gx = tx, gz = tz;
                    if (wp != null) {
                        gx = wp[0];
                        gz = wp[2];
                    }
                    // 防坠落护栏：前进方向若是 >3 格深坑则急停（A* 不可达时直线兜底的安全网）
                    if (System.currentTimeMillis() > fallGuardCooldownUntil) {
                        double pdx = gx - x, pdz = gz - z;
                        double plen = Math.sqrt(pdx * pdx + pdz * pdz);
                        if (plen > 0.3) {
                            int px = (int) Math.floor(x + pdx / plen * 1.2);
                            int pz = (int) Math.floor(z + pdz / plen * 1.2);
                            int feetY = (int) Math.floor(y);
                            int g = world.groundY(px, pz, feetY);
                            if (g != WorldModel.UNKNOWN && (feetY - (g + 1)) > 3) {
                                // 深坑：水平急停 + 停止行走并如实汇报（安全优先）
                                physics.haltHorizontal();
                                walkAbortReason = "前方有深坑";
                                log.info("行走中止: 前方 ({}, {}) 是 >3 格深坑", px, pz);
                                stopMoving();
                                return;
                            }
                        }
                    }
                    double dx = gx - x, dz = gz - z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    boolean sprint = Math.sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z)) > 10
                            && survival != null && survival.entityId() > 0;
                    double speed = sprint ? com.mcaibridge.physics.VanillaPhysics.SPRINT_SPEED
                            : com.mcaibridge.physics.VanillaPhysics.WALK_SPEED;
                    // 侧移脱困偏置
                    double biasX = 0, biasZ = 0;
                    if (System.currentTimeMillis() < sidestepUntil) {
                        biasX = sidestepX * 4;
                        biasZ = sidestepZ * 4;
                    }
                    physics.setIntent(dx + biasX, dz + biasZ, speed, sprint);
                    if (physics.isCollidedHorizontal() || physics.isInWater() || physics.isOnLadder()) {
                        physics.requestJump();
                    }
                    updateSprint(sprint);
                    if (!aim.hasTarget()) {
                        yaw = (float) Math.toDegrees(Math.atan2(-(dx + biasX), dz + biasZ));
                        pitch = 0;
                    }
                    // 航点耗尽仍在追目标 → 周期重算
                    if (pathQueue.isEmpty() && System.currentTimeMillis() - lastPathCalc > 2500) {
                        computePathIfPossible(tx, tz, true);
                    }
                } else {
                    physics.setIntent(0, 0, 0, false);
                    updateSprint(false);
                    if (followTarget != null && System.currentTimeMillis() - lastWhereQuery > WHERE_INTERVAL_MS) {
                        lastWhereQuery = System.currentTimeMillis();
                        queryWhere(followTarget);
                    }
                }
                physics.tick();
                physics.tick();
                x = physics.getX();
                y = physics.getY();
                z = physics.getZ();
                onGround = physics.isOnGround();
                yaw = aim.tickYaw();
                pitch = aim.tickPitch();
                sendMove();
                idleCounter.set(0);
                return;
            }

            // 无世界模型时的直线路径（迭代二行为）
            if (moving) {
                double dx = tx - x, dz = tz - z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double step = Math.min(WALK_SPEED * TICK_MS / 1000.0, dist);
                double nx = x + dx / dist * step;
                double nz = z + dz / dist * step;
                if (applyTerrain(nx, nz)) {
                    x = nx;
                    z = nz;
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    sendMove();
                }
                idleCounter.set(0);
                return;
            }
            if (followTarget != null && System.currentTimeMillis() - lastWhereQuery > WHERE_INTERVAL_MS) {
                lastWhereQuery = System.currentTimeMillis();
                queryWhere(followTarget);
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

    /** 疾跑状态包（状态变化才发）。 */
    private void updateSprint(boolean sprint) {
        if (sprint == sprintState) return;
        sprintState = sprint;
        if (survival != null && survival.entityId() > 0) {
            com.mcaibridge.protocol.ActionStateSender.setSprinting(bot, survival.entityId(), sprint);
        }
    }

    /**
     * 世界感知贴地：返回 false 表示目标列不可走（未加载/撞墙/深崖），已自动停下并通知执行器。
     */
    private boolean applyTerrain(double nx, double nz) {
        WorldModel wm = world;
        if (wm == null) return true; // 无世界模型：沿用服务器同步 y（迭代二行为）
        int bx = (int) Math.floor(nx);
        int bz = (int) Math.floor(nz);
        int feetY = (int) Math.floor(y);
        int g = wm.groundY(bx, bz, feetY);
        if (g == WorldModel.UNKNOWN) {
            stopMoving();
            notifyBlocked("前方地形未知（区块未加载）");
            return false;
        }
        int targetFeet = g + 1;
        if (targetFeet > feetY + 1) {
            stopMoving();
            notifyBlocked("前方有墙/高差超过 1 格");
            return false;
        }
        if (targetFeet < feetY - 3) {
            stopMoving();
            notifyBlocked("前方是超过 3 格的深坑/悬崖");
            return false;
        }
        y = targetFeet;
        onGround = true;
        return true;
    }

    private void notifyBlocked(String reason) {
        log.info("行走受阻: {}", reason);
        ActionExecutor ex = executor;
        if (ex != null) ex.onWalkBlocked(reason);
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
