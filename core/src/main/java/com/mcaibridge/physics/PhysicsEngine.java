package com.mcaibridge.physics;

import com.mcaibridge.world.WorldModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 50ms/tick 客户端物理积分：重力+阻力+AABB 碰撞+跳跃+流体+爬梯。
 * 位置以物理引擎为准（PlayerController 每 100ms 采一次并发位置包）；
 * 服务器位置校正（传送包）经 teleport() 重置；击退速度经 setVelocity() 注入。
 * 未加载区块视为实心墙（防止走进未知地形坠落）。
 */
public class PhysicsEngine {
    private static final Logger log = LoggerFactory.getLogger(PhysicsEngine.class);
    private static final double EPS = 1e-6;

    private final WorldModel world;

    private double x, y, z;
    private double vx, vy, vz;
    private boolean onGround = true;
    private boolean collidedHorizontal;
    private boolean inWater, inLava, onLadder, headInWater;
    private boolean sprinting;
    private boolean jumpRequested;

    private double intentX, intentZ; // 目标速度（格/tick）
    private boolean hasIntent;

    public PhysicsEngine(WorldModel world, double x, double y, double z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // ---- 外部输入 ----

    /** 移动意图：方向单位向量 × 期望速度。 */
    public void setIntent(double dirX, double dirZ, double speedPerTick, boolean sprint) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < EPS || speedPerTick <= 0) {
            hasIntent = false;
            intentX = 0;
            intentZ = 0;
        } else {
            hasIntent = true;
            intentX = dirX / len * speedPerTick;
            intentZ = dirZ / len * speedPerTick;
        }
        this.sprinting = sprint;
    }

    public void requestJump() {
        jumpRequested = true;
    }

    /** 击退等外部速度（SetEntityMotion，格/tick）。 */
    public void setVelocity(double vx, double vy, double vz) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /** 服务器位置校正/重生/传送：硬重置。 */
    public void teleport(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = this.vy = this.vz = 0;
        this.onGround = true;
    }

    // ---- 主循环（50ms）----

    public void tick() {
        FluidHandler.FluidState f = FluidHandler.sample(world, x, y, z);
        inWater = f.inWater();
        inLava = f.inLava();
        onLadder = f.onLadder();
        headInWater = f.headInWater();

        // 水平控制：向意图速度插值（地面响应快、空中弱控、水中折中）
        double accel = onGround ? 0.5 : (inWater || inLava ? 0.25 : 0.12);
        vx += (intentX - vx) * accel;
        vz += (intentZ - vz) * accel;

        if (inWater || inLava) {
            vy = vy * VanillaPhysics.WATER_DRAG - VanillaPhysics.WATER_GRAVITY;
            if (jumpRequested && hasIntent) vy += VanillaPhysics.SWIM_UP; // 游泳上浮
            if (onLadder) vy = Math.max(vy, VanillaPhysics.LADDER_SPEED);
        } else if (onLadder) {
            vy = hasIntent ? VanillaPhysics.LADDER_SPEED : 0;
        } else {
            vy = (vy - VanillaPhysics.GRAVITY) * VanillaPhysics.DRAG_VERTICAL;
            if (onGround && jumpRequested) {
                vy = VanillaPhysics.JUMP_VELOCITY;
                if (sprinting && hasIntent) { // 疾跑跳水平加成
                    vx += intentX * VanillaPhysics.SPRINT_JUMP_BOOST / Math.max(VanillaPhysics.SPRINT_SPEED, EPS) * VanillaPhysics.SPRINT_SPEED;
                    vz += intentZ * VanillaPhysics.SPRINT_JUMP_BOOST;
                }
                onGround = false;
            }
        }
        jumpRequested = false;

        collidedHorizontal = false;
        moveWithCollision();

        // 空中水平阻力
        if (!onGround && !inWater) {
            vx *= VanillaPhysics.DRAG_AIR;
            vz *= VanillaPhysics.DRAG_AIR;
        }
    }

    // ---- 碰撞移动（分轴 + 子步防穿透）----

    private void moveWithCollision() {
        double max = Math.max(Math.abs(vx), Math.max(Math.abs(vy), Math.abs(vz)));
        int steps = (int) Math.min(20, Math.max(1, Math.ceil(max / 0.4)));
        double sx = vx / steps, sy = vy / steps, sz = vz / steps;
        for (int i = 0; i < steps; i++) {
            // Y
            if (sy != 0) {
                double ny = y + sy;
                if (collides(x, ny, z)) {
                    if (sy < 0) {
                        y = Math.min(y, Math.floor(ny) + 1.0);
                        onGround = true;
                    } else {
                        y = Math.max(y, Math.ceil(ny + VanillaPhysics.HEIGHT) - 1.0 - VanillaPhysics.HEIGHT);
                    }
                    vy = 0;
                    sy = 0;
                } else {
                    y = ny;
                    if (sy < 0) onGround = false;
                }
            }
            // X
            if (sx != 0) {
                double nx = x + sx;
                if (collides(nx, y, z)) {
                    if (sx > 0) x = Math.min(x, Math.floor(nx + VanillaPhysics.HALF_WIDTH) - VanillaPhysics.HALF_WIDTH - EPS);
                    else x = Math.max(x, Math.floor(nx - VanillaPhysics.HALF_WIDTH) + 1.0 + VanillaPhysics.HALF_WIDTH + EPS);
                    vx = 0;
                    collidedHorizontal = true;
                    sx = 0;
                } else {
                    x = nx;
                }
            }
            // Z
            if (sz != 0) {
                double nz = z + sz;
                if (collides(x, y, nz)) {
                    if (sz > 0) z = Math.min(z, Math.floor(nz + VanillaPhysics.HALF_WIDTH) - VanillaPhysics.HALF_WIDTH - EPS);
                    else z = Math.max(z, Math.floor(nz - VanillaPhysics.HALF_WIDTH) + 1.0 + VanillaPhysics.HALF_WIDTH + EPS);
                    vz = 0;
                    collidedHorizontal = true;
                    sz = 0;
                } else {
                    z = nz;
                }
            }
        }
        // 站立确认：脚下一格内无支撑则离地
        if (!onGround && vy <= 0 && collides(x, y - 0.02, z)) {
            onGround = true;
        }
    }

    /** AABB [px±0.3, py..py+1.8, pz±0.3] 是否与实心方块相交。 */
    private boolean collides(double px, double py, double pz) {
        int x0 = (int) Math.floor(px - VanillaPhysics.HALF_WIDTH);
        int x1 = (int) Math.floor(px + VanillaPhysics.HALF_WIDTH);
        int y0 = (int) Math.floor(py);
        int y1 = (int) Math.floor(py + VanillaPhysics.HEIGHT);
        int z0 = (int) Math.floor(pz - VanillaPhysics.HALF_WIDTH);
        int z1 = (int) Math.floor(pz + VanillaPhysics.HALF_WIDTH);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (solidAt(bx, by, bz)) return true;
                }
            }
        }
        return false;
    }

    private boolean solidAt(int bx, int by, int bz) {
        int state = world.blockAt(bx, by, bz);
        if (state == WorldModel.UNKNOWN) return true; // 未加载=墙
        if (state == 0) return false;
        return !com.mcaibridge.world.BlockIds.isFluid(state) && !com.mcaibridge.world.BlockIds.isLadder(state);
    }

    // ---- 状态读取 ----

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public boolean isOnGround() { return onGround; }
    public boolean isCollidedHorizontal() { return collidedHorizontal; }
    public boolean isInWater() { return inWater; }
    public boolean isOnLadder() { return onLadder; }
    public boolean isHeadInWater() { return headInWater; }
}
