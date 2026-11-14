// RT1 protocol, phone side. Pure logic: no DOM, no globals.
// Every function that needs crypto takes a SubtleCrypto instance as `subtle`.
// The contract is docs/PROTOCOL.md.

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const BASE64URL_ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

export const SEED_BYTES = 16;
export const SEED_CODE_CHARS = 26;
export const MAX_LINE_CHARS = 200;
export const MAX_PAIRING_INPUT_CHARS = 80;
export const MAX_EVENT_CHARS = 4096;

export const MESSAGE_TYPES = Object.freeze(['alert', 'cancel', 'ping']);
export const ACK_STATUSES = Object.freeze([
  'shown',
  'dismissed',
  'expired',
  'cancelled',
  'asleep',
  'stale',
  'limited',
  'failed',
  'pong',
]);

const SLOT_RE = /^[1-4]$/;
const NONCE_RE = /^[A-Za-z0-9_-]{22}$/;
const TOPIC_RE = /^rt[0-9a-f]{40}$/;
const ACK_RE = new RegExp(
  '^RA1\\|(' +
    ACK_STATUSES.join('|') +
    ')\\|([1-4])\\|([1-9][0-9]{9})\\|([A-Za-z0-9_-]{22})\\|([A-Za-z0-9_-]{43})$',
);
// Spaces, ASCII hyphen and the Unicode dashes U+2010 to U+2015 that phone
// keyboards like to substitute.
const SEPARATORS_RE = /[\s\-‐-―]/g;

const HMAC_PARAMS = Object.freeze({ name: 'HMAC', hash: 'SHA-256' });

/**
 * Error for a rejected pairing string. The message is a fixed sentence chosen
 * by `code`; it never contains any part of the input.
 */
export class PairingError extends Error {
  constructor(code) {
    super(PAIRING_ERROR_TEXT[code] ?? PAIRING_ERROR_TEXT.format);
    this.name = 'PairingError';
    this.code = code;
  }
}

const PAIRING_ERROR_TEXT = Object.freeze({
  type: 'Pairing code must be text.',
  size: 'Pairing code is too long.',
  length: 'Pairing code has the wrong length.',
  slot: 'Pairing code must start with the slot number 1 to 4.',
  chars: 'Pairing code contains characters that are not allowed.',
  padding: 'Pairing code is not valid.',
  format: 'Pairing code is not valid.',
});

function asciiBytes(text) {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i += 1) {
    const c = text.charCodeAt(i);
    if (c > 0x7e || c < 0x20) throw new RangeError('not printable ASCII');
    out[i] = c;
  }
  return out;
}

function hex(bytes) {
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out;
}

/**
 * Strict RFC 4648 base32 decode of a 26 character seed code (upper case, no
 * padding characters). Throws PairingError unless it is exactly 16 bytes with
 * zero padding bits.
 */
export function base32DecodeSeed(code) {
  if (typeof code !== 'string') throw new PairingError('type');
  if (code.length !== SEED_CODE_CHARS) throw new PairingError('length');
  const out = new Uint8Array(SEED_BYTES);
  let buffer = 0;
  let bits = 0;
  let index = 0;
  for (let i = 0; i < code.length; i += 1) {
    const value = BASE32_ALPHABET.indexOf(code[i]);
    if (value < 0) {
      out.fill(0);
      throw new PairingError('chars');
    }
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out[index] = (buffer >>> bits) & 0xff;
      index += 1;
      buffer &= (1 << bits) - 1;
    }
  }
  // 26 * 5 = 130 bits: 128 of data, 2 of padding that must be zero.
  if (buffer !== 0) {
    out.fill(0);
    throw new PairingError('padding');
  }
  return out;
}

/**
 * Parse the pairing string shown on the TV: `<slot>-<26 base32 chars>`.
 * Spaces, hyphens and case are ignored. Returns { slot, seed } where slot is
 * the digit as a string and seed is a fresh 16 byte Uint8Array that the
 * caller should zero after deriving keys.
 */
export function parsePairingString(input) {
  if (typeof input !== 'string') throw new PairingError('type');
  if (input.length > MAX_PAIRING_INPUT_CHARS) throw new PairingError('size');
  const compact = input.replace(SEPARATORS_RE, '').toUpperCase();
  if (compact.length !== 1 + SEED_CODE_CHARS) throw new PairingError('length');
  const slot = compact[0];
  if (!SLOT_RE.test(slot)) throw new PairingError('slot');
  const seed = base32DecodeSeed(compact.slice(1));
  return { slot, seed };
}

/** Unpadded base64url of a byte array. */
export function base64urlEncode(bytes) {
  let out = '';
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    out +=
      BASE64URL_ALPHABET[(n >>> 18) & 63] +
      BASE64URL_ALPHABET[(n >>> 12) & 63] +
      BASE64URL_ALPHABET[(n >>> 6) & 63] +
      BASE64URL_ALPHABET[n & 63];
  }
  const rest = bytes.length - i;
  if (rest === 1) {
    const n = bytes[i] << 16;
    out += BASE64URL_ALPHABET[(n >>> 18) & 63] + BASE64URL_ALPHABET[(n >>> 12) & 63];
  } else if (rest === 2) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8);
    out +=
      BASE64URL_ALPHABET[(n >>> 18) & 63] +
      BASE64URL_ALPHABET[(n >>> 12) & 63] +
      BASE64URL_ALPHABET[(n >>> 6) & 63];
  }
  return out;
}

/**
 * Strict unpadded base64url decode. Returns null for bad characters, an
 * impossible length or non-zero trailing bits (so each byte string has
 * exactly one accepted encoding).
 */
export function base64urlDecode(text) {
  if (typeof text !== 'string' || text.length % 4 === 1) return null;
  const out = new Uint8Array(Math.floor((text.length * 6) / 8));
  let buffer = 0;
  let bits = 0;
  let index = 0;
  for (let i = 0; i < text.length; i += 1) {
    const value = BASE64URL_ALPHABET.indexOf(text[i]);
    if (value < 0) return null;
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[index] = (buffer >>> bits) & 0xff;
      index += 1;
      buffer &= (1 << bits) - 1;
    }
  }
  if (buffer !== 0) return null;
  return out;
}

/**
 * Derive the two HMAC keys and the two relay topics from the 16 byte seed.
 * Both keys are imported as non-extractable CryptoKeys: kAuth can only sign,
 * kAck can only verify. Intermediate key bytes are zeroed before returning.
 * The caller still owns `seed` and should zero it.
 */
export async function derivePairing(seed, subtle) {
  if (!(seed instanceof Uint8Array) || seed.length !== SEED_BYTES) {
    throw new PairingError('format');
  }
  const seedKey = await subtle.importKey('raw', seed, HMAC_PARAMS, false, ['sign']);
  const mac = async (label) =>
    new Uint8Array(await subtle.sign('HMAC', seedKey, asciiBytes(label)));

  const authBytes = await mac('rt1/auth');
  const ackBytes = await mac('rt1/ack');
  try {
    const kAuth = await subtle.importKey('raw', authBytes, HMAC_PARAMS, false, ['sign']);
    const kAck = await subtle.importKey('raw', ackBytes, HMAC_PARAMS, false, ['verify']);
    const topicDown = 'rt' + hex(await mac('rt1/down')).slice(0, 40);
    const topicAck = 'rt' + hex(await mac('rt1/up')).slice(0, 40);
    return { kAuth, kAck, topicDown, topicAck };
  } finally {
    authBytes.fill(0);
    ackBytes.fill(0);
  }
}

/** True for a well formed relay topic name. */
export function isTopic(value) {
  return typeof value === 'string' && TOPIC_RE.test(value);
}

/** True for a well formed 22 character nonce. */
export function isNonce(value) {
  return typeof value === 'string' && NONCE_RE.test(value);
}

/** Turn 16 random bytes into a 22 character base64url nonce. */
export function makeNonce(randomBytes) {
  if (!(randomBytes instanceof Uint8Array) || randomBytes.length !== 16) {
    throw new RangeError('nonce needs exactly 16 random bytes');
  }
  return base64urlEncode(randomBytes);
}

/** Unix seconds from a millisecond clock reading. */
export function unixSeconds(nowMs) {
  return Math.floor(nowMs / 1000);
}

const TS_MIN = 1000000000;
const TS_MAX = 9999999999;

/** True for a value usable as a message ts: an integer of exactly 10 digits. */
export function isTs(value) {
  return Number.isInteger(value) && value >= TS_MIN && value <= TS_MAX;
}

/**
 * The ts for the next new message: `max(now, lastTs + 1)`, so every new
 * message is strictly newer than the one before even within one second or
 * after the clock steps back. `lastTs` is the ts of the previous message, or
 * anything that is not a valid ts (null, garbage from storage) when there is
 * none. A retry re-sends an existing line and must not call this.
 */
export function nextTs(nowMs, lastTs) {
  const now = unixSeconds(nowMs);
  const ts = isTs(lastTs) ? Math.max(now, lastTs + 1) : now;
  if (!isTs(ts)) throw new RangeError('clock is outside the 10 digit range');
  return ts;
}

/**
 * Build and sign one phone to TV line:
 * `RT1|<type>|<slot>|<ts>|<nonce>|<ref>|<mac>`.
 * `ts` is unix seconds. `ref` is the alert nonce for a cancel and must be
 * omitted (or "-") otherwise.
 */
export async function buildMessage({ subtle, kAuth, type, slot, ts, nonce, ref = '-' }) {
  if (!MESSAGE_TYPES.includes(type)) throw new RangeError('unknown message type');
  const slotText = String(slot);
  if (!SLOT_RE.test(slotText)) throw new RangeError('slot must be 1 to 4');
  if (!isTs(ts)) {
    throw new RangeError('ts must be 10 digit unix seconds');
  }
  if (!isNonce(nonce)) throw new RangeError('nonce must be 22 base64url characters');
  if (type === 'cancel') {
    if (!isNonce(ref)) throw new RangeError('cancel needs the alert nonce as ref');
  } else if (ref !== '-') {
    throw new RangeError('ref must be "-" unless the type is cancel');
  }
  const body = `RT1|${type}|${slotText}|${ts}|${nonce}|${ref}`;
  const mac = new Uint8Array(await subtle.sign('HMAC', kAuth, asciiBytes(body)));
  const line = `${body}|${base64urlEncode(mac)}`;
  if (line.length > MAX_LINE_CHARS) throw new RangeError('line too long');
  return line;
}

/**
 * Grammar check of one TV to phone line. Returns the fields, or null when
 * the line is not exactly `RA1|<status>|<slot>|<ts>|<ref>|<mac>`.
 * This does not check the mac: use verifyAck for anything you will trust.
 */
export function parseAck(line) {
  if (typeof line !== 'string' || line.length > MAX_LINE_CHARS) return null;
  const m = ACK_RE.exec(line);
  if (m === null) return null;
  return {
    status: m[1],
    slot: m[2],
    ts: Number(m[3]),
    ref: m[4],
    mac: m[5],
    body: line.slice(0, line.length - 44),
  };
}

/**
 * Full check of an ack line: grammar, slot, known ref, then the mac with
 * kAck through subtle.verify (constant time inside WebCrypto).
 * `isKnownRef(ref)` must return true only for nonces this session sent.
 * Returns { status, slot, ts, ref } or null. Never throws.
 */
export async function verifyAck({ subtle, kAck, slot, line, isKnownRef }) {
  try {
    const ack = parseAck(line);
    if (ack === null) return null;
    if (ack.slot !== String(slot)) return null;
    if (isKnownRef(ack.ref) !== true) return null;
    const mac = base64urlDecode(ack.mac);
    if (mac === null || mac.length !== 32) return null;
    const ok = await subtle.verify('HMAC', kAck, mac, asciiBytes(ack.body));
    if (ok !== true) return null;
    return { status: ack.status, slot: ack.slot, ts: ack.ts, ref: ack.ref };
  } catch {
    return null;
  }
}

/**
 * Pull the published line out of one relay stream event (the JSON text in
 * an SSE `data:` field). Returns the `message` string, or null for anything
 * that is not a plain message event of sane size.
 */
export function extractRelayMessage(eventData) {
  if (typeof eventData !== 'string' || eventData.length > MAX_EVENT_CHARS) return null;
  let parsed;
  try {
    parsed = JSON.parse(eventData);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  if (parsed.event !== 'message') return null;
  const { message } = parsed;
  if (typeof message !== 'string' || message.length > MAX_LINE_CHARS) return null;
  return message;
}
