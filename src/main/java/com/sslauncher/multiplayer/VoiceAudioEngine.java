package com.sslauncher.multiplayer;

import com.sslauncher.core.Log;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Microphone capture, Opus encoding/decoding and multi-speaker mixing.
 *
 * <p>One capture thread encodes 20 ms Opus frames and hands them to the
 * service, which writes them to every peer's voice stream. One playback
 * thread decodes each peer's stream into an independent jitter buffer and
 * mixes the buffers into the system output, applying the global output gain
 * and each peer's mute flag.</p>
 *
 * <p>The engine deliberately uses only the default Java Sound mixer; the
 * feature is meant to work out of the box with headsets and laptop audio.</p>
 */
final class VoiceAudioEngine {
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_MS = 20;
    static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000; // 960
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int MAX_PACKET = 1275;
    /** Two frames (40 ms) are kept before playback to absorb jitter. */
    private static final int PREBUFFER_FRAMES = 2;
    private static final int MAX_QUEUED_FRAMES = 10;
    /** Rough RMS² threshold that separates speech from room noise. */
    private static final long SPEAKING_ENERGY = 18_000_000L;
    private static final AudioFormat MONO = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final AudioFormat STEREO = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);

    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();
    private volatile Consumer<byte[]> packetSink = packet -> { };
    private volatile float inputGain = 1f;
    private volatile float outputGain = 1f;
    private volatile boolean micMuted;
    private volatile boolean running;
    private volatile boolean micAvailable;
    private volatile String error = "";
    private volatile long selfVoiceAt;

    private OpusEncoder encoder;
    private TargetDataLine mic;
    private SourceDataLine speaker;
    private boolean inputStereo;
    private boolean outputStereo;
    private Thread captureThread;
    private Thread playbackThread;

    /** One remote speaker's encoded-frame queue and decoder. */
    private static final class Receiver {
        final String peerId;
        final Deque<byte[]> queue = new ArrayDeque<>();
        final OpusDecoder decoder;
        final short[] pcm = new short[FRAME_SAMPLES];
        volatile boolean muted;
        volatile long lastVoiceAt;

        Receiver(String peerId) throws OpusException {
            this.peerId = peerId;
            this.decoder = new OpusDecoder(SAMPLE_RATE, 1);
        }
    }

    /**
     * Opens the audio devices and starts both loops.
     *
     * @return false when playback could not be started; {@link #error()}
     *         describes the reason.
     */
    synchronized boolean start(Consumer<byte[]> sink) {
        if (running) {
            packetSink = sink == null ? packet -> { } : sink;
            return true;
        }
        packetSink = sink == null ? packet -> { } : sink;
        error = "";
        try {
            encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(32_000);
            encoder.setUseInbandFEC(true);
            encoder.setPacketLossPercent(5);
            encoder.setComplexity(5);
        } catch (OpusException e) {
            error = "Opus 编码器初始化失败：" + e.getMessage();
            Log.warn("语音：Opus 编码器初始化失败: " + e.getMessage());
            return false;
        }
        if (!openSpeaker()) {
            return false;
        }
        openMicrophone();
        running = true;
        playbackThread = new Thread(this::playbackLoop, "ss-voice-play");
        playbackThread.setDaemon(true);
        playbackThread.setPriority(Thread.NORM_PRIORITY + 2);
        playbackThread.start();
        if (micAvailable) {
            captureThread = new Thread(this::captureLoop, "ss-voice-capture");
            captureThread.setDaemon(true);
            captureThread.setPriority(Thread.NORM_PRIORITY + 2);
            captureThread.start();
        }
        Log.info("语音：音频引擎已启动（麦克风" + (micAvailable ? "可用" : "不可用")
                + "，输出" + (outputStereo ? "立体声" : "单声道") + "）");
        return true;
    }

    private boolean openSpeaker() {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, MONO);
            if (AudioSystem.isLineSupported(info)) {
                speaker = (SourceDataLine) AudioSystem.getLine(info);
                speaker.open(MONO, FRAME_BYTES * 4);
                outputStereo = false;
            } else {
                info = new DataLine.Info(SourceDataLine.class, STEREO);
                if (!AudioSystem.isLineSupported(info)) {
                    error = "系统没有支持 48 kHz 的音频输出设备";
                    return false;
                }
                speaker = (SourceDataLine) AudioSystem.getLine(info);
                speaker.open(STEREO, FRAME_BYTES * 8);
                outputStereo = true;
            }
            speaker.start();
            return true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            error = "无法打开音频输出：" + e.getMessage();
            speaker = null;
            return false;
        }
    }

    private void openMicrophone() {
        micAvailable = false;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, MONO);
            if (AudioSystem.isLineSupported(info)) {
                mic = (TargetDataLine) AudioSystem.getLine(info);
                mic.open(MONO, FRAME_BYTES * 4);
                inputStereo = false;
            } else {
                info = new DataLine.Info(TargetDataLine.class, STEREO);
                if (!AudioSystem.isLineSupported(info)) {
                    Log.warn("语音：未找到可用的麦克风，将只能收听");
                    return;
                }
                mic = (TargetDataLine) AudioSystem.getLine(info);
                mic.open(STEREO, FRAME_BYTES * 8);
                inputStereo = true;
            }
            mic.start();
            micAvailable = true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            Log.warn("语音：无法打开麦克风: " + e.getMessage());
            mic = null;
            micAvailable = false;
        }
    }

    synchronized void stop() {
        if (!running && mic == null && speaker == null) {
            return;
        }
        running = false;
        closeLine(mic);
        mic = null;
        closeLine(speaker);
        speaker = null;
        Thread capture = captureThread;
        Thread playback = playbackThread;
        captureThread = null;
        playbackThread = null;
        join(capture);
        join(playback);
        receivers.clear();
        packetSink = packet -> { };
        selfVoiceAt = 0;
        micAvailable = false;
        Log.info("语音：音频引擎已停止");
    }

    private static void closeLine(javax.sound.sampled.DataLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
            line.close();
        } catch (RuntimeException ignored) {
            // The device may already be gone.
        }
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ peers

    void addReceiver(String peerId) {
        if (peerId == null || peerId.isBlank() || receivers.containsKey(peerId)) {
            return;
        }
        try {
            Receiver receiver = new Receiver(peerId);
            receivers.putIfAbsent(peerId, receiver);
        } catch (OpusException e) {
            Log.warn("语音：无法为 " + shortId(peerId) + " 创建解码器: " + e.getMessage());
        }
    }

    void removeReceiver(String peerId) {
        Receiver receiver = receivers.remove(peerId);
        if (receiver != null) {
            synchronized (receiver.queue) {
                receiver.queue.clear();
            }
        }
    }

    void setMuted(String peerId, boolean muted) {
        Receiver receiver = receivers.get(peerId);
        if (receiver != null) {
            receiver.muted = muted;
            if (muted) {
                receiver.lastVoiceAt = 0;
            }
        }
    }

    void pushPacket(String peerId, byte[] packet) {
        Receiver receiver = receivers.get(peerId);
        if (receiver == null || packet == null) {
            return;
        }
        synchronized (receiver.queue) {
            if (receiver.muted) {
                return;
            }
            receiver.queue.addLast(packet);
            while (receiver.queue.size() > MAX_QUEUED_FRAMES) {
                receiver.queue.removeFirst();
            }
        }
    }

    boolean isSpeaking(String peerId) {
        Receiver receiver = receivers.get(peerId);
        return receiver != null && !receiver.muted
                && System.currentTimeMillis() - receiver.lastVoiceAt < 450;
    }

    boolean isSelfSpeaking() {
        return !micMuted && System.currentTimeMillis() - selfVoiceAt < 450;
    }

    // ---------------------------------------------------------------- controls

    void setInputVolume(int percent) {
        inputGain = Math.max(0, Math.min(200, percent)) / 100f;
    }

    void setOutputVolume(int percent) {
        outputGain = Math.max(0, Math.min(200, percent)) / 100f;
    }

    void setMicMuted(boolean muted) {
        micMuted = muted;
    }

    boolean micAvailable() {
        return micAvailable;
    }

    String error() {
        return error;
    }

    // ------------------------------------------------------------------ loops

    private void captureLoop() {
        int bytesPerFrame = inputStereo ? FRAME_BYTES * 2 : FRAME_BYTES;
        byte[] input = new byte[bytesPerFrame];
        int filled = 0;
        short[] pcm = new short[FRAME_SAMPLES];
        byte[] packet = new byte[MAX_PACKET];
        while (running) {
            int read;
            try {
                read = mic.read(input, filled, input.length - filled);
            } catch (RuntimeException e) {
                if (running) {
                    Log.warn("语音：麦克风读取失败: " + e.getMessage());
                }
                break;
            }
            if (read <= 0) {
                continue;
            }
            filled += read;
            if (filled < input.length) {
                continue;
            }
            filled = 0;
            if (inputStereo) {
                downmixStereo(input, pcm);
            } else {
                bytesToShorts(input, pcm);
            }
            applyGain(pcm, inputGain);
            if (energy(pcm) > SPEAKING_ENERGY) {
                selfVoiceAt = System.currentTimeMillis();
            }
            if (micMuted) {
                continue;
            }
            try {
                int size = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.length);
                if (size > 0) {
                    packetSink.accept(Arrays.copyOf(packet, size));
                }
            } catch (OpusException e) {
                Log.warn("语音：Opus 编码失败: " + e.getMessage());
            }
        }
    }

    private void playbackLoop() {
        int[] mix = new int[FRAME_SAMPLES];
        byte[] out = new byte[outputStereo ? FRAME_BYTES * 2 : FRAME_BYTES];
        long next = System.nanoTime();
        while (running) {
            Arrays.fill(mix, 0);
            for (Receiver receiver : receivers.values()) {
                // Drain even while muted so unmuting does not replay old audio.
                byte[] packet = pollFrame(receiver);
                if (packet == null || receiver.muted) {
                    continue;
                }
                int samples;
                try {
                    samples = receiver.decoder.decode(packet, 0, packet.length,
                            receiver.pcm, 0, FRAME_SAMPLES, false);
                } catch (OpusException e) {
                    continue;
                }
                if (samples <= 0) {
                    continue;
                }
                long level = 0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int value = receiver.pcm[i];
                    mix[i] += value;
                    level += (long) value * value;
                }
                if (level > SPEAKING_ENERGY) {
                    receiver.lastVoiceAt = System.currentTimeMillis();
                }
            }
            encodeOutput(mix, out);
            try {
                speaker.write(out, 0, out.length);
            } catch (RuntimeException e) {
                if (running) {
                    Log.warn("语音：音频输出失败: " + e.getMessage());
                }
                break;
            }
            next += FRAME_MS * 1_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else if (sleep < -2L * FRAME_MS * 1_000_000L) {
                // We fell behind; do not try to catch up by bursting frames.
                next = System.nanoTime();
            }
        }
    }

    private static byte[] pollFrame(Receiver receiver) {
        synchronized (receiver.queue) {
            while (receiver.queue.size() > PREBUFFER_FRAMES * 3) {
                receiver.queue.removeFirst();
            }
            if (receiver.queue.size() < PREBUFFER_FRAMES) {
                return null;
            }
            return receiver.queue.pollFirst();
        }
    }

    private void encodeOutput(int[] mix, byte[] out) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int value = Math.round(mix[i] * outputGain);
            if (value > Short.MAX_VALUE) {
                value = Short.MAX_VALUE;
            } else if (value < Short.MIN_VALUE) {
                value = Short.MIN_VALUE;
            }
            int offset = outputStereo ? i * 4 : i * 2;
            out[offset] = (byte) (value & 0xFF);
            out[offset + 1] = (byte) ((value >> 8) & 0xFF);
            if (outputStereo) {
                out[offset + 2] = out[offset];
                out[offset + 3] = out[offset + 1];
            }
        }
    }

    // ----------------------------------------------------------- PCM helpers

    static void bytesToShorts(byte[] bytes, short[] out) {
        int count = Math.min(out.length, bytes.length / 2);
        for (int i = 0; i < count; i++) {
            out[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
    }

    static void downmixStereo(byte[] bytes, short[] out) {
        int count = Math.min(out.length, bytes.length / 4);
        for (int i = 0; i < count; i++) {
            out[i] = (short) ((((bytes[i * 4] & 0xFF) | (bytes[i * 4 + 1] << 8))
                    + ((bytes[i * 4 + 2] & 0xFF) | (bytes[i * 4 + 3] << 8))) / 2);
        }
    }

    static void applyGain(short[] pcm, float gain) {
        if (Math.abs(gain - 1f) < 0.001f) {
            return;
        }
        for (int i = 0; i < pcm.length; i++) {
            int value = Math.round(pcm[i] * gain);
            if (value > Short.MAX_VALUE) {
                value = Short.MAX_VALUE;
            } else if (value < Short.MIN_VALUE) {
                value = Short.MIN_VALUE;
            }
            pcm[i] = (short) value;
        }
    }

    static long energy(short[] pcm) {
        long total = 0;
        for (short value : pcm) {
            total += (long) value * value;
        }
        return total;
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 10 ? String.valueOf(id)
                : id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }
}
