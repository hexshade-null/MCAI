package com.mcaibridge.ai;

import com.mcaibridge.core.PlayerController;
import com.mcaibridge.world.EntityTracker;
import com.mcaibridge.world.SurvivalManager;
import com.mcaibridge.world.WorldScanner;

/**
 * 短期记忆/上下文管理：为 LLM 决策与战斗状态机汇总"机器人此刻知道什么"。
 * （最近攻击者字段由 combat.DamageListener 写入，M7 启用。）
 */
public class ContextManager {
    private final PlayerController controller;
    private final SurvivalManager survival;
    private final EntityTracker entities;
    private final WorldScanner scanner;

    private volatile String lastAttackerName;
    private volatile long lastAttackedAt;
    private volatile String currentTask;

    public ContextManager(PlayerController controller, SurvivalManager survival,
                          EntityTracker entities, WorldScanner scanner) {
        this.controller = controller;
        this.survival = survival;
        this.entities = entities;
        this.scanner = scanner;
    }

    public void recordAttacker(String name) {
        lastAttackerName = name;
        lastAttackedAt = System.currentTimeMillis();
    }

    public String lastAttackerName() {
        return lastAttackerName;
    }

    public long lastAttackedAt() {
        return lastAttackedAt;
    }

    public void setTask(String task) {
        this.currentTask = task;
    }

    public String task() {
        return currentTask;
    }

    /** 给 LLM 的一句话状态摘要。 */
    public String summarize() {
        double[] p = controller.position();
        StringBuilder sb = new StringBuilder();
        sb.append("位置(").append((int) p[0]).append(",").append((int) p[1]).append(",").append((int) p[2]).append(")");
        sb.append(" 血量").append((int) survival.getHealth()).append("/20");
        sb.append(" 饥饿").append(survival.getFood()).append("/20");
        if (survival.isDead()) sb.append(" [已死亡]");
        String attacker = lastAttackerName != null && System.currentTimeMillis() - lastAttackedAt < 30_000
                ? lastAttackerName : null;
        if (attacker != null) sb.append(" 刚被").append(attacker).append("攻击");
        if (currentTask != null) sb.append(" 当前任务:").append(currentTask);
        String nearby = entities.summarize(p[0], p[2]);
        if (!nearby.isBlank()) sb.append(" 附近: ").append(nearby);
        return sb.toString();
    }
}
