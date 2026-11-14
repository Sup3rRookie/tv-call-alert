package dev.tvcallalert;

import dev.tvcallalert.core.AckBuilder;
import dev.tvcallalert.core.Codec;
import dev.tvcallalert.core.SlotKeys;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Publishes signed acknowledgements to the phone, off the main thread. */
final class AckSender {
    private static final int TIMEOUT_MILLIS = 10_000;
    private static final int MAX_QUEUED = 16;

    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_QUEUED),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    void send(AckBuilder.Status status, int slot, long tsSeconds, String refNonce,
            SlotKeys keys) {
        if (keys == null || refNonce == null) {
            return;
        }
        final String line;
        try {
            line = AckBuilder.build(status, slot, tsSeconds, refNonce, keys);
        } catch (IllegalArgumentException e) {
            return;
        }
        final String topic = keys.topicAck;
        executor.execute(() -> post(topic, line));
    }

    private static void post(String topic, String line) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RelayService.RELAY_BASE + topic).openConnection();
            conn.setConnectTimeout(TIMEOUT_MILLIS);
            conn.setReadTimeout(TIMEOUT_MILLIS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            byte[] body = Codec.ascii(line);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                L.w("ack rejected by relay, http " + code);
            }
        } catch (IOException | RuntimeException e) {
            L.w("ack send failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
