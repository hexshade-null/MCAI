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
    private volatile IntentParser parser;
    private volatile ActionExecutor executor;
    private volatile com.mcaibridge.ai.TaskPlanner taskPlanner;
    private volatile com.mcaibridge.ai.ContextManager context;
    private volatile com.mcaibridge.world.EntityTracker entities;
    private volatile com.mcaibridge.world.SurvivalManager survival;
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

    public void setParser(IntentParser parser) {
        this.parser = parser;
    }

    public void setExecutor(ActionExecutor executor) {
        this.executor = executor;
    }

    public void setWorldModules(com.mcaibridge.world.EntityTracker entities,
                                com.mcaibridge.world.SurvivalManager survival) {
        this.entities = entities;
        this.survival = survival;
    }

    public void setTaskPlanner(com.mcaibridge.ai.TaskPlanner planner) {
        this.taskPlanner = planner;
    }

    public void setContext(com.mcaibridge.ai.ContextManager context) {
        this.context = context;
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
        log.info("收到触发消息 [{}]: {}", senderName, question);

        if (!pending.compareAndSet(false, true)) {
            log.info("已有回复处理中，跳过本条");
            return;
        }
        aiExecutor.submit(() -> {
            try {
                // 言出法随：先解析意图（LLM/关键词），解析出动作或说明则执行+回复
                IntentParser parser = this.parser;
                if (parser != null) {
                    IntentParser.Plan plan = parser.parse(question, buildContext(senderName));
                    if (plan != null) {
                        if (plan.say != null && !plan.say.isBlank()) {
                            String out = prefix() + truncate(plan.say.trim());
                            log.info("意图回复: {}", out);
                            sendReply(out);
                        }
                        ActionExecutor ex = this.executor;
                        if (plan.hasAction() && ex != null) {
                            com.mcaibridge.ai.TaskPlanner tp = this.taskPlanner;
                            if (tp != null) {
                                tp.submit(plan.actions, 1); // 失败自动重试 1 次
                            } else {
                                ex.submit(plan.actions);
                            }
                            if (plan.say == null || plan.say.isBlank()) {
                                sendReply(prefix() + "收到，马上行动！");
                            }
                        }
                        return;
                    }
                }
                // 普通聊天问答
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

    private IntentParser.Ctx buildContext(String sender) {
        PlayerController ctl = controller;
        double[] p = ctl != null ? ctl.position() : new double[3];
        String pos = ctl != null ? ctl.positionString() : "(未知)";
        float health = survival != null ? survival.getHealth() : 20f;
        int food = survival != null ? survival.getFood() : 20;
        String nearby = entities != null ? entities.summarize(p[0], p[2]) : "";
        return new IntentParser.Ctx(sender == null ? "" : sender, pos, health, food, nearby);
    }

    /** 动作执行器过程汇报（从 ticker 线程回调），走统一回复通道。 */
    public void sendActionReport(String text) {
        sendReply(prefix() + truncate(text));
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
