package com.mcaibridge.action;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 真人化节奏器：给所有动作加"人类反应"。
 * - 反应延迟：新动作开始前随机 100-400ms
 * - 攻击节奏：冷却 + 随机抖动（由 ActionExecutor 按武器冷却调用 nextAttackDelayMs）
 * - 视角抖动：瞄准叠加 ±2° 高斯噪声（AimController 消费）
 */
public class HumanizedExecutor {
    /** 下一个动作的反应延迟（ms）。 */
    public long nextReactionDelayMs() {
        return ThreadLocalRandom.current().nextLong(100, 400);
    }

    /** 攻击间隔：基础冷却外的人为抖动（ms）。 */
    public long nextAttackJitterMs() {
        return ThreadLocalRandom.current().nextLong(80, 280);
    }

    /** 瞄准噪声（度）。 */
    public double aimNoiseDegrees() {
        return ThreadLocalRandom.current().nextGaussian() * 2.0;
    }

    /** 小概率的"分神"：动作间额外停顿（约 8% 概率 +0.5-1.2s）。 */
    public long maybeDistractionMs() {
        return ThreadLocalRandom.current().nextInt(100) < 8
                ? ThreadLocalRandom.current().nextLong(500, 1200) : 0;
    }
}
