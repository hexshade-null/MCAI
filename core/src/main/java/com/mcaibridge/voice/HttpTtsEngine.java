package com.mcaibridge.voice;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容 HTTP TTS（POST {base_url}/audio/speech）。
 * 默认对接 Z.ai GLM-TTS（base_url=https://api.z.ai/api/paas/v4, model=glm-tts,
 * voice=tongtong/chuichui/xiaochen...，response_format=wav，输出 24kHz）。
 */
public class HttpTtsEngine implements TtsEngine {
    private static final Logger log = LoggerFactory.getLogger(HttpTtsEngine.class);

    private final String baseUrl;
    private final String model;
    private final String voice;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpTtsEngine(String baseUrl, String model, String voice, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.model = model;
        this.voice = voice;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "http-tts(" + model + "@" + URI.create(baseUrl).getHost() + ")";
    }

    @Override
    public byte[] synthesizeWav(String text) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("TTS api_key 未设置");
        }
        if (text.length() > 1024) text = text.substring(0, 1024); // GLM-TTS 上限
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "wav");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/audio/speech"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String detail = resp.body() == null ? "" : new String(resp.body());
            throw new IOException("TTS HTTP " + resp.statusCode() + ": "
                    + detail.substring(0, Math.min(200, detail.length())));
        }
        byte[] audio = resp.body();
        if (audio.length <= 44 || audio[0] != 'R' || audio[1] != 'I') {
            throw new IOException("TTS 返回的不是 WAV 音频 (" + audio.length + " bytes)");
        }
        log.debug("GLM-TTS 合成 {} 字节 WAV", audio.length);
        return audio;
    }
}
