package dev.tvcallalert;

import android.content.Context;
import android.content.SharedPreferences;
import dev.tvcallalert.core.Codec;
import dev.tvcallalert.core.Kdf;
import dev.tvcallalert.core.Limits;
import dev.tvcallalert.core.NameSanitizer;
import dev.tvcallalert.core.SlotKeys;
import dev.tvcallalert.core.SlotStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Paired phones, kept in app-private storage. One instance per process. */
final class Store implements SlotStore {
    private static final String PREFS = "slots";
    private static Store instance;

    private final SharedPreferences prefs;
    private final String defaultName;
    private final SlotKeys[] keys = new SlotKeys[Limits.MAX_SLOTS + 1];
    private final boolean[] loaded = new boolean[Limits.MAX_SLOTS + 1];

    private Store(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        defaultName = context.getString(R.string.default_name);
    }

    static synchronized Store get(Context context) {
        if (instance == null) {
            instance = new Store(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public synchronized SlotKeys keys(int slot) {
        if (slot < 1 || slot > Limits.MAX_SLOTS) {
            return null;
        }
        if (!loaded[slot]) {
            byte[] seed = Codec.parseSeedCode(prefs.getString("seed" + slot, null));
            keys[slot] = seed == null ? null : Kdf.derive(seed);
            loaded[slot] = true;
        }
        return keys[slot];
    }

    @Override
    public synchronized long highWater(int slot) {
        return prefs.getLong("hw" + slot, 0);
    }

    @Override
    public synchronized void setHighWater(int slot, long ts) {
        prefs.edit().putLong("hw" + slot, ts).apply();
    }

    synchronized boolean isPaired(int slot) {
        return keys(slot) != null;
    }

    synchronized String name(int slot) {
        return NameSanitizer.clean(prefs.getString("name" + slot, null), defaultName);
    }

    /** Creates a fresh seed for the slot, replacing any previous pairing. */
    synchronized void pair(int slot, String name) {
        byte[] seed = new byte[Limits.SEED_BYTES];
        new SecureRandom().nextBytes(seed);
        prefs.edit()
                .putString("seed" + slot, Codec.formatSeedCode(seed))
                .putString("name" + slot, NameSanitizer.clean(name, defaultName))
                .putLong("hw" + slot, 0)
                .apply();
        loaded[slot] = false;
    }

    synchronized void rename(int slot, String name) {
        prefs.edit().putString("name" + slot, NameSanitizer.clean(name, defaultName)).apply();
    }

    synchronized void remove(int slot) {
        prefs.edit().remove("seed" + slot).remove("name" + slot).remove("hw" + slot).apply();
        loaded[slot] = false;
    }

    /** The string typed into the phone: slot digit, hyphen, grouped seed code. */
    synchronized String pairingString(int slot) {
        String code = prefs.getString("seed" + slot, null);
        return code == null ? null : slot + "-" + code;
    }

    synchronized List<String> downTopics() {
        List<String> topics = new ArrayList<>();
        for (int i = 1; i <= Limits.MAX_SLOTS; i++) {
            SlotKeys k = keys(i);
            if (k != null) {
                topics.add(k.topicDown);
            }
        }
        return topics;
    }
}
