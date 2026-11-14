package dev.tvcallalert;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Pauses whatever is playing while the card is up and resumes it afterwards, but only when
 * this class is the one that paused it. Live TV and HDMI inputs cannot be paused.
 */
final class MediaPauser {
    private static final long CHECK_DELAY_MILLIS = 700;

    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioFocusRequest focus;
    private boolean pausedByUs;
    private Runnable pendingCheck;

    MediaPauser(Context context) {
        audio = context.getSystemService(AudioManager.class);
    }

    /**
     * Pauses playback.
     *
     * @param afterCheck run once the pause has had time to take effect
     */
    void pause(Runnable afterCheck) {
        final boolean wasActive = audio.isMusicActive();
        pausedByUs = false;
        if (wasActive) {
            sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        audio.requestAudioFocus(focus);
        pendingCheck = () -> {
            pendingCheck = null;
            pausedByUs = wasActive && !audio.isMusicActive();
            afterCheck.run();
        };
        main.postDelayed(pendingCheck, CHECK_DELAY_MILLIS);
    }

    void resume() {
        if (pendingCheck != null) {
            main.removeCallbacks(pendingCheck);
            pendingCheck = null;
        }
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
        if (pausedByUs) {
            pausedByUs = false;
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
    }

    private void sendKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
