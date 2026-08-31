package com.mcaibridge.combat;

import com.mcaibridge.ai.ContextManager;
import com.mcaibridge.world.EntityTracker;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundHurtAnimationPacket;

import java.util.function.BiConsumer;

/**
 * 受伤监听：DamageEvent（含攻击者实体 id）→ 回调战斗状态机，并写入短期记忆。
 */
public class DamageListener {
    private final EntityTracker entities;
    private final ContextManager context;
    private volatile BiConsumer<Integer, String> onHurt; // (attackerEntityId, attackerName)

    public DamageListener(EntityTracker entities, ContextManager context) {
        this.entities = entities;
        this.context = context;
    }

    public void setOnHurt(BiConsumer<Integer, String> handler) {
        this.onHurt = handler;
    }

    public void handle(Packet packet) {
        if (packet instanceof ClientboundDamageEventPacket p) {
            // 实测 1.21.11：部分攻击 cause=-1，直接归因在 directId；均无效才视为环境伤害
            int attackerId = p.getSourceCauseId() > 0 ? p.getSourceCauseId() : p.getSourceDirectId();
            if (attackerId <= 0) return; // 环境/摔落伤害
            var attacker = entities.get(attackerId);
            String name = attacker != null
                    ? resolveName(attacker.uuid, attacker.type.name()) : "entity#" + attackerId;
            context.recordAttacker(name);
            var cb = onHurt;
            if (cb != null) cb.accept(attackerId, name);
        } else if (packet instanceof ClientboundHurtAnimationPacket p) {
            // 受击动画（含受击朝向），可选续用
        }
    }

    private String resolveName(java.util.UUID uuid, String fallback) {
        String player = entities.playerName(uuid);
        return player != null ? player : fallback.toLowerCase();
    }
}
