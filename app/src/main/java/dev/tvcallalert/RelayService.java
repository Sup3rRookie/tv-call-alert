package dev.tvcallalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import dev.tvcallalert.core.BackoffPolicy;
import dev.tvcallalert.core.BoundedLineReader;
import dev.tvcallalert.core.Decision;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground service that holds one streaming HTTPS connection to the relay and hands every
 * message to the {@link AlertController}.
 */
public final class RelayService extends Service {
    static final String RELAY_BASE = "https://ntfy.sh/";
    private static final String CHANNEL_ID = "listening";
    private static final int NOTIFICATION_ID = 1;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** The relay sends a keepalive roughly every 45 s; silence this long means a dead socket. */
    private static final int READ_TIMEOUT_MILLIS = 100_000;
    private static final int MAX_LINE_BYTES = 4096;
    private static final long UNPAIRED_POLL_MILLIS = 30_000;

    private static volatile boolean connected;
    private static volatile RelayService running;

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BackoffPolicy backoff = new BackoffPolicy(new SecureRandom());
    private Thread worker;
    private volatile boolean stopped;
    private volatile HttpURLConnection current;
    private boolean wake;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver screenReceiver;

    static void start(Context context) {
        Context app = context.getApplicationContext();
        try {
            app.startForegroundService(new Intent(app, RelayService.class));
        } catch (RuntimeException e) {
            L.w("service start refused", e);
        }
    }

    static boolean isConnected() {
        return connected;
    }

    /** Drops the current connection so the topic list is re-read. */
    static void reconnectNow() {
        RelayService s = running;
        if (s != null) {
            s.kick();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        startForeground(NOTIFICATION_ID, buildNotification());
        registerTriggers();
        worker = new Thread(this::loop, "relay");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        kick();
        if (networkCallback != null) {
            try {
                getSystemService(ConnectivityManager.class)
                        .unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                L.w("network callback unregister failed", e);
            }
        }
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
        }
        connected = false;
        running = null;
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(getString(R.string.notification_title))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void registerTriggers() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                kick();
            }
        };
        try {
            getSystemService(ConnectivityManager.class)
                    .registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
            L.w("network callback register failed", e);
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // After standby the socket is usually dead without the OS noticing.
                kick();
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** Closes the live connection, clears the backoff and wakes the worker. */
    private void kick() {
        backoff.reset();
        final HttpURLConnection conn = current;
        if (conn != null) {
            // disconnect() touches the socket, so keep it off the main thread.
            new Thread(conn::disconnect, "relay-close").start();
        }
        synchronized (lock) {
            wake = true;
            lock.notifyAll();
        }
    }

    private void loop() {
        Store store = Store.get(this);
        while (!stopped) {
            List<String> topics = store.downTopics();
            if (topics.isEmpty()) {
                pause(UNPAIRED_POLL_MILLIS);
                continue;
            }
            long startedAt = SystemClock.elapsedRealtime();
            boolean pushedBack = false;
            try {
                pushedBack = stream(TextUtils.join(",", topics));
            } catch (IOException | RuntimeException e) {
                L.w("relay connection ended", e);
            } finally {
                connected = false;
                current = null;
            }
            if (stopped) {
                break;
            }
            backoff.connectionEnded(SystemClock.elapsedRealtime() - startedAt);
            pause(backoff.nextDelayMillis(pushedBack));
        }
    }

    /** Sleeps unless a kick arrived in the meantime. */
    private void pause(long millis) {
        synchronized (lock) {
            if (!wake && !stopped) {
                try {
                    lock.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            wake = false;
        }
    }

    /**
     * Streams until the connection drops.
     *
     * @return true when the relay answered 429 or 5xx
     */
    private boolean stream(String topics) throws IOException {
        URL url = new URL(RELAY_BASE + topics + "/json?since=60s");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        current = conn;
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            conn.setReadTimeout(READ_TIMEOUT_MILLIS);
            conn.setRequestProperty("Accept", "application/x-ndjson");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                L.w("relay refused stream, http " + code);
                return code == 429 || code >= 500;
            }
            AlertController controller = AlertController.get(this);
            try (InputStream in = conn.getInputStream()) {
                BoundedLineReader reader = new BoundedLineReader(in, MAX_LINE_BYTES);
                String line;
                while (!stopped && (line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        handle(line, controller);
                    }
                }
            }
            return false;
        } finally {
            conn.disconnect();
        }
    }

    private void handle(String line, AlertController controller) {
        final JSONObject event;
        try {
            event = new JSONObject(line);
        } catch (JSONException e) {
            controller.countInvalid();
            return;
        }
        String kind = event.optString("event");
        long elapsed = SystemClock.elapsedRealtime();
        if ("open".equals(kind) || "keepalive".equals(kind)) {
            long time = event.optLong("time", 0);
            if (time > 0) {
                controller.clock().sync(time, elapsed);
            }
            connected = true;
            return;
        }
        if (!"message".equals(kind)) {
            return;
        }
        long now = controller.clock().nowSeconds(elapsed);
        if (now < 0) {
            controller.countInvalid();
            return;
        }
        String topic = event.optString("topic", null);
        String message = event.optString("message", null);
        final Decision decision = controller.pipeline().process(topic, message, now);
        main.post(() -> controller.onDecision(decision));
    }
}
