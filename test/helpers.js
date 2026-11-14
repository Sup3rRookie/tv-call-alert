// Shared test helpers. All keys and seeds used in tests are obviously fake:
// they come from testvectors/vectors.tsv (00 01 02 ..., all zero, all ff).

import { readFileSync } from 'node:fs';
import { createHmac, webcrypto } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const subtle = webcrypto.subtle;

const HERE = dirname(fileURLToPath(import.meta.url));
export const TRIGGER_DIR = join(HERE, '..');
export const VECTORS_PATH = join(HERE, '..', '..', 'testvectors', 'vectors.tsv');

export function loadVectors() {
  const lines = readFileSync(VECTORS_PATH, 'utf8').split(/\r?\n/).filter((l) => l.length > 0);
  const header = lines[0].split('\t');
  return lines.slice(1).map((line) => {
    const cells = line.split('\t');
    if (cells.length !== header.length) throw new Error('malformed vector row');
    return Object.fromEntries(header.map((name, i) => [name, cells[i]]));
  });
}

export function hexToBytes(hex) {
  return Uint8Array.from(hex.match(/../g).map((pair) => parseInt(pair, 16)));
}

export function groupCode(code) {
  return code.match(/.{1,4}/g).join('-');
}

/** Independent HMAC-SHA256 with Node's own crypto, for cross checks and forgeries. */
export function nodeHmac(keyBytes, text) {
  return createHmac('sha256', Buffer.from(keyBytes)).update(text, 'ascii').digest();
}

/** Raw bytes of kAuth or kAck for a fake seed, computed outside the code under test. */
export function rawKey(seedHex, label) {
  return nodeHmac(hexToBytes(seedHex), label);
}

/** Sign an ack body the way the TV would, with whatever key bytes are given. */
export function signAck(keyBytes, status, slot, ts, ref) {
  const body = `RA1|${status}|${slot}|${ts}|${ref}`;
  return `${body}|${nodeHmac(keyBytes, body).toString('base64url')}`;
}
