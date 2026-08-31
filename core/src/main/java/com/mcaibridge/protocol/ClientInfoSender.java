package com.mcaibridge.protocol;

import com.mcaibridge.core.MCBot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 客户端信息上报（join 后一次）：全皮肤部件、聊天可见性 FULL（避免"仅安全聊天"客户端看不见
 * 机器人发言）、中文区域。此前从未发送，服务器用默认值。
 */
public final class ClientInfoSender {
    private static final Logger log = LoggerFactory.getLogger(ClientInfoSender.class);

    private ClientInfoSender() {
    }

    public static void send(MCBot bot) {
        try {
            bot.send(new ServerboundClientInformationPacket(
                    "zh_CN", 8, ChatVisibility.FULL, true,
                    Arrays.asList(SkinPart.VALUES), HandPreference.RIGHT_HAND,
                    false, true, ParticleStatus.ALL));
            log.info("客户端信息已上报（皮肤部件全开/聊天 FULL）");
        } catch (Exception e) {
            log.warn("客户端信息上报失败: {}", e.toString());
        }
    }
}
