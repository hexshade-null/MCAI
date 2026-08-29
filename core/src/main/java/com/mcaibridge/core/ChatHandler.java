package com.mcaibridge.core;

import com.mcaibridge.config.BridgeConfig;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聊天监听：收到含 @botName 的消息时触发 AI 回复。
 * 回复通道可配：chat（真实玩家聊天）/ plugin（经 paper 伴生插件广播，绕开部分 Paper
 * 版本对机器人外发聊天的间歇性静默丢弃）/ both。
 */
public class ChatHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final int MAX_REPLY_CHARS = 240;

    private final BridgeConfig cfg;
    private final MCBot bot;
    private final AIBrain brain;
    private volatile PlayerController controller;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger pluginFailures = new AtomicInteger();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcai-ai");
        t.setDaemon(true);
        return t;
    });
    /** 同时最多 1 条 AI 回复在处理，避免刷屏与并发调用。 */
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public ChatHandler(BridgeConfig cfg, MCBot bot, AIBrain brain) {
        this.cfg = cfg;
        this.bot = bot;
        this.brain = brain;
    }

    public void setController(PlayerController controller) {
        this.controller = controller;
    }

    public void handle(Packet packet) {
        String text;
        String senderName;
        if (packet instanceof ClientboundPlayerChatPacket p) {
            senderName = TextUtil.plain(p.getName());
            text = p.getUnsignedContent() != null ? TextUtil.plain(p.getUnsignedContent()) : p.getContent();
        } else if (packet instanceof ClientboundSystemChatPacket p) {
            senderName = null;
            text = TextUtil.plain(p.getContent());
        } else {
            return;
        }
        if (text == null || text.isEmpty()) return;

        String trigger = "@" + cfg.botName;
        if (!text.contains(trigger)) return;
        if (cfg.botName.equals(senderName)) return; // 忽略自己的消息

        String question = extractQuestion(text, trigger);
        log.info("收到触发消息 [{}]: {}", senderName, text);

        // 内置行动指令优先于 AI（@bot 跟我来 / 停下 / 挖 / 走到 x z / /指令）
        if (controller != null && handleBotCommand(senderName == null ? "" : senderName, question)) {
            return;
        }

        if (!pending.compareAndSet(false, true)) {
            log.info("已有回复处理中，跳过本条");
            return;
        }
        aiExecutor.submit(() -> {
            try {
                String reply = brain.chat(question);
                if (reply != null && !reply.isBlank()) {
                    String out = prefix() + truncate(reply.trim());
                    log.info("AI 回复: {}", out);
                    sendReply(out);
                }
            } catch (Exception e) {
                log.warn("AI 调用失败: {}", e.toString());
                sendReply(prefix() + "(AI 暂时无法回复)");
            } finally {
                pending.set(false);
            }
        });
    }

    /** 内置行动指令。返回 true 表示已处理（不再问 AI）。 */
    private boolean handleBotCommand(String sender, String q) {
        String s = q.trim();
        try {
            if (s.startsWith("/")) {
                controller.sendCommand(s);
                sendReply(prefix() + "执行指令: " + s);
                return true;
            }
            if (s.matches("(停|停下|停止|站住|stop)\\S{0,2}")) {
                controller.stopMoving();
                sendReply(prefix() + "好的，我停下");
                return true;
            }
            if (s.matches("(过来|跟我来|跟随|跟上|follow)\\S{0,2}")) {
                if (sender.isBlank()) return false;
                controller.follow(sender);
                sendReply(prefix() + "好的，我这就来！");
                return true;
            }
            if (s.matches("(挖|dig)\\S{0,2}")) {
                controller.digBelow();
                sendReply(prefix() + "开挖脚下的方块！");
                return true;
            }
            var m = java.util.regex.Pattern.compile("(?:走到|走向|去)\\s*(-?[0-9.]+)[ ,，]+(-?[0-9.]+)").matcher(s);
            if (m.find()) {
                controller.walkTo(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
                sendReply(prefix() + "走向 (" + m.group(1) + ", " + m.group(2) + ")");
                return true;
            }
            if (s.matches("(在哪|位置|坐标)\\S{0,2}")) {
                sendReply(prefix() + "我在 " + controller.positionString());
                return true;
            }
        } catch (Exception e) {
            log.warn("行动指令执行失败: {}", e.toString());
        }
        return false;
    }

    /** 按配置通道发送：chat=机器人真实聊天；plugin=经插件广播（每次先尝试真实聊天，失败自动降级）。 */
    private void sendReply(String out) {
        String via = cfg.replyVia;
        if ("plugin".equals(via)) {
            sendViaPlugin(out);
            return;
        }
        if (pluginFailures.get() < 3) {
            bot.sendChat(out); // 真实聊天
        }
        if ("both".equals(via) || pluginFailures.get() >= 3) {
            sendViaPlugin(out);
        }
    }

    private void sendViaPlugin(String text) {
        if (cfg.skinUploadUrl == null || cfg.skinUploadUrl.isBlank()) return;
        String url = cfg.skinUploadUrl.replaceAll("/mcai/skin$", "/mcai/chat");
        try {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("from", cfg.botName);
            body.addProperty("text", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-MCAI-Token", cfg.skinToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                pluginFailures.set(0);
                return;
            }
            log.warn("插件广播回复失败: HTTP {}", resp.statusCode());
            pluginFailures.incrementAndGet();
        } catch (Exception e) {
            pluginFailures.incrementAndGet();
            log.warn("插件广播回复异常: {}", e.toString());
        }
    }

    private String extractQuestion(String text, String trigger) {
        int idx = text.indexOf(trigger);
        String q = text.substring(idx + trigger.length()).trim();
        if (q.startsWith(":")) q = q.substring(1).trim();
        if (q.startsWith("，") || q.startsWith(",")) q = q.substring(1).trim();
        return q.isEmpty() ? "你好" : q;
    }

    private String prefix() {
        return String.format(cfg.replyPrefix, cfg.botName);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_REPLY_CHARS ? s : s.substring(0, MAX_REPLY_CHARS);
    }

    public void shutdown() {
        aiExecutor.shutdownNow();
    }
}
