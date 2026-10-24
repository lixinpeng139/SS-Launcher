package com.sslauncher.multiplayer;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAudioEngineTest {
    @Test
    void decodesLittleEndianPcm() {
        byte[] bytes = {0x34, 0x12, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0x80};
        short[] pcm = new short[3];
        VoiceAudioEngine.bytesToShorts(bytes, pcm);
        assertArrayEquals(new short[]{0x1234, -1, Short.MIN_VALUE}, pcm);
    }

    @Test
    void downmixesStereoToMono() {
        byte[] stereo = new byte[8];
        // L = 1000, R = 2000
        stereo[0] = (byte) 0xE8;
        stereo[1] = 0x03;
        stereo[2] = (byte) 0xD0;
        stereo[3] = 0x07;
        // L = -1000, R = -2000
        stereo[4] = 0x18;
        stereo[5] = (byte) 0xFC;
        stereo[6] = 0x30;
        stereo[7] = (byte) 0xF8;
        short[] pcm = new short[2];
        VoiceAudioEngine.downmixStereo(stereo, pcm);
        assertArrayEquals(new short[]{1500, -1500}, pcm);
    }

    @Test
    void gainScalesAndClips() {
        short[] pcm = {1000, -1000, 30000, -30000};
        VoiceAudioEngine.applyGain(pcm, 0.5f);
        assertArrayEquals(new short[]{500, -500, 15000, -15000}, pcm);

        VoiceAudioEngine.applyGain(pcm, 4f);
        assertArrayEquals(new short[]{2000, -2000, Short.MAX_VALUE, Short.MIN_VALUE}, pcm);

        VoiceAudioEngine.applyGain(pcm, 0f);
        assertArrayEquals(new short[]{0, 0, 0, 0}, pcm);
    }

    @Test
    void energyIgnoresSign() {
        assertEquals(25, VoiceAudioEngine.energy(new short[]{3, -4}));
        assertEquals(0, VoiceAudioEngine.energy(new short[]{0, 0}));
    }

    @Test
    void opusRoundTripsOneFrame() throws Exception {
        OpusEncoder encoder = new OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(32_000);
        OpusDecoder decoder = new OpusDecoder(48_000, 1);

        short[] input = new short[VoiceAudioEngine.FRAME_SAMPLES];
        for (int i = 0; i < input.length; i++) {
            input[i] = (short) (12_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
        }
        byte[] packet = new byte[1275];
        int size = encoder.encode(input, 0, input.length, packet, 0, packet.length);
        assertTrue(size > 0 && size <= 1275, "encoded size " + size);

        short[] output = new short[input.length];
        int samples = decoder.decode(packet, 0, size, output, 0, output.length, false);
        assertEquals(input.length, samples);
    }
}
