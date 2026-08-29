package com.mcaibridge.voice;

/** 语音识别引擎（输入 PCM16 单声道 WAV 字节，输出文本）。 */
public interface AsrEngine {
    String name();

    String transcribe(byte[] wavBytes) throws Exception;
}
