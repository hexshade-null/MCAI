package com.mcaibridge.core;

import com.mcaibridge.auth.AuthResult;
import com.mcaibridge.config.BridgeConfig;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.BitSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCProtocolLib 连接生命周期：连接、保活、收发包分发、断线重连、发送聊天。
 */
public class MCBot {
    private static final Logger log = LoggerFactory.getLogger(MCBot.class);

    public enum State {DISCONNECTED, CONNECTING, CONNECTED}

    /** 状态/日志回调（GUI 或无头模式各自实现）。 */
    public interface Listener {
        void onLog(String line);

        void onStateChange(State state, String detail);
    }

    private final BridgeConfig cfg;
    private final AuthResult auth;
    private final Listener listener;
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcaiboard-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile ClientSession session;
    private volatile ChatHandler chatHandler;
    private volatile PlayerController controller;
    private volatile com.mcaibridge.world.WorldModel world;
    private volatile com.mcaibridge.world.EntityTracker entities;
    private volatile com.mcaibridge.world.SurvivalManager survival;
    private volatile boolean shutdown;

    public MCBot(BridgeConfig cfg, AuthResult auth, Listener listener) {
        this.cfg = cfg;
        this.auth = auth;
        this.listener = listener;
    }

    public void setChatHandler(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    public void setController(PlayerController controller) {
        this.controller = controller;
    }

    public void setWorld(com.mcaibridge.world.WorldModel world) {
        this.world = world;
    }

    public void setEntities(com.mcaibridge.world.EntityTracker entities) {
        this.entities = entities;
    }

    public void setSurvival(com.mcaibridge.world.SurvivalManager survival) {
        this.survival = survival;
    }

    /** 发送任意协议包（移动/挖掘/指令等）。 */
    public void send(Packet packet) {
        ClientSession s = session;
        if (s == null || state.get() != State.CONNECTED) return;
        s.send(packet);
    }

    public State getState() {
        return state.get();
    }

    public String getBotName() {
        return auth.profile().getName();
    }

    public synchronized void connect() {
        if (shutdown) return;
        state.set(State.CONNECTING);
        listener.onStateChange(State.CONNECTING, cfg.serverHost + ":" + cfg.serverPort);
        log.info("正在连接 {}:{} (认证方式: {}, 玩家: {})", cfg.serverHost, cfg.serverPort, auth.source(), auth.profile().getName());

        ClientSession s = ClientNetworkSessionFactory.factory()
                .setAddress(cfg.serverHost, cfg.serverPort)
                .setProtocol(auth.protocol())
                .create();
        s.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        s.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                state.set(State.CONNECTED);
                reconnectAttempts.set(0);
                log.info("已连接服务器，玩家 {} 登录成功", auth.profile().getName());
                listener.onStateChange(State.CONNECTED, auth.profile().getName());
                com.mcaibridge.protocol.ClientInfoSender.send(MCBot.this);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                state.set(State.DISCONNECTED);
                String reason = event.getReason() != null ? TextUtil.plain(event.getReason()) : "未知原因";
                if (event.getCause() != null) {
                    log.warn("连接断开: {} | 异常: {}", reason, event.getCause().toString());
                } else {
                    log.warn("连接断开: {}", reason);
                }
                listener.onStateChange(State.DISCONNECTED, reason);
                scheduleReconnect(reason);
            }

            @Override
            public void packetReceived(Session ses, Packet packet) {
                com.mcaibridge.world.WorldModel wm = world;
                if (wm != null) {
                    try {
                        wm.handle(packet);
                    } catch (Exception e) {
                        log.debug("世界模型处理包异常: {}", e.toString());
                    }
                }
                com.mcaibridge.world.EntityTracker et = entities;
                if (et != null) {
                    try {
                        et.handle(packet);
                    } catch (Exception e) {
                        log.debug("实体跟踪处理包异常: {}", e.toString());
                    }
                }
                com.mcaibridge.world.SurvivalManager sm = survival;
                if (sm != null) {
                    try {
                        sm.handle(packet);
                    } catch (Exception e) {
                        log.debug("生存辅助处理包异常: {}", e.toString());
                    }
                }
                PlayerController ctl = controller;
                if (ctl != null) {
                    try {
                        ctl.handle(packet);
                    } catch (Exception e) {
                        log.debug("控制器处理包异常: {}", e.toString());
                    }
                }
                ChatHandler handler = chatHandler;
                if (handler == null) return;
                try {
                    handler.handle(packet);
                } catch (Exception e) {
                    log.warn("处理数据包异常: {}", e.toString());
                }
            }
        });
        this.session = s;
        s.connect();
    }

    private void scheduleReconnect(String reason) {
        if (shutdown) return;
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > cfg.reconnectMaxAttempts) {
            log.error("重连次数已达上限({})，停止重连。最后原因: {}", cfg.reconnectMaxAttempts, reason);
            return;
        }
        long delay = Math.min(60, 2L * attempt * attempt);
        log.info("将在 {} 秒后进行第 {}/{} 次重连", delay, attempt, cfg.reconnectMaxAttempts);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** 以当前玩家身份发送聊天（离线客户端无签名密钥，发送未签名消息）。 */
    public void sendChat(String message) {
        ClientSession s = session;
        if (s == null || state.get() != State.CONNECTED) {
            log.warn("未连接，丢弃聊天: {}", message);
            return;
        }
        s.send(new ServerboundChatPacket(message, Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0));
    }

    public void shutdown() {
        shutdown = true;
        scheduler.shutdownNow();
        ChatHandler handler = chatHandler;
        if (handler != null) handler.shutdown();
        PlayerController ctl = controller;
        if (ctl != null) ctl.stop();
        com.mcaibridge.world.SurvivalManager sm = survival;
        if (sm != null) sm.shutdown();
        ClientSession s = session;
        if (s != null) {
            try {
                s.disconnect("客户端关闭");
            } catch (Exception ignored) {
            }
        }
        state.set(State.DISCONNECTED);
    }
}
