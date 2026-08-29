package com.mcaibridge.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Enumeration;
import java.util.UUID;

/**
 * 皮肤存储与 textures 属性构造。
 * 上传的 PNG 存 dataFolder/skins/，并生成 textures 属性 value（base64 JSON），
 * 皮肤图片由本插件 ApiServer 以 http(s) 提供供客户端拉取。
 */
public class SkinService {
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final String token;
    private final int apiPort;

    SkinService(JavaPlugin plugin, String token, int apiPort) {
        this.plugin = plugin;
        this.token = token;
        this.apiPort = apiPort;
    }

    String token() {
        return token;
    }

    public Path skinsDir() {
        return plugin.getDataFolder().toPath().resolve("skins");
    }

    /** 保存上传皮肤并生成 textures 值。返回给客户端的图片 URL。 */
    public synchronized String store(String name, String model, byte[] png) throws IOException {
        Path dir = skinsDir();
        Files.createDirectories(dir);
        String safe = name.toLowerCase();
        Files.write(dir.resolve(safe + ".png"), png);

        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", imageUrl(safe));
        if ("slim".equalsIgnoreCase(model)) {
            JsonObject meta = new JsonObject();
            meta.addProperty("model", "slim");
            skin.add("metadata", meta);
        }
        JsonObject textures = new JsonObject();
        textures.add("SKIN", skin);
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("profileId", uuid);
        root.addProperty("profileName", name);
        root.add("textures", textures);

        JsonObject meta = new JsonObject();
        meta.addProperty("value", Base64.getEncoder().encodeToString(root.toString().getBytes(StandardCharsets.UTF_8)));
        Files.write(dir.resolve(safe + ".json"), GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        plugin.getLogger().info("皮肤已存储: " + name + " (" + model + ") → " + imageUrl(safe));
        return imageUrl(safe);
    }

    /** 登录时应用：读取已存 textures 值。 */
    public String storedValue(String name) {
        try {
            Path meta = skinsDir().resolve(name.toLowerCase() + ".json");
            if (!Files.exists(meta)) return null;
            return GSON.fromJson(Files.readString(meta, StandardCharsets.UTF_8), JsonObject.class).get("value").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    Path png(String name) {
        return skinsDir().resolve(name.toLowerCase() + ".png");
    }

    private String imageUrl(String safeName) {
        return "http://" + lanHost() + ":" + apiPort + "/mcai/skinimg/" + safeName + ".png";
    }

    /** 探测本机局域网 IPv4（客户端需能访问该地址）。 */
    static String lanHost() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return "127.0.0.1";
    }
}
