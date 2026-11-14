package dev.tvcallalert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.tvcallalert.core.Limits;

/** Setup and status screen, driven with the TV remote. */
public final class MainActivity extends Activity {
    private static final long REFRESH_MILLIS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Button[] slotButtons = new Button[Limits.MAX_SLOTS + 1];
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, REFRESH_MILLIS);
        }
    };
    private Store store;
    private AlertController controller;
    private TextView relayStatus;
    private TextView displayStatus;
    private TextView droppedStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Pairing codes are shown here, so keep the screen out of screenshots and recents.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = Store.get(this);
        controller = AlertController.get(this);
        setContentView(buildLayout());
        RelayService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(refresher);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresher);
        super.onPause();
    }

    private ScrollView buildLayout() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        column.setPadding(pad, pad, pad, pad);

        TextView title = label(R.string.title, 34, R.color.text);
        column.addView(title);
        relayStatus = label(R.string.status_relay_disconnected, 20, R.color.text_dim);
        displayStatus = label(R.string.status_display_none, 20, R.color.text_dim);
        droppedStatus = label(R.string.status_display_none, 16, R.color.text_dim);
        column.addView(relayStatus);
        column.addView(displayStatus);
        column.addView(droppedStatus);

        for (int slot = 1; slot <= Limits.MAX_SLOTS; slot++) {
            final int s = slot;
            slotButtons[slot] = button(column);
            slotButtons[slot].setOnClickListener(v -> onSlotClicked(s));
        }
        Button test = button(column);
        test.setText(R.string.btn_test);
        test.setOnClickListener(v -> {
            if (!controller.showTest()) {
                Toast.makeText(this, R.string.test_not_ready, Toast.LENGTH_LONG).show();
            }
        });
        Button a11y = button(column);
        a11y.setText(R.string.btn_a11y);
        a11y.setOnClickListener(v ->
                openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button overlay = button(column);
        overlay.setText(R.string.btn_overlay);
        overlay.setOnClickListener(v -> openSettings(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        slotButtons[1].requestFocus();
        return scroll;
    }

    private void refresh() {
        boolean paired = !store.downTopics().isEmpty();
        if (!paired) {
            relayStatus.setText(R.string.status_relay_unpaired);
            relayStatus.setTextColor(getColor(R.color.text_dim));
        } else if (RelayService.isConnected()) {
            relayStatus.setText(R.string.status_relay_connected);
            relayStatus.setTextColor(getColor(R.color.good));
        } else {
            relayStatus.setText(R.string.status_relay_disconnected);
            relayStatus.setTextColor(getColor(R.color.bad));
        }
        switch (controller.displayMode()) {
            case ACCESSIBILITY:
                displayStatus.setText(R.string.status_display_a11y);
                displayStatus.setTextColor(getColor(R.color.good));
                break;
            case OVERLAY:
                displayStatus.setText(R.string.status_display_overlay);
                displayStatus.setTextColor(getColor(R.color.good));
                break;
            default:
                displayStatus.setText(R.string.status_display_none);
                displayStatus.setTextColor(getColor(R.color.bad));
                break;
        }
        droppedStatus.setText(getString(R.string.status_dropped, controller.invalidCount()));
        for (int slot = 1; slot <= Limits.MAX_SLOTS; slot++) {
            slotButtons[slot].setText(store.isPaired(slot)
                    ? getString(R.string.slot_paired, slot, store.name(slot))
                    : getString(R.string.slot_empty, slot));
        }
    }

    private void onSlotClicked(int slot) {
        if (!store.isPaired(slot)) {
            askName(slot, name -> {
                store.pair(slot, name);
                RelayService.reconnectNow();
                showCode(slot);
            });
            return;
        }
        CharSequence[] options = {
            getString(R.string.opt_show_code),
            getString(R.string.opt_rename),
            getString(R.string.opt_remove),
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_options_title, store.name(slot)))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showCode(slot);
                    } else if (which == 1) {
                        askName(slot, name -> store.rename(slot, name));
                    } else {
                        confirmRemove(slot);
                    }
                })
                .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(int slot, NameCallback callback) {
        final EditText input = new EditText(this);
        input.setHint(R.string.dlg_name_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Limits.MAX_NAME_CHARS)});
        if (store.isPaired(slot)) {
            input.setText(store.name(slot));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_name_title)
                .setView(input)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    callback.onName(input.getText().toString());
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCode(int slot) {
        String code = store.pairingString(slot);
        if (code == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_code_title, store.name(slot)))
                .setMessage(getString(R.string.dlg_code_body, code))
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        }
    }

    private void confirmRemove(int slot) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_remove_title, store.name(slot)))
                .setMessage(R.string.dlg_remove_body)
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    controller.onSlotRemoved(slot);
                    store.remove(slot);
                    RelayService.reconnectNow();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.settings_missing, Toast.LENGTH_LONG).show();
        }
    }

    private TextView label(int textRes, int sp, int colorRes) {
        TextView v = new TextView(this);
        v.setText(textRes);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(getColor(colorRes));
        v.setPadding(0, 0, 0, dp(10));
        return v;
    }

    private Button button(LinearLayout parent) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
