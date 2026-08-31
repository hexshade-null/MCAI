package com.mcaibridge.world;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体跟踪（纯本地端）：库无实体 registry，这里自行维护 增/移/删，
 * 并用 PlayerInfo 列表把 UUID 映射到玩家名，支撑"跟着某人/攻击某生物"等言出法随意图。
 */
public class EntityTracker {
    private static final Logger log = LoggerFactory.getLogger(EntityTracker.class);

    /** 敌对生物集合（近身攻击目标筛选用）。 */
    public static final Set<EntityType> HOSTILE = Set.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER,
            EntityType.ENDERMAN, EntityType.WITCH, EntityType.DROWNED, EntityType.HUSK,
            EntityType.STRAY, EntityType.PHANTOM, EntityType.SLIME, EntityType.MAGMA_CUBE,
            EntityType.ZOMBIE_VILLAGER, EntityType.PILLAGER, EntityType.VEX, EntityType.VINDICATOR,
            EntityType.CAVE_SPIDER, EntityType.BOGGED, EntityType.BREEZE);

    public static final class TrackedEntity {
        public final int id;
        public final UUID uuid;
        public final EntityType type;
        public volatile double x, y, z;
        public volatile float yaw;
        public volatile long seen;

        TrackedEntity(int id, UUID uuid, EntityType type, double x, double y, double z, float yaw) {
            this.id = id;
            this.uuid = uuid;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.seen = System.currentTimeMillis();
        }

        public double dist2(double px, double pz) {
            double dx = x - px, dz = z - pz;
            return dx * dx + dz * dz;
        }
    }

    private final Map<Integer, TrackedEntity> entities = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    public void handle(Packet packet) {
        if (packet instanceof ClientboundAddEntityPacket p) {
            entities.put(p.getEntityId(), new TrackedEntity(p.getEntityId(), p.getUuid(), p.getType(),
                    p.getX(), p.getY(), p.getZ(), p.getYaw()));
            log.debug("add-entity id={} type={} ({}, {}, {})", p.getEntityId(), p.getType(), p.getX(), p.getY(), p.getZ());
        } else if (packet instanceof ClientboundEntityPositionSyncPacket p) {
            // 绝对坐标同步（注意：与 Move 系增量包走不同路径，见 setAbsolute）
            setAbsolute(p.getId(), p.getPosition(), p.getYRot());
        } else if (packet instanceof ClientboundTeleportEntityPacket p) {
            setAbsolute(p.getId(), p.getPosition(), p.getYRot());
        } else if (packet instanceof ClientboundMoveEntityPosRotPacket p) {
            move(p.getEntityId(), p.getMoveX(), p.getMoveY(), p.getMoveZ(), p.getYaw());
        } else if (packet instanceof ClientboundMoveEntityPosPacket p) {
            move(p.getEntityId(), p.getMoveX(), p.getMoveY(), p.getMoveZ(), null);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket p) {
            for (int id : p.getEntityIds()) entities.remove(id);
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket p) {
            EnumSet<PlayerListEntryAction> actions = p.getActions();
            if (actions.contains(PlayerListEntryAction.ADD_PLAYER)) {
                for (PlayerListEntry e : p.getEntries()) {
                    if (e.getProfile() != null && e.getProfile().getName() != null) {
                        playerNames.put(e.getProfileId(), e.getProfile().getName());
                    }
                }
            }
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket p) {
            for (UUID u : p.getProfileIds()) playerNames.remove(u);
        } else if (packet instanceof ClientboundSetEntityDataPacket p) {
            for (org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata<?, ?> m : p.getMetadata()) {
                if (m.getType() instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.FloatMetadataType) {
                    log.debug("entity-meta id={} float[{}] = {}", p.getEntityId(), m.getId(), m.getValue());
                }
            }
        }
    }

    /** 传送/位置同步：直接覆盖绝对坐标（不能累加，否则每次同步都漂移一次）。 */
    private void setAbsolute(int id, Vector3d pos, float yaw) {
        TrackedEntity e = entities.get(id);
        if (e == null) return;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.yaw = yaw;
        e.seen = System.currentTimeMillis();
    }

    private void move(int id, double dx, double dy, double dz, Float yaw) {
        TrackedEntity e = entities.get(id);
        if (e == null) return;
        e.x += dx;
        e.y += dy;
        e.z += dz;
        if (yaw != null) e.yaw = yaw;
        e.seen = System.currentTimeMillis();
    }

    public TrackedEntity get(int entityId) {
        return entities.get(entityId);
    }

    /** 按玩家名找实体（AddEntity 的 uuid ↔ PlayerInfo 名单）。 */
    public TrackedEntity findPlayer(String name) {
        if (name == null || name.isBlank()) return null;
        UUID want = null;
        for (Map.Entry<UUID, String> e : playerNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                want = e.getKey();
                break;
            }
        }
        if (want == null) return null;
        for (TrackedEntity e : entities.values()) {
            if (want.equals(e.uuid)) return e;
        }
        return null;
    }

    /** 调试用：当前跟踪到的全部僵尸。 */
    public java.util.List<TrackedEntity> zombies() {
        return entities.values().stream().filter(e -> e.type == EntityType.ZOMBIE).collect(java.util.stream.Collectors.toList());
    }

    /** 距 (px,py,pz) 最近的敌对生物（水平 ≤ maxDist 且垂直 ≤ maxDy——地下怪不可达不该选中）。 */
    public TrackedEntity nearestHostile(double px, double py, double pz, double maxDist, double maxDy) {
        TrackedEntity best = null;
        double bestD = maxDist * maxDist;
        for (TrackedEntity e : entities.values()) {
            if (!HOSTILE.contains(e.type)) continue;
            if (Math.abs(e.y - py) > maxDy) continue;
            double d = e.dist2(px, pz);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 按类型名模糊找最近的实体（如 pig、zombie，大小写不敏感）。 */
    public TrackedEntity nearestOfType(String typeName, double px, double pz, double maxDist) {
        TrackedEntity best = null;
        double bestD = maxDist * maxDist;
        for (TrackedEntity e : entities.values()) {
            if (!e.type.name().equalsIgnoreCase(typeName)) continue;
            double d = e.dist2(px, pz);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 附近实体摘要（给 LLM 的上下文；最多 8 个，按距离排序）。 */
    public String summarize(double px, double pz) {
        StringBuilder sb = new StringBuilder();
        entities.values().stream()
                .sorted((a, b) -> Double.compare(a.dist2(px, pz), b.dist2(px, pz)))
                .limit(8)
                .forEach(e -> {
                    String name = e.uuid != null ? playerNames.get(e.uuid) : null;
                    double d = Math.sqrt(e.dist2(px, pz));
                    sb.append(e.type.name().toLowerCase())
                            .append(name != null ? "(" + name + ")" : "")
                            .append("@").append((int) d).append("格 ");
                });
        return sb.toString().trim();
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
        playerNames.clear();
    }
}
