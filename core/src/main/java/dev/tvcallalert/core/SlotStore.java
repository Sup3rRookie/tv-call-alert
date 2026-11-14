package dev.tvcallalert.core;

/** Persistent per-slot state, implemented by the app. */
public interface SlotStore {
    /** Keys for a paired slot, or null when the slot is empty. */
    SlotKeys keys(int slot);

    /** Highest accepted message timestamp for the slot, 0 when none. */
    long highWater(int slot);

    void setHighWater(int slot, long ts);
}
