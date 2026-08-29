package com.mcaibridge.voice;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.core.AIBrain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * 语音 HTTP 服务（bridge 侧）：paper 插件把切好的语音段 POST 过来，
 * 这里完成 ASR → AI → TTS，返回 {asr, reply, audio(base64 WAV, 可空)}。
 * 同时承载 GET /mcai/health 供探测。
 */
public class VoiceServer {
    private static final Logger log = LoggerFactory.getLogger(VoiceServer.class);
    private static final Gson GSON = new Gson();

    private final BridgeConfig cfg;
    private final AsrEngine asr;
    private final TtsEngine tts;
    private HttpServer server;

    public VoiceServer(BridgeConfig cfg, AsrEngine asr, TtsEngine tts) {
        this.cfg = cfg;
        this.asr = asr;
        this.tts = tts;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.voiceServerPort), 0);
        server.createContext("/mcai/voice", this::handleVoice);
        server.createContext("/mcai/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mcai-voice");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("语音服务已启动: 端口 {} | ASR={} | TTS={}", cfg.voiceServerPort, asr.name(), tts.name());
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handleVoice(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "{\"error\":\"method\"}");
                return;
            }
            String token = ex.getRequestHeaders().getFirst("X-MCAI-Token");
            String player = ex.getRequestHeaders().getFirst("X-MCAI-Player");
            if (cfg.voiceToken != null && !cfg.voiceToken.isBlank() && !cfg.voiceToken.equals(token)) {
                respond(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            byte[] wav;
            try (InputStream in = ex.getRequestBody()) {
                wav = in.readAllBytes();
            }
            if (wav.length <= 44) {
                respond(ex, 400, "{\"error\":\"empty audio\"}");
                return;
            }

            long t0 = System.currentTimeMillis();
            String asrText = safeAsr(wav);
            String reply;
            byte[] audio = null;
            if (asrText == null || asrText.isBlank()) {
                reply = "(没有听清，请再说一遍)";
            } else {
                AIBrain brain = new AIBrain(cfg);
                try {
                    reply = brain.chat(asrText).trim();
                } catch (Exception e) {
                    log.warn("语音 AI 调用失败: {}", e.toString());
                    reply = "(AI 暂时无法回复)";
                }
                try {
                    audio = tts.synthesizeWav(reply);
                } catch (Exception e) {
                    log.warn("TTS 合成失败({}): {}", tts.name(), e.toString());
                    audio = null;
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("asr", asrText == null ? "" : asrText);
            out.addProperty("reply", reply == null ? "" : reply);
            out.addProperty("tts", audio != null);
            if (audio != null) {
                if (AudioPipeline.wavSampleRate(audio) != 48000) {
                    short[] up = AudioPipeline.resample(AudioPipeline.wavToShorts(audio),
                            AudioPipeline.wavSampleRate(audio), 48000);
                    audio = AudioPipeline.wavFromShorts(up, 48000);
                }
                out.addProperty("audio", Base64.getEncoder().encodeToString(audio));
            }
            log.info("语音管线完成 ({}ms): 玩家={} asr=\"{}\" reply=\"{}\" audio={}",
                    System.currentTimeMillis() - t0, player, asrText, reply, audio != null);
            respond(ex, 200, GSON.toJson(out));
        } catch (Exception e) {
            log.warn("语音请求处理异常: {}", e.toString());
            respond(ex, 500, "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}");
        }
    }

    private String safeAsr(byte[] wav) {
        try {
            return asr.transcribe(wav);
        } catch (Exception e) {
            log.warn("ASR 失败({}): {}", asr.name(), e.toString());
            return null;
        }
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
