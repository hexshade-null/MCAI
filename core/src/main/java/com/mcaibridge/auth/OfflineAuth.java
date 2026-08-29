package com.mcaibridge.auth;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.util.List;
import java.util.UUID;

/**
 * 离线登录：用户名 + 离线 UUID（服务器 online-mode=false 时可用）。
 * 可选注入自定义 textures 属性（离线环境下由服务端决定是否采纳；
 * 原版服务端忽略该属性，真正生效需配合 paper 伴生插件的服务端注入）。
 */
public final class OfflineAuth {

    private OfflineAuth() {
    }

    public static AuthResult login(String username) {
        return login(username, null);
    }

    public static AuthResult login(String username, GameProfile.Property skinProperty) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
        GameProfile profile = new GameProfile(offlineUuid, username);
        if (skinProperty != null) {
            profile.setProperties(List.of(skinProperty));
        }
        // 连接协议必须走 username 构造（实测离线服可正常发送聊天）；
        // profile 仅用于本地展示皮肤属性（原版服务端会重建档案，真正生效靠 paper 插件服务端注入）
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        return new AuthResult("offline", profile, null, protocol);
    }
}
