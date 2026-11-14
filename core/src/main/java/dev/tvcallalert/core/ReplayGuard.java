package dev.tvcallalert.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers recently accepted nonces. Fails closed when full of live entries. */
public final class ReplayGuard {
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    /** True when the nonce was accepted within the TTL. */
    public boolean isReplay(String nonce, long nowSeconds) {
        expire(nowSeconds);
        return seen.containsKey(nonce);
    }

    /** True when the cache is full of unexpired entries, so record would return false. */
    boolean isFull(long nowSeconds) {
        expire(nowSeconds);
        return seen.size() >= Limits.NONCE_CACHE_SIZE;
    }

    /**
     * Records an accepted nonce.
     *
     * @return false when the cache is full of unexpired entries, in which case the caller
     *         must reject the message
     */
    public boolean record(String nonce, long nowSeconds) {
        expire(nowSeconds);
        if (seen.size() >= Limits.NONCE_CACHE_SIZE) {
            return false;
        }
        seen.put(nonce, nowSeconds);
        return true;
    }

    private void expire(long nowSeconds) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            long at = it.next().getValue();
            // An entry dated after now (clock stepped back) is kept until the clock catches
            // up, so a backwards step cannot reopen a replay window.
            if (nowSeconds - at > Limits.NONCE_TTL_SECONDS) {
                it.remove();
            }
        }
    }
}
