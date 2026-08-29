package com.mcaibridge.skin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * 皮肤管理：64x64/64x32 PNG 校验、textures 属性值构造、向 paper 伴生插件上传。
 */
public final class SkinManager {
    private static final Logger log = LoggerFactory.getLogger(SkinManager.class);
    private static final Gson GSON = new Gson();

    private SkinManager() {
    }

    /** 读取并校验皮肤 PNG（仅允许 64x64 / 64x32），返回原始字节。 */
    public static byte[] loadValidated(Path file) throws IOException {
        byte[] png = Files.readAllBytes(file);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("不是有效的 PNG 图片: " + file);
        int w = img.getWidth(), h = img.getHeight();
        if (!((w == 64 && h == 64) || (w == 64 && h == 32))) {
            throw new IOException("皮肤尺寸必须为 64x64 或 64x32，当前 " + w + "x" + h + ": " + file);
        }
        return png;
    }

    /**
     * 构造 textures 属性 value（base64 JSON）。imageUrl 为客户端可访问的皮肤 PNG 地址。
     * 离线环境无 Mojang 签名，value 不带 signature（offline 服务器客户端可正常渲染）。
     */
    public static String buildTexturesValue(byte[] png, String model, String playerName, String imageUrl, String playerUuid) {
        JsonObject textures = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", imageUrl);
        if ("slim".equalsIgnoreCase(model)) {
            JsonObject meta = new JsonObject();
            meta.addProperty("model", "slim");
            skin.add("metadata", meta);
        }
        textures.add("SKIN", skin);
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("profileId", playerUuid);
        root.addProperty("profileName", playerName);
        root.add("textures", textures);
        return Base64.getEncoder().encodeToString(root.toString().getBytes());
    }

    /** 上传皮肤到 paper 伴生插件（POST /mcai/skin）。失败只告警不抛出（皮肤属增强功能）。 */
    public static boolean upload(String uploadUrl, String token, String playerName, String model, byte[] png) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("name", playerName);
            body.addProperty("model", model);
            body.addProperty("png", Base64.getEncoder().encodeToString(png));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-MCAI-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("皮肤已上传至服务端插件: {} ({})", playerName, model);
                return true;
            }
            log.warn("皮肤上传失败: HTTP {} {}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("皮肤上传异常（服务端插件未部署？）: {}", e.toString());
        }
        return false;
    }
}
