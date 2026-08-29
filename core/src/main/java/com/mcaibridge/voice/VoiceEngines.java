package com.mcaibridge.voice;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.core.AIBrain;

/** 按配置创建 ASR/TTS 引擎（Headless 与 GUI 共用）。 */
public final class VoiceEngines {

    private VoiceEngines() {
    }

    public static AsrEngine asr(BridgeConfig cfg) {
        return switch (cfg.asrProvider) {
            case "whisper-http" -> new WhisperHttpAsrEngine(cfg.asrBaseUrl, cfg.asrModel, cfg.asrApiKey);
            default -> new MockAsrEngine();
        };
    }

    public static TtsEngine tts(BridgeConfig cfg) {
        String key = cfg.ttsApiKey == null || cfg.ttsApiKey.isBlank() ? cfg.aiApiKey : cfg.ttsApiKey;
        return switch (cfg.ttsProvider) {
            case "zai", "http" -> new HttpTtsEngine(cfg.ttsBaseUrl, cfg.ttsModel, cfg.ttsVoice, key);
            case "edge" -> new EdgeTtsEngine(cfg.ttsVoice);
            default -> new OffTtsEngine();
        };
    }
}
