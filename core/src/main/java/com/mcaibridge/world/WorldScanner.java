package com.mcaibridge.world;

import org.cloudburstmc.math.vector.Vector3i;

import java.util.function.IntPredicate;

/**
 * 块级世界扫描：在机器人周围已加载区块内按过滤器找最近方块（言出法随"找树/找矿"的感知层）。
 * 过滤器基于实测 BlockIds；范围立方逐圈扫描，半径≤16。
 */
public class WorldScanner {
    public interface BlockFilter extends IntPredicate {
    }

    public static final BlockFilter LOGS = BlockIds::isLog;
    public static final BlockFilter ORES = BlockIds::isOre;
    public static final BlockFilter WATER = BlockIds::isWater;

    private final WorldModel world;

    public WorldScanner(WorldModel world) {
        this.world = world;
    }

    public record Hit(Vector3i pos, int stateId, double dist2) {
    }

    /** 找最近的匹配方块；找不到返回 null。 */
    public Hit findNearest(double cx, double cy, double cz, BlockFilter filter, int radius) {
        int bx = (int) Math.floor(cx);
        int by = (int) Math.floor(cy);
        int bz = (int) Math.floor(cz);
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= radius && best == null; r++) {
            // 逐圈（近似）：立方壳
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue; // 只扫当前壳
                        int sx = bx + dx, sy = by + dy, sz = bz + dz;
                        int state = world.blockAt(sx, sy, sz);
                        if (state <= 0) continue;
                        if (!filter.test(state)) continue;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = new Hit(Vector3i.from(sx, sy, sz), state, d);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 顶面高度（贴地行走/落地判断）。 */
    public int surfaceY(int x, int z, int fromY) {
        return world.groundY(x, z, fromY);
    }
}
