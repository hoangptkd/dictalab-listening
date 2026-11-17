package com.dictalab.service;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

/** One audio contract for direct providers, platform jobs, storage and playback. */
final class WavAudio {
    static byte[] finalizeAndValidate(byte[] bytes) {
        var header = header(bytes);
        if (header.getInt(4) == -1) {
            for (long offset = 12; offset + 8 <= bytes.length;) {
                int start = (int) offset;
                long size = Integer.toUnsignedLong(header.getInt(start + 4));
                if (header.getInt(start) == 0x61746164) {
                    if (size == 0xffffffffL) {
                        int pcmSize = bytes.length - start - 8;
                        if (pcmSize < 2 || pcmSize % 2 != 0) throw new IllegalStateException("Invalid PCM audio");
                        bytes = bytes.clone();
                        header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                        header.putInt(4, bytes.length - 8).putInt(start + 4, pcmSize);
                    }
                    break;
                }
                offset += 8 + size + size % 2;
            }
        }
        return validate(bytes);
    }

    static byte[] validate(byte[] bytes) {
        if (header(bytes).getInt(4) != bytes.length - 8) throw new IllegalStateException("Invalid WAV length");
        try (var audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            var format = audio.getFormat();
            if (format.getChannels() != 1 || format.getSampleRate() != 24000 || format.getSampleSizeInBits() != 16
                    || format.isBigEndian() || !format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                    || audio.getFrameLength() <= 0 || audio.readAllBytes().length != audio.getFrameLength() * 2)
                throw new IllegalStateException("Invalid PCM audio");
        } catch (java.io.IOException | javax.sound.sampled.UnsupportedAudioFileException invalid) {
            throw new IllegalStateException("Invalid WAV audio");
        }
        return bytes;
    }

    private static ByteBuffer header(byte[] bytes) {
        if (bytes == null || bytes.length < 44 || bytes.length > 3_000_000)
            throw new IllegalStateException("Invalid audio bytes");
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != 0x46464952 || header.getInt(8) != 0x45564157)
            throw new IllegalStateException("Invalid WAV signature");
        return header;
    }
    private WavAudio() {}
}
