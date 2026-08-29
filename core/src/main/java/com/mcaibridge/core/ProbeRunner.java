package com.mcaibridge.core;

import com.mcaibridge.config.BridgeConfig;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 纯协议自检（降级测试路径）：以 probe.name（默认 TestPlayer）加入服务器，
 * 发送 "@botName 你好"，在超时内等到 AI 回复（含 replyPrefix 标记）即 PASS。
 * 独立进程运行：java -jar mcaibridge.jar --config xx --probe
 */
public final class ProbeRunner {
    private static final Logger log = LoggerFactory.getLogger(ProbeRunner.class);

    private ProbeRunner() {
    }

    public static int run(BridgeConfig cfg) throws Exception {
        String marker = String.format(cfg.replyPrefix, cfg.botName);
        String trigger = "@" + cfg.botName + " 你好";
        System.out.println("[probe] 以 " + cfg.probeName + " 加入 " + cfg.serverHost + ":" + cfg.serverPort
                + "，发送 \"" + trigger + "\"，等待回复标记 \"" + marker + "\" …");

        MinecraftProtocol protocol = new MinecraftProtocol(cfg.probeName);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setAddress(cfg.serverHost, cfg.serverPort)
                .setProtocol(protocol)
                .create();
        session.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);

        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch replied = new CountDownLatch(1);
        boolean[] skinSeen = {false};
        session.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                System.out.println("[probe] 已加入服务器");
                joined.countDown();
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String reason = event.getReason() != null ? TextUtil.plain(event.getReason()) : "未知";
                System.out.println("[probe] 断开: " + reason);
            }

            @Override
            public void packetError(org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent event) {
                System.out.println("[probe] 包错误: " + event.getCause());
            }

            @Override
            public void packetReceived(Session s, Packet packet) {
                String text = null;
                if (packet instanceof ClientboundPlayerChatPacket p) {
                    text = p.getUnsignedContent() != null ? TextUtil.plain(p.getUnsignedContent()) : p.getContent();
                } else if (packet instanceof ClientboundSystemChatPacket p) {
                    text = TextUtil.plain(p.getContent());
                } else if (packet instanceof ClientboundPlayerInfoUpdatePacket piu
                        && piu.getActions().contains(PlayerListEntryAction.ADD_PLAYER)) {
                    // 皮肤断言：机器人档案是否携带 textures 属性
                    for (PlayerListEntry entry : piu.getEntries()) {
                        if (entry.getProfile() != null && cfg.botName.equals(entry.getProfile().getName())) {
                            boolean has = entry.getProfile().getProperty("textures") != null;
                            if (has && !skinSeen[0]) {
                                skinSeen[0] = true;
                                System.out.println("SKIN_PROPERTY=PRESENT (" + cfg.botName + " 档案含 textures)");
                            }
                        }
                    }
                }
                if (text != null && text.contains(marker)) {
                    System.out.println("[probe] 收到 AI 回复: " + text);
                    replied.countDown();
                }
            }
        });
        session.connect();

        if (!joined.await(30, TimeUnit.SECONDS)) {
            System.out.println("PROBE_RESULT=FAIL (30秒内未加入服务器)");
            return 1;
        }
        // 加入后等待聊天会话完全建立再发（过早发送会被服务端静默丢弃）
        Thread.sleep(4000);
        session.send(new ServerboundChatPacket(trigger, Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0),
                () -> System.out.println("[probe] 触发消息已写出"));
        System.out.println("[probe] 已发送触发消息");

        boolean ok = replied.await(cfg.probeTimeoutMs, TimeUnit.MILLISECONDS);
        if (!skinSeen[0]) {
            System.out.println("SKIN_PROPERTY=ABSENT (" + cfg.botName + " 档案无 textures，服务端皮肤插件未生效)");
        }
        session.disconnect("探测完成");
        if (ok) {
            System.out.println("PROBE_RESULT=PASS");
            return 0;
        }
        System.out.println("PROBE_RESULT=FAIL (" + cfg.probeTimeoutMs + "ms 内未收到 AI 回复)");
        return 1;
    }
}
