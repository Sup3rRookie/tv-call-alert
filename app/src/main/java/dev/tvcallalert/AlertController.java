package dev.tvcallalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import dev.tvcallalert.core.AckBuilder;
import dev.tvcallalert.core.AlertPipeline;
import dev.tvcallalert.core.Decision;
import dev.tvcallalert.core.Rejection;
import dev.tvcallalert.core.RelayClock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the one call card that can be on screen. Everything except {@link #pipeline()},
 * {@link #clock()} and {@link #countInvalid()} must be called on the main thread.
 */
final class AlertController {
    /** How the card can currently be drawn. */
    enum DisplayMode { ACCESSIBILITY, OVERLAY, NONE }

    private static final long CARD_TIMEOUT_MILLIS = 45_000;
    private static AlertController instance;

    private final Context app;
    private final Store store;
    private final AlertPipeline pipeline;
    private final RelayClock clock = new RelayClock();
    private final AckSender acks = new AckSender();
    private final MediaPauser pauser;
    private final RingPlayer ring = new RingPlayer();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger invalid = new AtomicInteger();
    private final Runnable timeout = () -> dismiss(AckBuilder.Status.EXPIRED);

    private AnimeCardView card;
    private Context cardHost;
    private int cardSlot;
    private String cardNonce;
    private BroadcastReceiver homeReceiver;

    private AlertController(Context app) {
        this.app = app;
        this.store = Store.get(app);
        this.pipeline = new AlertPipeline(store);
        this.pauser = new MediaPauser(app);
    }

    static synchronized AlertController get(Context context) {
        if (instance == null) {
            instance = new AlertController(context.getApplicationContext());
        }
        return instance;
    }

    AlertPipeline pipeline() {
        return pipeline;
    }

    RelayClock clock() {
        return clock;
    }

    void countInvalid() {
        invalid.incrementAndGet();
    }

    int invalidCount() {
        return invalid.get();
    }

    DisplayMode displayMode() {
        if (AlertA11yService.get() != null) {
            return DisplayMode.ACCESSIBILITY;
        }
        return Settings.canDrawOverlays(app) ? DisplayMode.OVERLAY : DisplayMode.NONE;
    }

    void onDecision(Decision d) {
        switch (d.kind) {
            case SHOW:
                if (!app.getSystemService(PowerManager.class).isInteractive()) {
                    ack(AckBuilder.Status.ASLEEP, d.slot, d.nonce);
                } else if (show(d.slot, d.nonce, store.name(d.slot))) {
                    ack(AckBuilder.Status.SHOWN, d.slot, d.nonce);
                } else {
                    ack(AckBuilder.Status.FAILED, d.slot, d.nonce);
                }
                break;
            case CANCEL:
                if (card != null && d.slot == cardSlot && d.ref.equals(cardNonce)) {
                    dismiss(null);
                }
                ack(AckBuilder.Status.CANCELLED, d.slot, d.nonce);
                break;
            case PONG:
                ack(AckBuilder.Status.PONG, d.slot, d.nonce);
                break;
            default:
                onDrop(d);
                break;
        }
    }

    private void onDrop(Decision d) {
        if (!d.authenticated) {
            countInvalid();
            return;
        }
        if (d.rejection == Rejection.STALE || d.rejection == Rejection.FUTURE) {
            ack(AckBuilder.Status.STALE, d.slot, d.nonce);
        } else if (d.rejection == Rejection.RATE_LIMITED) {
            ack(AckBuilder.Status.LIMITED, d.slot, d.nonce);
        } else if (d.rejection == Rejection.REPLAYED && card != null
                && d.slot == cardSlot && d.nonce.equals(cardNonce)) {
            // The phone retried because our first ack was lost.
            ack(AckBuilder.Status.SHOWN, d.slot, d.nonce);
        }
    }

    /** Shows the card for the setup screen's test button. */
    boolean showTest() {
        return show(0, null, app.getString(R.string.test_name));
    }

    private boolean show(int slot, String nonce, String name) {
        if (card != null) {
            // One card at a time: a new call takes over the card that is already up.
            cardSlot = slot;
            cardNonce = nonce;
            card.restart(name);
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, CARD_TIMEOUT_MILLIS);
            return true;
        }
        AnimeCardView view = attach(name);
        if (view == null) {
            return false;
        }
        card = view;
        cardSlot = slot;
        cardNonce = nonce;
        view.setOnDismiss(() -> dismiss(AckBuilder.Status.DISMISSED));
        pauser.pause(ring::start);
        main.postDelayed(timeout, CARD_TIMEOUT_MILLIS);
        homeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                dismiss(AckBuilder.Status.DISMISSED);
            }
        };
        app.registerReceiver(homeReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        return true;
    }

    /** Tries the accessibility overlay first, then the system overlay. */
    private AnimeCardView attach(String name) {
        AlertA11yService a11y = AlertA11yService.get();
        if (a11y != null) {
            AnimeCardView v = tryAttach(a11y,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, name);
            if (v != null) {
                return v;
            }
        }
        if (Settings.canDrawOverlays(app)) {
            return tryAttach(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, name);
        }
        return null;
    }

    private AnimeCardView tryAttach(Context host, int type, String name) {
        AnimeCardView view = new AnimeCardView(host, name, Portrait.load(app));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {
            host.getSystemService(WindowManager.class).addView(view, lp);
            view.requestFocus();
            cardHost = host;
            return view;
        } catch (RuntimeException e) {
            L.w("card attach failed", e);
            return null;
        }
    }

    /**
     * Removes the card.
     *
     * @param status what to tell the phone, or null to tell it nothing
     */
    private void dismiss(AckBuilder.Status status) {
        if (card == null) {
            return;
        }
        removeCardView();
        main.removeCallbacks(timeout);
        if (homeReceiver != null) {
            app.unregisterReceiver(homeReceiver);
            homeReceiver = null;
        }
        ring.stop();
        pauser.resume();
        if (status != null) {
            ack(status, cardSlot, cardNonce);
        }
        cardNonce = null;
        cardSlot = 0;
    }

    private void removeCardView() {
        try {
            cardHost.getSystemService(WindowManager.class).removeViewImmediate(card);
        } catch (RuntimeException e) {
            L.w("card remove failed", e);
        }
        card = null;
        cardHost = null;
    }

    /** The accessibility service is going away: its windows die with it. */
    void onHostGone(Context host) {
        if (card != null && cardHost == host) {
            dismiss(AckBuilder.Status.EXPIRED);
        }
    }

    /** A phone was removed on the setup screen: its card must not stay up. */
    void onSlotRemoved(int slot) {
        if (card != null && cardSlot == slot) {
            dismiss(null);
        }
    }

    private void ack(AckBuilder.Status status, int slot, String nonce) {
        if (slot < 1 || nonce == null) {
            return;
        }
        long now = clock.nowSeconds(SystemClock.elapsedRealtime());
        if (now < 0) {
            now = System.currentTimeMillis() / 1000;
        }
        acks.send(status, slot, now, nonce, store.keys(slot));
    }
}
