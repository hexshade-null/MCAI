package com.mcaibridge.voice;

/** Mock ASR：无网络/无 key 时的确定性语音识别，用于管线测试。 */
public class MockAsrEngine implements AsrEngine {
    private final String fixedText;

    public MockAsrEngine(String fixedText) {
        this.fixedText = fixedText;
    }

    public MockAsrEngine() {
        this("你好机器人");
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String transcribe(byte[] wavBytes) {
        return fixedText;
    }
}
