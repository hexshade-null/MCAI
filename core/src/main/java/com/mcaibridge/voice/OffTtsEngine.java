package com.mcaibridge.voice;

/** 关闭 TTS：不出音频，调用方回退文字回复。 */
public class OffTtsEngine implements TtsEngine {
    @Override
    public String name() {
        return "off";
    }

    @Override
    public byte[] synthesizeWav(String text) {
        return null;
    }
}
