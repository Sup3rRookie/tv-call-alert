// Remembers the ts of the last message this phone signed, across reloads,
// so the next one is always strictly greater (see nextTs in protocol.js).
// The value is a plain timestamp, not a secret. This is the only file that
// touches web storage, and the only thing it ever writes is that number.

import { isTs, nextTs } from './protocol.js';

const KEY = 'rt1.lastTs';

function defaultStorage() {
  return globalThis.localStorage; // may throw when storage is blocked
}

/**
 * Returns { reserve(nowMs) }. reserve() picks the ts for a new message and
 * records it before returning, so two messages signed back to back can never
 * share a ts. Storage failures are swallowed: the in-memory value still
 * keeps one page session monotonic.
 */
export function createTsClock(getStorage = defaultStorage) {
  let memory = null;

  function read() {
    try {
      const text = getStorage().getItem(KEY);
      if (typeof text !== 'string' || !/^[1-9][0-9]{9}$/.test(text)) return null;
      return Number(text);
    } catch {
      return null;
    }
  }

  function write(ts) {
    try {
      getStorage().setItem(KEY, String(ts));
    } catch {
      // Private mode or full storage: nothing to do.
    }
  }

  return {
    reserve(nowMs) {
      const stored = read(); // re-read each time: another tab may have sent
      let last = memory;
      if (isTs(stored) && (last === null || stored > last)) last = stored;
      const ts = nextTs(nowMs, last);
      memory = ts;
      write(ts);
      return ts;
    },
  };
}
