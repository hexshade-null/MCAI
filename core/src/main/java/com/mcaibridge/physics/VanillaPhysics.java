package com.mcaibridge.physics;

/**
 * 原版物理常量（来源：minecraft.wiki/w/Sprinting、/Jumping、/Breaking 及原版运动代码，
 * 2026-08-31 抓取；单方块=1 格，速度按 每秒/20=每 tick）。
 */
public final class VanillaPhysics {
    private VanillaPhysics() {
    }

    /** 重力加速度（格/tick²）：vy = (vy - 0.08) * 0.98。 */
    public static final double GRAVITY = 0.08;
    /** 竖直阻力系数。 */
    public static final double DRAG_VERTICAL = 0.98;
    /** 水平空中阻力。 */
    public static final double DRAG_AIR = 0.91;
    /** 地面摩擦（滑度 0.6 × 0.91）。 */
    public static final double DRAG_GROUND = 0.546;
    /** 起跳初速（≈1.25 格跳高）。 */
    public static final double JUMP_VELOCITY = 0.42;
    /** 疾跑跳水平加成。 */
    public static final double SPRINT_JUMP_BOOST = 0.2;
    /** 行走速度（4.317 m/s）。 */
    public static final double WALK_SPEED = 4.317 / 20.0;
    /** 疾跑速度（5.612 m/s）。 */
    public static final double SPRINT_SPEED = 5.612 / 20.0;
    /** 玩家碰撞箱：宽 0.6（半宽 0.3）、高 1.8、眼高 1.62。 */
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    /** 水中竖直运动：vy = vy*0.8 - 0.02，跳跃键每 tick +0.04。 */
    public static final double WATER_DRAG = 0.8;
    public static final double WATER_GRAVITY = 0.02;
    public static final double SWIM_UP = 0.04;
    /** 爬梯速度。 */
    public static final double LADDER_SPEED = 0.2;
}
