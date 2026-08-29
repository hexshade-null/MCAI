package com.mcaibridge.auth;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.util.UUID;

/**
 * 离线登录：用户名 + 随机离线 UUID（服务器 online-mode=false 时可用）。
 */
public final class OfflineAuth {

    private OfflineAuth() {
    }

    public static AuthResult login(String username) {
        // MCProtocolLib 的 username 构造器即为离线模式：随机 UUID、无访问令牌
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()), username);
        return new AuthResult("offline", profile, null, protocol);
    }
}
