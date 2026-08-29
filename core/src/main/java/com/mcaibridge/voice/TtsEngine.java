package com.mcaibridge.voice;

/** 语音合成引擎（输入文本，输出 PCM16 单声道 WAV 字节；null 表示无音频）。 */
public interface TtsEngine {
    String name();

    /** @return WAV 字节；null 表示该引擎不出音频（调用方可回退文字）。 */
    byte[] synthesizeWav(String text) throws Exception;
}
