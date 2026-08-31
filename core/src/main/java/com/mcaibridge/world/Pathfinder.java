package com.mcaibridge.world;

import org.cloudburstmc.math.vector.Vector3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* 体素寻路：4 邻接 + 跨上 1 格（跳）+ 落差 ≤3 格（落）。
 * 未加载区块视为不可通行（从根上避免"直走掉洞"）；不可达返回 null，调用方回落直线行走。
 * 节点=可站立脚部位（脚部两格 passable、脚下 solid）。
 */
public class Pathfinder {
    private static final int MAX_EXPAND = 12000;
    private static final int OFF_X = 1 << 25; // 坐标偏移使 key 非负
    private static final int OFF_Z = 1 << 25;
    private static final int OFF_Y = 1 << 11;

    private final WorldModel world;
    private int goalY;

    public Pathfinder(WorldModel world) {
        this.world = world;
    }

    private boolean passable(int x, int y, int z) {
        int s = world.blockAt(x, y, z);
        if (s == WorldModel.UNKNOWN) return false;
        if (s == 0) return true;
        return com.mcaibridge.world.BlockIds.isFluid(s) || com.mcaibridge.world.BlockIds.isLadder(s);
    }

    private boolean standable(int x, int y, int z) {
        return passable(x, y, z) && passable(x, y + 1, z) && !passable(x, y - 1, z);
    }

    /** 寻路。返回脚部航点（不含起点、含终点邻位）；不可达 null。 */
    public List<Vector3i> find(int sx, int sy, int sz, int gx, int gy, int gz) {
        this.goalY = gy; // 目标导向：不允许绕到目标层以下（防止沿洞穴阶梯级联下坠）
        long startKey = key(sx, sy, sz);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        gScore.put(startKey, 0.0);
        open.add(new double[]{h(sx, sy, sz, gx, gy, gz), 0, sx, sy, sz});
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < MAX_EXPAND) {
            double[] cur = open.poll();
            double g = cur[1];
            int cx = (int) cur[2], cy = (int) cur[3], cz = (int) cur[4];
            long ck = key(cx, cy, cz);
            if (g > gScore.getOrDefault(ck, Double.MAX_VALUE)) continue; // 过期条目

            int ddx = cx - gx, ddz = cz - gz;
            if (ddx * ddx + ddz * ddz <= 2 && Math.abs(cy - gy) <= 3) {
                return reconstruct(cameFrom, ck);
            }
            for (int[] n : neighbors(cx, cy, cz)) {
                long nk = key(n[0], n[1], n[2]);
                double ng = g + n[3] / 10.0;
                if (ng < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
                    gScore.put(nk, ng);
                    cameFrom.put(nk, ck);
                    open.add(new double[]{ng + h(n[0], n[1], n[2], gx, gy, gz), ng, n[0], n[1], n[2]});
                }
            }
        }
        return null;
    }

    /** 邻居：平走 / 上 1（跳）/ 下 1-3（落）。{x,y,z,代价×10}。 */
    private List<int[]> neighbors(int x, int y, int z) {
        List<int[]> out = new ArrayList<>(8);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (standable(nx, y, nz)) {
                out.add(new int[]{nx, y, nz, 10});
            } else if (passable(nx, y, nz) && passable(x, y + 2, z) && passable(nx, y + 2, nz)
                    && standable(nx, y + 1, nz)) {
                out.add(new int[]{nx, y + 1, nz, 14}); // 跳上
            } else {
                for (int drop = 1; drop <= 3; drop++) {
                    if (y - drop < goalY - 1) break; // 不降到目标层以下
                    if (standable(nx, y - drop, nz)) {
                        out.add(new int[]{nx, y - drop, nz, 10 + drop * 20}); // 高落价：宁可绕路不跳崖
                        break;
                    }
                    if (!passable(nx, y - drop, nz)) break;
                }
            }
        }
        return out;
    }

    private List<Vector3i> reconstruct(Map<Long, Long> cameFrom, long endKey) {
        List<Vector3i> path = new ArrayList<>();
        long k = endKey;
        while (cameFrom.containsKey(k)) {
            path.add(unpack(k));
            k = cameFrom.get(k);
        }
        Collections.reverse(path);
        return path;
    }

    private static long key(int x, int y, int z) {
        long ux = (long) (x + OFF_X) & 0x3FFFFFF;
        long uz = (long) (z + OFF_Z) & 0x3FFFFFF;
        long uy = (long) (y + OFF_Y) & 0xFFF;
        return (ux << 38) | (uz << 12) | uy;
    }

    private static Vector3i unpack(long k) {
        int x = (int) ((k >>> 38) & 0x3FFFFFF) - OFF_X;
        int z = (int) ((k >>> 12) & 0x3FFFFFF) - OFF_Z;
        int y = (int) (k & 0xFFF) - OFF_Y;
        return Vector3i.from(x, y, z);
    }

    private static int h(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy) * 2;
    }
}
