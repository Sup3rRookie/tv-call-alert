package dev.tvcallalert;

import android.util.Log;

/**
 * Logging wrapper. Callers pass event names and reasons only: never keys, topics, URLs,
 * payloads or caller names.
 */
final class L {
    private static final String TAG = "TvCallAlert";

    private L() {
    }

    static void d(String event) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, event);
        }
    }

    static void w(String event) {
        Log.w(TAG, event);
    }

    /** Logs the exception class only, because messages can contain the relay URL. */
    static void w(String event, Throwable t) {
        Log.w(TAG, event + ": " + t.getClass().getSimpleName());
    }
}
