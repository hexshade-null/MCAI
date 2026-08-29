package com.mcaibridge.voice;

/** PCM/WAV 16bit 单声道音频工具：字节转换、WAV 封装解析、重采样、能量 VAD。 */
public final class AudioPipeline {

    private AudioPipeline() {
    }

    /** 16bit 小端 PCM → short[] */
    public static short[] bytesToShortsLE(byte[] bytes) {
        short[] out = new short[bytes.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return out;
    }

    public static byte[] shortsToBytesLE(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            out[i * 2] = (byte) (pcm[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** 封装标准 44 字节头的 PCM16 单声道 WAV。 */
    public static byte[] wavFromShorts(short[] pcm, int sampleRate) {
        byte[] data = shortsToBytesLE(pcm);
        int total = 36 + data.length;
        byte[] out = new byte[44 + data.length];
        putAscii(out, 0, "RIFF");
        putIntLE(out, 4, total);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putIntLE(out, 16, 16);
        putShortLE(out, 20, (short) 1);              // PCM
        putShortLE(out, 22, (short) 1);              // mono
        putIntLE(out, 24, sampleRate);
        putIntLE(out, 28, sampleRate * 2);           // byte rate
        putShortLE(out, 32, (short) 2);              // block align
        putShortLE(out, 34, (short) 16);             // bits
        putAscii(out, 36, "data");
        putIntLE(out, 40, data.length);
        System.arraycopy(data, 0, out, 44, data.length);
        return out;
    }

    /** 解析 WAV 的 data 块（兼容偏移不为 44 的写法）。 */
    public static short[] wavToShorts(byte[] wav) {
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4);
            int size = getIntLE(wav, pos + 4);
            if ("data".equals(id)) {
                int len = Math.min(size, wav.length - pos - 8);
                byte[] data = new byte[len];
                System.arraycopy(wav, pos + 8, data, 0, len);
                return bytesToShortsLE(data);
            }
            pos += 8 + size + (size % 2);
        }
        throw new IllegalArgumentException("WAV 中未找到 data 块");
    }

    public static int wavSampleRate(byte[] wav) {
        return getIntLE(wav, 24);
    }

    /** 线性插值重采样（如 24k TTS 输出 → 48k SVC 输入）。 */
    public static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) return in;
        long outLen = (long) in.length * toRate / fromRate;
        short[] out = new short[(int) outLen];
        double step = (double) (in.length - 1) / Math.max(1, outLen - 1);
        double idx = 0;
        for (int i = 0; i < outLen; i++) {
            int i0 = (int) idx;
            int i1 = Math.min(in.length - 1, i0 + 1);
            double frac = idx - i0;
            out[i] = (short) Math.round(in[i0] * (1 - frac) + in[i1] * frac);
            idx += step;
        }
        return out;
    }

    /** 均方根能量（简单 VAD 依据）。 */
    public static double rms(short[] pcm) {
        if (pcm.length == 0) return 0;
        double sum = 0;
        for (short v : pcm) sum += (double) v * v;
        return Math.sqrt(sum / pcm.length);
    }

    /** 通用 AudioConverter 缺失时的字节转换（SVC 插件侧也用得到）。 */
    private static void putAscii(byte[] b, int pos, String s) {
        for (int i = 0; i < s.length(); i++) b[pos + i] = (byte) s.charAt(i);
    }

    private static void putIntLE(byte[] b, int pos, int v) {
        b[pos] = (byte) v;
        b[pos + 1] = (byte) (v >> 8);
        b[pos + 2] = (byte) (v >> 16);
        b[pos + 3] = (byte) (v >> 24);
    }

    private static void putShortLE(byte[] b, int pos, int v) {
        b[pos] = (byte) v;
        b[pos + 1] = (byte) (v >> 8);
    }

    private static int getIntLE(byte[] b, int pos) {
        return (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
    }
}
