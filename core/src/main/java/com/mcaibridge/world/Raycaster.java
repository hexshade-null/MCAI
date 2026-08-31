package com.mcaibridge.world;

import org.cloudburstmc.math.vector.Vector3i;

/**
 * 体素视线检测（Amanatides-Woo DDA）：视线是否被方块挡住、命中方块与交点。
 */
public final class Raycaster {
    private Raycaster() {
    }

    public record RayHit(Vector3i blockPos, int stateId, double distance, double hx, double hy, double hz) {
    }

    /**
     * 从 (x0,y0,z0) 沿单位方向 (dx,dy,dz) 射线，最大 maxDist；返回首个非空气方块命中，null=通畅。
     */
    public static RayHit raycast(WorldModel world, double x0, double y0, double z0,
                                 double dx, double dy, double dz, double maxDist) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return null;
        dx /= len;
        dy /= len;
        dz /= len;

        int bx = (int) Math.floor(x0);
        int by = (int) Math.floor(y0);
        int bz = (int) Math.floor(z0);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX = dx != 0 ? (dx > 0 ? (bx + 1 - x0) : (x0 - bx)) * tDeltaX : Double.MAX_VALUE;
        double tMaxY = dy != 0 ? (dy > 0 ? (by + 1 - y0) : (y0 - by)) * tDeltaY : Double.MAX_VALUE;
        double tMaxZ = dz != 0 ? (dz > 0 ? (bz + 1 - z0) : (z0 - bz)) * tDeltaZ : Double.MAX_VALUE;

        double t = 0;
        while (t <= maxDist) {
            int state = world.blockAt(bx, by, bz);
            if (state > 0 && !BlockIds.isFluid(state)) {
                double hx = x0 + dx * t, hy = y0 + dy * t, hz = z0 + dz * t;
                return new RayHit(Vector3i.from(bx, by, bz), state, t, hx, hy, hz);
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                bx += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                by += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                bz += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    /** 两点间视线是否通畅（头眼位置 → 目标点）。 */
    public static boolean lineOfSight(WorldModel world, double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        return raycast(world, x0, y0, z0, dx, dy, dz, Math.sqrt(dx * dx + dy * dy + dz * dz) - 0.2) == null;
    }
}
