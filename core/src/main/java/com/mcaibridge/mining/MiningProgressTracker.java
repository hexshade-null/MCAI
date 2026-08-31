package com.mcaibridge.mining;

import com.mcaibridge.core.MCBot;
import com.mcaibridge.world.SurvivalManager;
import com.mcaibridge.world.WorldModel;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.cloudburstmc.math.vector.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 挖掘进度跟踪（wiki Breaking 公式实装）：
 * START → 按公式计时（每 250ms 挥臂）→ FINISH；方块变更/目标丢失自动终止。
 * 实际否掉落由服务端按 canHarvest 判定（空手挖石无掉落）。
 */
public class MiningProgressTracker {
    private static final Logger log = LoggerFactory.getLogger(MiningProgressTracker.class);
    private static final long SWING_INTERVAL_MS = 250;

    private final MCBot bot;
    private final WorldModel world;
    private final SurvivalManager survival;

    private Vector3i pos;
    private int originalState;
    private long deadline;
    private long lastSwing;
    private double expectedSeconds;
    private long beginAt;
    /** 实测挖掘参数（供日志/测试断言）。 */
    private String debugInfo = "";

    public MiningProgressTracker(MCBot bot, WorldModel world, SurvivalManager survival) {
        this.bot = bot;
        this.world = world;
        this.survival = survival;
    }

    /**
     * 开始挖掘。offGround/inWater 触发 wiki ÷5 罚系数。
     * 返回 false 表示目标不可挖（未知/流体/不可破坏/区块未加载）。
     */
    public boolean begin(Vector3i target, boolean offGround, boolean inWater) {
        int stateId = world.blockAt(target.getX(), target.getY(), target.getZ());
        if (stateId == WorldModel.UNKNOWN || BlockHardnessRegistry.isUnbreakable(stateId)) {
            log.info("目标不可挖: state={} ({})", stateId, com.mcaibridge.world.BlockIds.name(stateId));
            return false;
        }
        this.pos = target;
        this.originalState = stateId;
        int held = survival.heldItem();
        double speed = ToolSpeedRegistry.speedFor(held, stateId);
        boolean harvest = HarvestChecker.canHarvest(held, stateId);
        this.expectedSeconds = BlockHardnessRegistry.breakSeconds(stateId, speed, harvest, offGround, inWater);
        this.deadline = System.currentTimeMillis() + (long) (expectedSeconds * 1000) + 120;
        this.beginAt = System.currentTimeMillis();
        this.lastSwing = 0;
        this.debugInfo = String.format("state=%d(%s) 工具=%s 速度=%.1f 采集=%s 预计=%.2fs",
                stateId, com.mcaibridge.world.BlockIds.name(stateId),
                ToolSpeedRegistry.tool(held).type(), speed, harvest, expectedSeconds);
        log.info("开始挖掘 {} | {}", target, debugInfo);
        bot.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, target, Direction.DOWN, 0));
        return true;
    }

    /** 由动作执行器 100ms 驱动；返回 true 表示完成或终止。 */
    public boolean tick() {
        if (pos == null) return true;
        long now = System.currentTimeMillis();
        if (now - lastSwing >= SWING_INTERVAL_MS) {
            lastSwing = now;
            bot.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }
        int cur = world.blockAt(pos.getX(), pos.getY(), pos.getZ());
        if (cur != originalState) {
            log.info("挖掘目标已变更/消失（{}→{}），结束", originalState, cur);
            pos = null;
            return true;
        }
        if (now >= deadline) {
            bot.send(new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, pos, Direction.DOWN, 0));
            bot.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
            log.info("挖掘完成: {} 实测={}s | {}", pos,
                    String.format("%.2f", (now - beginAt) / 1000.0), debugInfo);
            pos = null;
            return true;
        }
        return false;
    }

    public void abort() {
        if (pos != null) {
            bot.send(new ServerboundPlayerActionPacket(PlayerAction.CANCEL_DIGGING, pos, Direction.DOWN, 0));
            pos = null;
        }
    }

    public boolean busy() {
        return pos != null;
    }
}
