package com.mcaibridge.action;

/**
 * 自然瞄准：向目标平滑转向（带速度上限+轻微过冲+呼吸微动），
 * 瞄准就绪判定（误差 < 阈值）供攻击/挖掘使用。
 */
public class AimController {
    private static final double TURN_SPEED_DEG_PER_TICK = 480; // 每 50ms tick 最大转角
    private static final double AIM_TOLERANCE_DEG = 4.0;

    private float aimYaw, aimPitch;          // 目标朝向
    private float curYaw, curPitch;          // 当前朝向（向目标收敛）
    private boolean hasTarget;
    private final com.mcaibridge.action.HumanizedExecutor humanize;
    private double noiseYaw, noisePitch;
    private long lastNoise;

    public AimController(HumanizedExecutor humanize) {
        this.humanize = humanize;
    }

    /** 设定瞄准目标角度。 */
    public void aimAt(float targetYaw, float targetPitch) {
        // 归一化到当前朝向 ±180 内，避免多转圈
        this.aimYaw = curYaw + (float) normalizeAngle(targetYaw - curYaw);
        this.aimPitch = targetPitch;
        this.hasTarget = true;
    }

    /** 每 50ms 调一次；返回当前应上报的 yaw。 */
    public float tickYaw() {
        step();
        return curYaw + (float) breathNoise(true);
    }

    public float tickPitch() {
        return curPitch + (float) breathNoise(false);
    }

    public boolean isAimed() {
        if (!hasTarget) return true;
        double dyaw = Math.abs(normalizeAngle(aimYaw - curYaw));
        double dpitch = Math.abs(aimPitch - curPitch);
        return dyaw < AIM_TOLERANCE_DEG && dpitch < AIM_TOLERANCE_DEG;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public float currentYaw() {
        return curYaw;
    }

    public float currentPitch() {
        return curPitch;
    }

    /** 直接同步（重生/传送/服务器校正时）。 */
    public void snap(float yaw, float pitch) {
        this.curYaw = yaw;
        this.curPitch = pitch;
        this.aimYaw = yaw;
        this.aimPitch = pitch;
    }

    private void step() {
        if (!hasTarget) return;
        float dy = aimYaw - curYaw;
        float dp = aimPitch - curPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        // 接近目标时减速（自然减速），带 5% 过冲由下一帧修正
        double step = Math.min(TURN_SPEED_DEG_PER_TICK, Math.max(2.0, dist * 0.35));
        if (dist <= step) {
            curYaw = aimYaw;
            curPitch = aimPitch;
        } else {
            curYaw += (float) (dy / dist * step);
            curPitch += (float) (dp / dist * step);
        }
    }

    /** 呼吸微动：静止瞄准时 ±0.4° 慢漂移；瞄准噪声 ±2° 高斯（低频更新）。 */
    private double breathNoise(boolean yawAxis) {
        long now = System.currentTimeMillis();
        if (now - lastNoise > 400) {
            lastNoise = now;
            noiseYaw = humanize.aimNoiseDegrees() * 0.2;
            noisePitch = humanize.aimNoiseDegrees() * 0.1;
        }
        double t = now % 2600 / 2600.0 * Math.PI * 2;
        double breath = Math.sin(t) * 0.4;
        return yawAxis ? noiseYaw + breath : noisePitch;
    }

    private static double normalizeAngle(double a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }
}
