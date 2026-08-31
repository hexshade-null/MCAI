package com.mcaibridge.protocol;

import com.mcaibridge.core.MCBot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;

/**
 * 玩家动作状态上报：疾跑（PlayerCommand，服务端据此给疾跑击退/消耗饥饿）、
 * 潜行（Input shift 位；本版协议 PlayerState 无潜行枚举）。
 */
public final class ActionStateSender {
    private ActionStateSender() {
    }

    public static void setSprinting(MCBot bot, int selfEntityId, boolean sprinting) {
        bot.send(new ServerboundPlayerCommandPacket(selfEntityId,
                sprinting ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING));
    }

    public static void setSneaking(MCBot bot, boolean sneaking) {
        bot.send(new ServerboundPlayerInputPacket(false, false, false, false, false, sneaking, false));
    }

    /** 输入位整体上报（前进/疾跑等，供物理引擎同步真实输入）。 */
    public static void sendInput(MCBot bot, boolean forward, boolean backward, boolean left, boolean right,
                                 boolean jump, boolean sneaking, boolean sprinting) {
        bot.send(new ServerboundPlayerInputPacket(forward, backward, left, right, jump, sneaking, sprinting));
    }
}
