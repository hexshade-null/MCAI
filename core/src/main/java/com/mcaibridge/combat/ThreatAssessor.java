package com.mcaibridge.combat;

import com.mcaibridge.world.EntityTracker.TrackedEntity;

/**
 * 威胁评估：目标威胁等级（供战斗状态机决策）。
 * 数值参考 minecraft.wiki 各生物页基础数据（2026-08-31）。
 */
public final class ThreatAssessor {
    private ThreatAssessor() {
    }

    public enum Level {
        NONE, LOW, MEDIUM, HIGH, DEADLY
    }

    /** 各类型基础威胁分（血量×攻击性近似）。 */
    private static final java.util.Map<String, Integer> BASE_THREAT = java.util.Map.ofEntries(
            java.util.Map.entry("ZOMBIE", 6), java.util.Map.entry("SKELETON", 8),
            java.util.Map.entry("CREEPER", 10), java.util.Map.entry("SPIDER", 6),
            java.util.Map.entry("WITCH", 9), java.util.Map.entry("ENDERMAN", 10),
            java.util.Map.entry("DROWNED", 6), java.util.Map.entry("PILLAGER", 8),
            java.util.Map.entry("VINDICATOR", 9), java.util.Map.entry("RAVAGER", 10),
            java.util.Map.entry("PHANTOM", 7), java.util.Map.entry("SLIME", 4),
            java.util.Map.entry("BOGGED", 8), java.util.Map.entry("BREEZE", 8));

    /** 评估：自身血量越低威胁感知越高，距离越近越高。 */
    public static Level assess(TrackedEntity target, double selfX, double selfZ, float selfHealth) {
        if (target == null) return Level.NONE;
        int base = BASE_THREAT.getOrDefault(target.type.name(), 5);
        double d = Math.sqrt(target.dist2(selfX, selfZ));
        double proximity = d <= 3 ? 2.0 : d <= 6 ? 1.3 : 1.0;
        double healthFactor = selfHealth <= 6 ? 1.8 : selfHealth <= 10 ? 1.4 : 1.0;
        double score = base * proximity * healthFactor;
        if (score >= 16) return Level.DEADLY;
        if (score >= 10) return Level.HIGH;
        if (score >= 6) return Level.MEDIUM;
        return Level.LOW;
    }
}
