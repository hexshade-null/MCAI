package com.mcaibridge.action;

import com.mcaibridge.core.PlayerController;

/**
 * 移动辅助：卡住检测（3 秒位移≈0 → 跳+随机侧移脱困）、侧移、逃离。
 * 由 PlayerController 每拍调用 tick()；脱困动作直接下发到物理意图。
 */
public class MoveController {
    private static final long STUCK_MS = 3000;
    private static final double STUCK_EPS = 0.05;

    private final PlayerController controller;
    private double lastX, lastZ;
    private long lastMoveAt;
    private long sidestepUntil;
    private double sidestepDx, sidestepDz;

    public MoveController(PlayerController controller) {
        this.controller = controller;
    }

    /**
     * @return true=正在脱困侧移（调用方保持移动意图即可）
     */
    public boolean tick(boolean moving, double x, double y, double z) {
        long now = System.currentTimeMillis();
        if (!moving) {
            lastMoveAt = now;
            lastX = x;
            lastZ = z;
            return false;
        }
        double dx = Math.abs(x - lastX), dz = Math.abs(z - lastZ);
        if (dx > STUCK_EPS || dz > STUCK_EPS) {
            lastMoveAt = now;
            lastX = x;
            lastZ = z;
            return false;
        }
        // 位置基本没变
        if (now - lastMoveAt >= STUCK_MS) {
            // 跳一下 + 垂直方向随机侧移 1.5 格
            controller.jumpNow();
            double[] p = controller.position();
            double perpX = -(z - lastZ), perpZ = (x - lastX);
            double len = Math.sqrt(perpX * perpX + perpZ * perpZ);
            if (len < 0.01) {
                perpX = 1;
                perpZ = 0;
                len = 1;
            }
            double sign = Math.random() < 0.5 ? 1 : -1;
            sidestepDx = perpX / len * 1.5 * sign;
            sidestepDz = perpZ / len * 1.5 * sign;
            sidestepUntil = now + 1200;
            controller.nudgeTo(p[0] + sidestepDx, p[2] + sidestepDz);
            lastMoveAt = now;
            lastX = x;
            lastZ = z;
            return true;
        }
        return false;
    }

    public boolean sidestepping() {
        return System.currentTimeMillis() < sidestepUntil;
    }
}
