// Pairing storage in IndexedDB. The record holds two non-extractable
// CryptoKeys, the two topic names and the slot. The seed is never stored.
// Every function takes the IDBFactory (window.indexedDB) as a parameter.

import { isTopic } from './protocol.js';

const DB_NAME = 'rt1-trigger';
const DB_VERSION = 1;
const STORE = 'pairing';
const KEY = 'current';

function isSealedHmacKey(key, usage) {
  return (
    key !== null &&
    typeof key === 'object' &&
    key.type === 'secret' &&
    key.extractable === false &&
    key.algorithm !== undefined &&
    key.algorithm.name === 'HMAC' &&
    Array.isArray(key.usages) &&
    key.usages.length === 1 &&
    key.usages[0] === usage
  );
}

/** Shape check for a pairing record, used on both save and load. */
export function isPairingRecord(record) {
  return (
    record !== null &&
    typeof record === 'object' &&
    typeof record.slot === 'string' &&
    /^[1-4]$/.test(record.slot) &&
    isTopic(record.topicDown) &&
    isTopic(record.topicAck) &&
    record.topicDown !== record.topicAck &&
    isSealedHmacKey(record.kAuth, 'sign') &&
    isSealedHmacKey(record.kAck, 'verify')
  );
}

function openDb(idb) {
  return new Promise((resolve, reject) => {
    let request;
    try {
      request = idb.open(DB_NAME, DB_VERSION);
    } catch {
      reject(new Error('storage unavailable'));
      return;
    }
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) {
        request.result.createObjectStore(STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error('storage unavailable'));
    request.onblocked = () => reject(new Error('storage unavailable'));
  });
}

async function withStore(idb, mode, work) {
  const db = await openDb(idb);
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, mode);
      let result;
      const request = work(tx.objectStore(STORE));
      request.onsuccess = () => {
        result = request.result;
      };
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(new Error('storage failed'));
      tx.onabort = () => reject(new Error('storage failed'));
    });
  } finally {
    db.close();
  }
}

/** Returns the stored pairing, or null when there is none or it is malformed. */
export async function loadPairing(idb) {
  const record = await withStore(idb, 'readonly', (store) => store.get(KEY));
  return isPairingRecord(record) ? record : null;
}

export async function savePairing(idb, record) {
  if (!isPairingRecord(record)) throw new Error('refusing to store a malformed pairing');
  const { slot, topicDown, topicAck, kAuth, kAck } = record;
  await withStore(idb, 'readwrite', (store) =>
    store.put({ slot, topicDown, topicAck, kAuth, kAck }, KEY),
  );
}

export async function clearPairing(idb) {
  await withStore(idb, 'readwrite', (store) => store.delete(KEY));
}
