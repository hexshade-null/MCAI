package com.mcaibridge.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SVC 语音中继：玩家麦克风 → Opus 解码 + VAD 切句 → POST bridge(ASR→AI→TTS) → 广播回复语音 + 文字。
 */
public class VoiceRelay implements VoicechatPlugin, com.mcaibridge.paper.BridgePlugin.Shutdownable {
    private static final Logger log = LoggerFactory.getLogger("MCAIBridge-Voice");
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private volatile VoicechatServerApi serverApi;
    private volatile OpusDecoder sharedDecoder; // 每个说话者独立实例按需创建

    private final Map<UUID, Utterance> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    private String bridgeUrl;
    private String token;
    private int silenceMs;
    private int maxUtteranceMs;
    private String broadcast;   // all | nearby
    private boolean sendText;

    private static class Utterance {
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        volatile long lastMs;
        boolean speechSeen;
    }

    public VoiceRelay(JavaPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        bridgeUrl = plugin.getConfig().getString("voice.bridge_url", "http://localhost:8787/mcai/voice");
        token = plugin.getConfig().getString("voice.token", "changeme");
        silenceMs = plugin.getConfig().getInt("voice.silence_ms", 900);
        maxUtteranceMs = plugin.getConfig().getInt("voice.max_utterance_ms", 12000);
        broadcast = plugin.getConfig().getString("voice.broadcast", "all");
        sendText = plugin.getConfig().getBoolean("voice.send_text", true);
    }

    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
    }

    @Override
    public String getPluginId() {
        return "mcaibridge";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi server) {
            this.serverApi = server;
            this.sharedDecoder = server.createDecoder();
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 10L, 10L);
    }

    private void onMic(MicrophonePacketEvent event) {
        VoicechatServerApi api = serverApi;
        if (api == null || event.getSenderConnection() == null) return;
        UUID speaker = event.getSenderConnection().getPlayer().getUuid();
        try {
            OpusDecoder decoder = decoders.computeIfAbsent(speaker, id -> api.createDecoder());
            short[] pcm = decoder.decode(event.getPacket().getOpusEncodedData());
            Utterance u = buffers.computeIfAbsent(speaker, id -> new Utterance());
            synchronized (u) {
                if (u.pcm.size() < maxUtteranceMs * 48) { // 48 samples/ms
                    u.pcm.writeBytes(AudioUtil.shortsToBytesLE(pcm));
                    if (AudioUtil.rms(pcm) > 300) u.speechSeen = true;
                }
                u.lastMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.warn("麦克风数据处理异常: {}", e.toString());
        }
    }

    /** 定时检查：静音超时且有语音内容 → 发往 bridge。 */
    private void flush() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Utterance> en : buffers.entrySet()) {
            Utterance u = en.getValue();
            if (u.lastMs == 0) continue;
            boolean silence = now - u.lastMs >= silenceMs;
            boolean overflow = synchronizedSize(u) >= (long) maxUtteranceMs * 48 * 2;
            if (!silence && !overflow) continue;
            buffers.remove(en.getKey());
            if (!u.speechSeen) continue; // 纯静音段丢弃
            byte[] pcmBytes;
            synchronized (u) {
                pcmBytes = u.pcm.toByteArray();
            }
            short[] pcm = AudioUtil.bytesToShortsLE(pcmBytes);
            if (pcm.length < 4800) continue; // <100ms 噪声丢弃
            Player bukkitPlayer = Bukkit.getPlayer(en.getKey());
            String name = bukkitPlayer != null ? bukkitPlayer.getName() : en.getKey().toString();
            byte[] wav = AudioUtil.wavFromShorts(pcm, 48000);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> askBridge(name, en.getKey(), wav));
        }
    }

    private static int synchronizedSize(Utterance u) {
        synchronized (u) {
            return u.pcm.size();
        }
    }

    private void askBridge(String playerName, UUID speaker, byte[] wav) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "audio/wav")
                    .header("X-MCAI-Token", token)
                    .header("X-MCAI-Player", playerName)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wav))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("bridge 语音响应异常: HTTP {} {}", resp.statusCode(), resp.body());
                return;
            }
            JsonObject out = GSON.fromJson(resp.body(), JsonObject.class);
            String asr = out.has("asr") ? out.get("asr").getAsString() : "";
            String reply = out.has("reply") ? out.get("reply").getAsString() : "";
            log.info("语音回合: 玩家={} asr=\"{}\" reply=\"{}\"", playerName, asr, reply);

            byte[] audio = null;
            if (out.has("audio") && !out.get("audio").isJsonNull()) {
                audio = Base64.getDecoder().decode(out.get("audio").getAsString());
            }
            byte[] finalAudio = audio;
            Bukkit.getScheduler().runTask(plugin, () -> broadcastReply(speaker, finalAudio, reply));
        } catch (Exception e) {
            log.warn("bridge 语音请求失败: {}", e.toString());
        }
    }

    /** 主线程：语音广播 + 文字播报。 */
    private void broadcastReply(UUID speaker, byte[] wav, String reply) {
        VoicechatServerApi api = serverApi;
        if (api == null) return;
        if (wav != null && wav.length > 44) {
            try {
                short[] pcm = AudioUtil.wavToShorts(wav);
                int rate = AudioUtil.wavSampleRate(wav);
                if (rate != 48000) pcm = AudioUtil.resample(pcm, rate, 48000);

                OpusEncoder encoder = api.createEncoder();
                Player src = Bukkit.getPlayer(speaker);
                if ("nearby".equals(broadcast) && src != null) {
                    LocationalAudioChannel ch = api.createLocationalAudioChannel(UUID.randomUUID(),
                            api.fromServerLevel(src.getWorld()),
                            api.createPosition(src.getLocation().getX(), src.getLocation().getY(), src.getLocation().getZ()));
                    AudioPlayer ap = api.createAudioPlayer(ch, encoder, pcm);
                    ap.startPlaying();
                } else {
                    StaticAudioChannel ch = api.createStaticAudioChannel(UUID.randomUUID());
                    ch.setBypassGroupIsolation(true);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        VoicechatConnection conn = api.getConnectionOf(api.fromServerPlayer(p));
                        if (conn != null && conn.isInstalled() && conn.isConnected()) ch.addTarget(conn);
                    }
                    AudioPlayer ap = api.createAudioPlayer(ch, encoder, pcm);
                    ap.setOnStopped(encoder::close);
                    ap.startPlaying();
                }
            } catch (Exception e) {
                log.warn("语音广播失败: {}", e.toString());
            }
        }
        if (sendText && reply != null && !reply.isBlank()) {
            Bukkit.broadcastMessage("[AI] " + reply);
        }
    }
}
