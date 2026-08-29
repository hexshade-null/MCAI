package com.mcaibridge.auth;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/**
 * 认证结果：携带会话协议与玩家档案。
 */
public record AuthResult(String source, GameProfile profile, String accessToken, MinecraftProtocol protocol) {
}
