package dev.tvcallalert;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Plays a synthesized two-tone ring on a loop. No audio files are bundled. */
final class RingPlayer {
    private static final int SAMPLE_RATE = 22050;
    private static final double LOOP_SECONDS = 2.0;
    private static final double BURST_SECONDS = 0.38;
    private static final double[] BURST_STARTS = {0.0, 0.5};

    private AudioTrack track;

    void start() {
        stop();
        short[] pcm = synthesize();
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            t.write(pcm, 0, pcm.length);
            t.setLoopPoints(0, pcm.length, -1);
            t.play();
            track = t;
        } catch (RuntimeException e) {
            L.w("ring start failed", e);
        }
    }

    void stop() {
        if (track != null) {
            try {
                track.pause();
                track.release();
            } catch (RuntimeException e) {
                L.w("ring stop failed", e);
            }
            track = null;
        }
    }

    private static short[] synthesize() {
        int total = (int) (SAMPLE_RATE * LOOP_SECONDS);
        short[] pcm = new short[total];
        for (double burstStart : BURST_STARTS) {
            int from = (int) (burstStart * SAMPLE_RATE);
            int len = (int) (BURST_SECONDS * SAMPLE_RATE);
            for (int i = 0; i < len && from + i < total; i++) {
                double t = i / (double) SAMPLE_RATE;
                // Two close tones give the classic warbling ring.
                double wave = Math.sin(2 * Math.PI * 1318.5 * t) + Math.sin(2 * Math.PI * 1568.0 * t);
                double tremolo = 0.6 + 0.4 * Math.sin(2 * Math.PI * 22 * t);
                double edge = Math.min(1.0, Math.min(i, len - i) / (0.012 * SAMPLE_RATE));
                pcm[from + i] = (short) (wave * 0.5 * tremolo * edge * 0.45 * Short.MAX_VALUE);
            }
        }
        return pcm;
    }
}
