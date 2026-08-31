package com.mcaibridge.combat;

import com.mcaibridge.mining.ToolSpeedRegistry;
import com.mcaibridge.world.SurvivalManager;

/**
 * 攻击冷却跟踪（原版 1.9 战斗）：按手持武器取冷却，攻击间隔不足则伤害打折。
 * 冷却数值来源 ToolSpeedRegistry（wiki：剑 0.625s/斧 1.0s/镐 0.833s/锹 1.0s/手 0.25s）。
 */
public class AttackCooldownTracker {
    private final SurvivalManager survival;
    private volatile long lastAttack;
    private final double minIntervalMs;

    public AttackCooldownTracker(SurvivalManager survival) {
        this.survival = survival;
        this.minIntervalMs = 250; // 绝对下限（原版手速上限 4 次/秒）
    }

    public boolean canAttack() {
        long cooldown = (long) (ToolSpeedRegistry.attackCooldownSeconds(survival.heldItem()) * 1000);
        return System.currentTimeMillis() - lastAttack >= Math.max(cooldown, minIntervalMs);
    }

    public void onAttack() {
        lastAttack = System.currentTimeMillis();
    }

    /** 冷却进度 0~1（1=完全冷却完毕，伤害全额）。 */
    public double cooldownProgress() {
        long cooldown = (long) (ToolSpeedRegistry.attackCooldownSeconds(survival.heldItem()) * 1000);
        double p = (System.currentTimeMillis() - lastAttack) / (double) cooldown;
        return Math.min(1.0, p);
    }
}
