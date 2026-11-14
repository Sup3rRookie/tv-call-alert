package dev.tvcallalert;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Exists only so the call card can be drawn as an accessibility overlay, which needs no
 * "display over other apps" permission. It reads no screen content and filters no keys.
 */
public final class AlertA11yService extends AccessibilityService {
    private static volatile AlertA11yService instance;

    static AlertA11yService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        RelayService.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty: no events are used.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onDestroy() {
        AlertController.get(this).onHostGone(this);
        instance = null;
        super.onDestroy();
    }
}
