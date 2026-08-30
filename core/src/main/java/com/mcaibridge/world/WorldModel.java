package com.mcaibridge.world;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.cloudburstmc.math.vector.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端世界模型（纯本地端言出法随的地基）：
 * 自解码 LevelChunkWithLight 的原始 section 字节流，维护方块更新，提供方块查询/贴地高度/前方阻挡判断。
 * 库不解析区块内容（getChunkData() 是裸 byte[]），此处按 1.21 网络格式手写解码。
 * 兼容两种 long 打包（1.21.5 起 entries 可跨 long 连续打包；旧版按 long 对齐），用声明的存储长度自动区分。
 * 方块状态只存数字 id（库无方块名注册表）：0=空气，UNKNOWN=-1=区块未加载。
 */
public class WorldModel {
    private static final Logger log = LoggerFactory.getLogger(WorldModel.class);
    public static final int UNKNOWN = -1;
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int MAX_CHUNKS = 256;

    /** 世界最低 y（主世界 -64；可在需要时扩展维度感知）。 */
    private final int minY;
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();

    /** 单区块：sections[sectionIdx][4096]，null=整段空气；sectionIdx 从 minY 起每 16 格一段。 */
    private static final class Chunk {
        final int[][] sections = new int[32][];
    }

    public WorldModel() {
        this(-64);
    }

    public WorldModel(int minY) {
        this.minY = minY;
    }

    public void clear() {
        chunks.clear();
        log.info("世界模型已清空（维度切换/重生）");
    }

    public void handle(Packet packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
            acceptChunk(p);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket p) {
            chunks.remove(chunkKey(p.getX(), p.getZ()));
        } else if (packet instanceof ClientboundBlockUpdatePacket p) {
            BlockChangeEntry e = p.getEntry();
            setBlock(e.getPosition(), e.getBlock());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            for (BlockChangeEntry e : p.getEntries()) {
                setBlock(e.getPosition(), e.getBlock());
            }
        } else if (packet instanceof ClientboundRespawnPacket) {
            clear();
        }
    }

    // ---- 查询 ----

    /** 方块状态 id；UNKNOWN=区块未加载，0=空气。 */
    public int blockAt(int x, int y, int z) {
        Chunk c = chunks.get(chunkKey(x >> 4, z >> 4));
        if (c == null) return UNKNOWN;
        int idx = sectionIndex(y);
        if (idx < 0 || idx >= c.sections.length) return 0;
        int[] sec = c.sections[idx];
        if (sec == null) return 0;
        return sec[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    /** 区块是否已加载（可走性判断的前提）。 */
    public boolean known(int x, int z) {
        return chunks.containsKey(chunkKey(x >> 4, z >> 4));
    }

    /**
     * 从 fromY 向下找首个非空气方块的 y（即地面方块本体，站上去的脚部 y = 返回值 + 1）。
     * 区块未加载返回 UNKNOWN。
     */
    public int groundY(int x, int z, int fromY) {
        Chunk c = chunks.get(chunkKey(x >> 4, z >> 4));
        if (c == null) return UNKNOWN;
        int start = Math.min(fromY + 2, minY + c.sections.length * 16 - 1);
        for (int y = start; y > minY; y--) {
            int idx = sectionIndex(y);
            if (idx < 0) break;
            int[] sec = c.sections[idx];
            if (sec == null) {
                y = minY + (idx << 4); // 整段空气，跳到段底；循环 y-- 落到下一段顶部
                continue;
            }
            if (sec[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] != 0) return y;
        }
        return minY;
    }

    /**
     * 行走可站性：目标列地面与脚部高差需在 [-3, +1] 内（可掉 ≤3 格、可跨上 1 格）。
     */
    public boolean standable(int x, int z, int feetY) {
        int g = groundY(x, z, feetY);
        if (g == UNKNOWN) return false;
        int targetFeet = g + 1;
        return targetFeet <= feetY + 1 && targetFeet >= feetY - 3;
    }

    // ---- 写入 ----

    private void setBlock(Vector3i pos, int stateId) {
        Chunk c = chunks.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
        if (c == null) return;
        int idx = sectionIndex(pos.getY());
        if (idx < 0 || idx >= c.sections.length) return;
        int[] sec = c.sections[idx];
        if (sec == null) {
            if (stateId == 0) return;
            sec = new int[SECTION_VOLUME];
            c.sections[idx] = sec;
        }
        sec[((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15)] = stateId;
    }

    private void acceptChunk(ClientboundLevelChunkWithLightPacket p) {
        int si = 0;
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(p.getChunkData());
            Chunk c = new Chunk();
            while (buf.isReadable() && si < c.sections.length) {
                buf.readShort(); // blockCount（整段空气时为 0，但仍带 palette 结构）
                c.sections[si] = readPaletted(buf, SECTION_VOLUME, 8);
                readPaletted(buf, 64, 3); // 生物群系 4x4x4，丢弃
                si++;
            }
            if (buf.isReadable()) {
                log.warn("区块 ({}, {}) 解码后有剩余字节({})，格式可能不匹配", p.getX(), p.getZ(), buf.readableBytes());
            }
            evictIfNeeded(p.getX(), p.getZ());
            chunks.put(chunkKey(p.getX(), p.getZ()), c);
        } catch (Exception e) {
            log.warn("区块 ({}, {}) 解码失败于段 {}: {} | head={}", p.getX(), p.getZ(), si, e.toString(), hexHead(p.getChunkData()));
        }
    }

    private static String hexHead(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(40, data.length); i++) {
            sb.append(String.format("%02x", data[i])).append(i % 4 == 3 ? ' ' : '/');
        }
        return sb.toString();
    }

    private void evictIfNeeded(int cx, int cz) {
        if (chunks.size() < MAX_CHUNKS) return;
        Long farKey = null;
        double farDist = -1;
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            long k = e.getKey();
            int ex = (int) (k >> 32);
            int ez = (int) (k & 0xffffffffL);
            double d = (ex - cx) * (double) (ex - cx) + (ez - cz) * (double) (ez - cz);
            if (d > farDist) {
                farDist = d;
                farKey = k;
            }
        }
        if (farKey != null) chunks.remove(farKey);
    }

    /**
     * 读一个 palette 容器（方块/生物群系通用）。返回逐体素展开的状态数组；整段单一值返回 null。
     * 1.21.11 网络格式（实测校准，见 TEST_REPORT）：bpe 字节；bpe=0 → 单一值 varInt；
     * 否则 indirect（bpe≤阈值）带 palette varInt 列表；存储按 long 对齐打包（条目不跨 long），
     * **无长度前缀**，long 数 = ceil(volume / floor(64/bpe))。
     */
    private int[] readPaletted(ByteBuf buf, int volume, int maxIndirectBits) {
        int bpe = buf.readUnsignedByte();
        if (bpe == 0) {
            MinecraftTypes.readVarInt(buf); // 单一值（空气段=0）；统一按空气处理
            return null;
        }
        int[] palette = null;
        if (bpe <= maxIndirectBits) {
            int n = MinecraftTypes.readVarInt(buf);
            palette = new int[n];
            for (int i = 0; i < n; i++) palette[i] = MinecraftTypes.readVarInt(buf);
        }
        int perLong = 64 / bpe;
        int longs = (volume + perLong - 1) / perLong;
        long[] storage = new long[longs];
        for (int i = 0; i < longs; i++) storage[i] = buf.readLong();

        int mask = (1 << bpe) - 1;
        int[] out = new int[volume];
        for (int i = 0; i < volume; i++) {
            int id = (int) ((storage[i / perLong] >>> ((i % perLong) * bpe)) & mask);
            if (palette != null) {
                if (id >= palette.length) {
                    throw new IllegalStateException("palette 索引越界 id=" + id + " size=" + palette.length + " bpe=" + bpe);
                }
                out[i] = palette[id];
            } else {
                out[i] = id;
            }
        }
        return out;
    }

    private int sectionIndex(int y) {
        return (y - minY) >> 4;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }
}
