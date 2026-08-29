package com.mcaibridge.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Whisper ASR：OpenAI 兼容 /audio/transcriptions（兼容 Groq、faster-whisper-server、whisper.cpp server）。 */
public class WhisperHttpAsrEngine implements AsrEngine {
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WhisperHttpAsrEngine(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "whisper-http(" + model + ")";
    }

    @Override
    public String transcribe(byte[] wavBytes) throws Exception {
        String boundary = "----mcai" + UUID.randomUUID();
        var sb = new java.io.ByteArrayOutputStream();
        addPart(sb, boundary, "model", model);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("asr.api_key 未设置（whisper-http 需要）");
        }
        sb.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes());
        sb.writeBytes(wavBytes);
        sb.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(sb.toByteArray()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String detail = resp.body() == null ? "" : resp.body();
            throw new IOException("ASR HTTP " + resp.statusCode() + ": " + detail.substring(0, Math.min(200, detail.length())));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        return root.has("text") ? root.get("text").getAsString().trim() : "";
    }

    private static void addPart(java.io.ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes());
    }
}
