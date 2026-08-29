package com.mcaibridge.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Edge-TTS（微软 Edge 朗读接口，免费）：
 * WSS speech.platform.bing.com，TrustedClientToken + Sec-MS-GEC（按公开 edge-tts 算法）。
 * 请求 RIFF PCM 24k16bit 单声道输出，失败由调用方回退文字。
 */
public class EdgeTtsEngine implements TtsEngine {
    private static final Logger log = LoggerFactory.getLogger(EdgeTtsEngine.class);
    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";

    private final String voice;
    private final HttpClient http = HttpClient.newBuilder().build();

    public EdgeTtsEngine(String voice) {
        this.voice = voice == null || voice.isBlank() ? "zh-CN-XiaoxiaoNeural" : voice;
    }

    @Override
    public String name() {
        return "edge-tts(" + voice + ")";
    }

    /** Sec-MS-GEC：SHA256(六百纳秒对齐的 Windows 时间戳 + TrustedToken) 大写十六进制。 */
    static String secMsGec() throws Exception {
        long ticks = System.currentTimeMillis() * 10_000L + 116_444_736_000_000_000L;
        long rounded = ticks - (ticks % 3_000_000_000L);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest((rounded + TRUSTED_TOKEN).getBytes(StandardCharsets.US_ASCII));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format("%02X", b));
        return hex.toString();
    }

    @Override
    public byte[] synthesizeWav(String text) throws Exception {
        String url = WSS + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + secMsGec() + "&Sec-MS-GEC-Version=1-131.0.2903.99";
        Handler handler = new Handler();
        WebSocket ws = http.newWebSocketBuilder()
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
                .buildAsync(URI.create(url), handler).get(10, TimeUnit.SECONDS);

        String ts = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now());
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String speechConfig = "X-Timestamp:" + ts + "\r\nContent-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\","
                + "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"riff-24khz-16bit-mono-pcm\"}}}}";
        String ssml = "X-RequestId:" + requestId + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + ts
                + "\r\nPath:ssml\r\n\r\n"
                + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                + "<voice name='" + voice + "'>" + escapeXml(text) + "</voice></speak>";
        ws.sendText(speechConfig, true);
        ws.sendText(ssml, true);

        byte[] wav = handler.await(30, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        return wav;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class Handler implements WebSocket.Listener {
        private final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private final List<byte[]> audio = new ArrayList<>();
        private volatile boolean open = true;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (data.toString().contains("Path:turn.end")) {
                done.complete(joinWav());
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            int headerLen = data.getShort() & 0xFFFF;
            data.position(2 + headerLen); // 头部含 Path:audio
            byte[] payload = new byte[data.remaining()];
            data.get(payload);
            synchronized (audio) {
                audio.add(payload);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (open) done.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            open = false;
            done.complete(joinWav()); // 服务端以 turn.end 结尾，正常 close 前应已 complete
            return null;
        }

        byte[] await(long timeout, TimeUnit unit) throws Exception {
            byte[] wav = done.get(timeout, unit);
            if (wav == null || wav.length <= 44) {
                throw new IllegalStateException("Edge-TTS 未返回音频");
            }
            log.debug("Edge-TTS 合成 {} 字节音频", wav.length);
            return wav;
        }

        private byte[] joinWav() {
            synchronized (audio) {
                int total = audio.stream().mapToInt(a -> a.length).sum();
                byte[] all = new byte[total];
                int pos = 0;
                for (byte[] a : audio) {
                    System.arraycopy(a, 0, all, pos, a.length);
                    pos += a.length;
                }
                return all;
            }
        }
    }
}
