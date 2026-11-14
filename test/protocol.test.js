import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  PairingError,
  base32DecodeSeed,
  parsePairingString,
  base64urlEncode,
  base64urlDecode,
  derivePairing,
  makeNonce,
  unixSeconds,
  buildMessage,
  parseAck,
  verifyAck,
  extractRelayMessage,
  isTopic,
} from '../lib/protocol.js';
import { subtle, hexToBytes, nodeHmac, rawKey, signAck } from './helpers.js';

const FAKE_SEED_HEX = '000102030405060708090a0b0c0d0e0f';
const FAKE_CODE = 'AAAQEAYEAUDAOCAJBIFQYDIOB4';
const FAKE_PAIRING = `1-AAAQ-EAYE-AUDA-OCAJ-BIFQ-YDIO-B4`;
const NONCE = 'AAAAAAAAAAAAAAAAAAAAAA';
const OTHER_NONCE = 'BBBBBBBBBBBBBBBBBBBBBB';

function rejects(input, code) {
  assert.throws(
    () => parsePairingString(input),
    (error) => error instanceof PairingError && error.code === code,
    `expected "${code}" for ${JSON.stringify(input)}`,
  );
}

// ---------- pairing string ----------

test('pairing: accepts the canonical form', () => {
  const { slot, seed } = parsePairingString(FAKE_PAIRING);
  assert.equal(slot, '1');
  assert.deepEqual(seed, hexToBytes(FAKE_SEED_HEX));
});

test('pairing: tolerates Unicode dashes and tabs', () => {
  const fancy = FAKE_PAIRING.replaceAll('-', '–').replace('–', '\t‑ ');
  assert.deepEqual(parsePairingString(fancy).seed, hexToBytes(FAKE_SEED_HEX));
});

test('pairing: every slot 1 to 4 is accepted, others are not', () => {
  for (const slot of ['1', '2', '3', '4']) {
    assert.equal(parsePairingString(`${slot}-${FAKE_CODE}`).slot, slot);
  }
  for (const slot of ['0', '5', '9', 'A', '7']) rejects(`${slot}-${FAKE_CODE}`, 'slot');
});

test('pairing: wrong length is rejected', () => {
  rejects('', 'length');
  rejects('1', 'length');
  rejects('1-', 'length');
  rejects(FAKE_CODE, 'length'); // slot missing
  rejects(`1-${FAKE_CODE.slice(0, 25)}`, 'length');
  rejects(`1-${FAKE_CODE}A`, 'length');
  rejects(`1-${FAKE_CODE}${FAKE_CODE}`, 'length');
  rejects('   - - -  ', 'length');
});

test('pairing: bad characters are rejected', () => {
  for (const bad of ['0', '1', '8', '9', '=', '_', '/', '+', '!', 'é', 'А', '\u{1F600}']) {
    const code = FAKE_CODE.slice(0, 10) + bad + FAKE_CODE.slice(10 + bad.length);
    const input = `1-${code}`;
    assert.throws(() => parsePairingString(input), PairingError, JSON.stringify(bad));
  }
  rejects(`1-${FAKE_CODE.slice(0, 25)}=`, 'chars');
  rejects(`1-${FAKE_CODE.slice(0, 25)}\u0000`, 'chars');
});

test('pairing: non-zero padding bits are rejected', () => {
  // The last character carries 3 data bits and 2 padding bits.
  const allowedLast = new Set(['A', 'E', 'I', 'M', 'Q', 'U', 'Y', '4']);
  for (const last of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567') {
    const input = `1-${FAKE_CODE.slice(0, 25)}${last}`;
    if (allowedLast.has(last)) {
      assert.equal(parsePairingString(input).seed.length, 16);
    } else {
      rejects(input, 'padding');
    }
  }
});

test('pairing: oversized and non-string input is rejected before any work', () => {
  rejects('1-' + 'A'.repeat(79), 'size');
  rejects(' '.repeat(100000) + FAKE_PAIRING, 'size');
  rejects('A'.repeat(1000000), 'size');
  for (const value of [undefined, null, 12345, {}, [], ['1', FAKE_CODE], Symbol('x')]) {
    rejects(value, 'type');
  }
});

test('pairing: error text never contains the input', () => {
  const secretLooking = `1-${FAKE_CODE.slice(0, 25)}B`;
  for (const input of [secretLooking, `9-${FAKE_CODE}`, `1-${FAKE_CODE}7`, `1-${FAKE_CODE.slice(0, 20)}!!!!!!`]) {
    try {
      parsePairingString(input);
      assert.fail('should have thrown');
    } catch (error) {
      const text = `${error.message} ${error.stack} ${JSON.stringify(error)}`;
      assert.ok(!text.includes(FAKE_CODE.slice(0, 8)), 'leaked part of the code');
      assert.ok(!text.includes(input));
    }
  }
});

test('base32: direct decoder is strict about case, length and type', () => {
  assert.throws(() => base32DecodeSeed(FAKE_CODE.toLowerCase()), PairingError);
  assert.throws(() => base32DecodeSeed(FAKE_CODE + '='), PairingError);
  assert.throws(() => base32DecodeSeed(''), PairingError);
  assert.throws(() => base32DecodeSeed(null), PairingError);
  assert.deepEqual(base32DecodeSeed('77777777777777777777777774'), new Uint8Array(16).fill(0xff));
});

// ---------- base64url ----------

test('base64url: round trips and matches Node for every length 0 to 40', () => {
  for (let n = 0; n <= 40; n += 1) {
    const bytes = Uint8Array.from({ length: n }, (_, i) => (i * 37 + n * 11 + 251) & 0xff);
    const text = base64urlEncode(bytes);
    assert.equal(text, Buffer.from(bytes).toString('base64url'));
    assert.deepEqual(base64urlDecode(text), bytes);
  }
});

test('base64url: decoder rejects junk and non-canonical tails', () => {
  assert.equal(base64urlDecode('A'), null); // impossible length
  assert.equal(base64urlDecode('AA=='), null);
  assert.equal(base64urlDecode('AA+/'), null);
  assert.equal(base64urlDecode('AB'), null); // trailing bits set
  assert.equal(base64urlDecode('AAB'), null);
  assert.equal(base64urlDecode(42), null);
  assert.deepEqual(base64urlDecode('AA'), new Uint8Array([0]));
});

// ---------- derivation ----------

test('derive: keys match an independent HMAC and are sealed', async () => {
  const seed = hexToBytes(FAKE_SEED_HEX);
  const { kAuth, kAck, topicDown, topicAck } = await derivePairing(seed, subtle);

  assert.equal(kAuth.extractable, false);
  assert.equal(kAck.extractable, false);
  assert.deepEqual(kAuth.usages, ['sign']);
  assert.deepEqual(kAck.usages, ['verify']);
  await assert.rejects(subtle.exportKey('raw', kAuth));
  await assert.rejects(subtle.exportKey('raw', kAck));
  await assert.rejects(subtle.exportKey('jwk', kAuth));
  await assert.rejects(subtle.sign('HMAC', kAck, new Uint8Array(1)));

  const body = 'RT1|ping|1|1794700030|abcdefghijklmnopqrstuv|-';
  const viaSubtle = Buffer.from(await subtle.sign('HMAC', kAuth, Buffer.from(body, 'ascii')));
  assert.deepEqual(viaSubtle, nodeHmac(rawKey(FAKE_SEED_HEX, 'rt1/auth'), body));

  assert.equal(topicDown, 'rt' + nodeHmac(seed, 'rt1/down').toString('hex').slice(0, 40));
  assert.equal(topicAck, 'rt' + nodeHmac(seed, 'rt1/up').toString('hex').slice(0, 40));
  assert.ok(isTopic(topicDown) && isTopic(topicAck));
  assert.notEqual(topicDown, topicAck);
  assert.deepEqual(seed, hexToBytes(FAKE_SEED_HEX), 'derive must not mutate the seed');
});

test('derive: rejects seeds of the wrong shape', async () => {
  await assert.rejects(derivePairing(new Uint8Array(15), subtle), PairingError);
  await assert.rejects(derivePairing(new Uint8Array(17), subtle), PairingError);
  await assert.rejects(derivePairing(FAKE_SEED_HEX, subtle), PairingError);
  await assert.rejects(derivePairing(Array.from(hexToBytes(FAKE_SEED_HEX)), subtle), PairingError);
});

// ---------- message building ----------

test('nonce: 16 bytes become 22 base64url characters', () => {
  assert.equal(makeNonce(new Uint8Array(16)), 'AAAAAAAAAAAAAAAAAAAAAA');
  assert.equal(makeNonce(new Uint8Array(16).fill(0xff)), '_____________________w');
  assert.throws(() => makeNonce(new Uint8Array(15)), RangeError);
  assert.throws(() => makeNonce(new Uint8Array(17)), RangeError);
  assert.throws(() => makeNonce([0, 1, 2]), RangeError);
});

test('unixSeconds floors milliseconds', () => {
  assert.equal(unixSeconds(1794700000999), 1794700000);
  assert.equal(unixSeconds(1794700001000), 1794700001);
});

test('buildMessage: refuses anything outside the grammar', async () => {
  const { kAuth } = await derivePairing(hexToBytes(FAKE_SEED_HEX), subtle);
  const good = { subtle, kAuth, type: 'alert', slot: '1', ts: 1794700000, nonce: NONCE };
  assert.match(await buildMessage(good), /^RT1\|alert\|1\|1794700000\|A{22}\|-\|[A-Za-z0-9_-]{43}$/);
  assert.match(await buildMessage({ ...good, slot: 3 }), /^RT1\|alert\|3\|/);

  const bad = [
    { type: 'boom' },
    { type: 'alert|1' },
    { slot: '0' },
    { slot: '5' },
    { slot: '1|' },
    { ts: 999999999 },
    { ts: 10000000000 },
    { ts: 1794700000.5 },
    { ts: '1794700000' },
    { ts: Number.NaN },
    { nonce: 'short' },
    { nonce: NONCE + 'A' },
    { nonce: 'AAAAAAAAAAAAAAAAAAAAA|' },
    { ref: NONCE }, // ref on an alert
    { type: 'ping', ref: NONCE },
    { type: 'cancel' }, // cancel without ref
    { type: 'cancel', ref: '-' },
    { type: 'cancel', ref: 'AAAA|AAAAAAAAAAAAAAAAA' },
  ];
  for (const change of bad) {
    await assert.rejects(buildMessage({ ...good, ...change }), RangeError, JSON.stringify(change));
  }
});

// ---------- ack parsing and verification ----------

async function fixture() {
  const { kAck } = await derivePairing(hexToBytes(FAKE_SEED_HEX), subtle);
  const ackKey = rawKey(FAKE_SEED_HEX, 'rt1/ack');
  const authKey = rawKey(FAKE_SEED_HEX, 'rt1/auth');
  const known = new Set([NONCE]);
  const check = (line, slot = '1') =>
    verifyAck({ subtle, kAck, slot, line, isKnownRef: (ref) => known.has(ref) });
  return { kAck, ackKey, authKey, known, check };
}

test('ack: a correctly signed ack for a known ref is accepted', async () => {
  const { ackKey, check } = await fixture();
  for (const status of ['shown', 'dismissed', 'expired', 'cancelled', 'asleep', 'stale', 'limited', 'failed', 'pong']) {
    const ack = await check(signAck(ackKey, status, '1', 1794700002, NONCE));
    assert.deepEqual(ack, { status, slot: '1', ts: 1794700002, ref: NONCE });
  }
});

test('ack: wrong mac is rejected', async () => {
  const { ackKey, check } = await fixture();
  const good = signAck(ackKey, 'shown', '1', 1794700002, NONCE);
  const flipped = good.slice(0, -1) + (good.endsWith('A') ? 'E' : 'A');
  assert.equal(await check(flipped), null);
  const firstMacChar = good.length - 43;
  const swapped = good.slice(0, firstMacChar) + (good[firstMacChar] === 'A' ? 'B' : 'A') + good.slice(firstMacChar + 1);
  assert.equal(await check(swapped), null);
  assert.equal(await check(good.slice(0, firstMacChar) + 'A'.repeat(43)), null);
});

test('ack: signed fields cannot be altered', async () => {
  const { ackKey, check } = await fixture();
  const good = signAck(ackKey, 'shown', '1', 1794700002, NONCE);
  assert.equal(await check(good.replace('|shown|', '|dismissed|')), null);
  assert.equal(await check(good.replace('|1794700002|', '|1794700003|')), null);
});

test('ack: signed with kAuth instead of kAck is rejected', async () => {
  const { authKey, check } = await fixture();
  assert.equal(await check(signAck(authKey, 'shown', '1', 1794700002, NONCE)), null);
});

test('ack: a phone to TV line replayed as an ack is rejected', async () => {
  const { check } = await fixture();
  assert.equal(
    await check('RT1|alert|1|1794700000|AAAAAAAAAAAAAAAAAAAAAA|-|rho7lHAItv5gJfxASnPJSEZ-kYDtR4MyRgs3OQFyICE'),
    null,
  );
});

test('ack: signed with the seed itself or another seed is rejected', async () => {
  const { check } = await fixture();
  assert.equal(await check(signAck(hexToBytes(FAKE_SEED_HEX), 'shown', '1', 1794700002, NONCE)), null);
  const otherKey = rawKey('ffffffffffffffffffffffffffffffff', 'rt1/ack');
  assert.equal(await check(signAck(otherKey, 'shown', '1', 1794700002, NONCE)), null);
});

test('ack: unknown ref is rejected even with a valid mac', async () => {
  const { ackKey, check, known } = await fixture();
  const line = signAck(ackKey, 'shown', '1', 1794700002, OTHER_NONCE);
  assert.equal(await check(line), null);
  known.add(OTHER_NONCE);
  assert.equal((await check(line)).status, 'shown');
});

test('ack: isKnownRef must return exactly true', async () => {
  const { kAck, ackKey } = await fixture();
  const line = signAck(ackKey, 'shown', '1', 1794700002, NONCE);
  for (const answer of [1, 'yes', {}, undefined, null]) {
    assert.equal(await verifyAck({ subtle, kAck, slot: '1', line, isKnownRef: () => answer }), null);
  }
  const throwing = () => {
    throw new Error('boom');
  };
  assert.equal(await verifyAck({ subtle, kAck, slot: '1', line, isKnownRef: throwing }), null);
});

test('ack: wrong slot is rejected even with a valid mac', async () => {
  const { ackKey, check } = await fixture();
  const forSlot2 = signAck(ackKey, 'shown', '2', 1794700002, NONCE);
  assert.equal(await check(forSlot2, '1'), null);
  assert.equal((await check(forSlot2, '2')).slot, '2');
  assert.equal((await check(forSlot2, 2)).slot, '2');
});

test('ack: grammar violations are rejected', async () => {
  const { ackKey, check } = await fixture();
  const good = signAck(ackKey, 'shown', '1', 1794700002, NONCE);
  const mac = good.slice(-43);
  const hostile = [
    '',
    ' ',
    'RA1',
    'RA1||||||',
    good + ' ',
    ' ' + good,
    good + '\n',
    good + '|extra',
    good.slice(0, -1),
    good + 'A',
    good.replace('RA1', 'RA2'),
    good.replace('RA1', 'ra1'),
    good.toLowerCase(),
    good.replace('|shown|', '|SHOWN|'),
    good.replace('|shown|', '|ringing|'),
    good.replace('|shown|', '||'),
    good.replace('|1|', '|0|'),
    good.replace('|1|', '|5|'),
    good.replace('|1|', '|01|'),
    good.replace('|1794700002|', '|179470000|'),
    good.replace('|1794700002|', '|17947000020|'),
    good.replace('|1794700002|', '|0794700002|'),
    good.replace('|1794700002|', '|-794700002|'),
    good.replace('|1794700002|', '|1.79470e09|'),
    good.replace(NONCE, NONCE.slice(1)),
    good.replace(NONCE, NONCE + 'A'),
    good.replace(NONCE, 'AAAAAAAAAAA+AAAAAAAAAA'),
    good.replace(NONCE, '-'),
    `RA1|shown|1|1794700002|${NONCE}|${mac.slice(0, 42)}=`,
    `RA1|shown|1|1794700002|${NONCE}|${mac.slice(0, 42)}+`,
    `RA1|shown|1|1794700002|${NONCE}|${mac}=`,
    `RA1|shown|1|1794700002|${NONCE}`,
    good.replaceAll('|', '\t'),
    good.replace('|', '|\u0000'),
    `${good}\r\n${good}`,
    'A'.repeat(201),
    good + 'A'.repeat(1000000),
  ];
  for (const line of hostile) {
    assert.equal(parseAck(line), null, JSON.stringify(line.slice(0, 80)));
    assert.equal(await check(line), null);
  }
  for (const value of [undefined, null, 0, {}, [good], Buffer.from(good)]) {
    assert.equal(parseAck(value), null);
    assert.equal(await check(value), null);
  }
});

test('ack: non-canonical mac encoding is rejected', async () => {
  const { ackKey, check } = await fixture();
  const good = signAck(ackKey, 'shown', '1', 1794700002, NONCE);
  // The last mac character carries 4 data bits and 2 unused bits. Setting an
  // unused bit decodes to the same bytes in a lenient decoder.
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const last = alphabet.indexOf(good.at(-1));
  assert.equal(last % 4, 0);
  const sloppy = good.slice(0, -1) + alphabet[last + 1];
  assert.notEqual(parseAck(sloppy), null, 'still fits the grammar');
  assert.equal(await check(sloppy), null);
});

// ---------- relay event JSON ----------

test('relay event: message is extracted from the real event shape', () => {
  const data =
    '{"id":"27IDLtXhIAhB","time":1794669622,"expires":1794712822,"event":"message","topic":"rt0000000000000000000000000000000000000000","message":"RA1|pong|1|1794700030|abcdefghijklmnopqrstuv|3rxIfEjWYsuspwMDT_TedHC0ob5BzpRQ5iXuVhkYsq4"}';
  assert.equal(
    extractRelayMessage(data),
    'RA1|pong|1|1794700030|abcdefghijklmnopqrstuv|3rxIfEjWYsuspwMDT_TedHC0ob5BzpRQ5iXuVhkYsq4',
  );
});

test('relay event: everything else is ignored', () => {
  const hostile = [
    '',
    'not json',
    '{',
    'null',
    '42',
    '"RA1|pong"',
    '[]',
    '[{"event":"message","message":"x"}]',
    '{}',
    '{"event":"open","topic":"t"}',
    '{"event":"keepalive"}',
    '{"event":"open","message":"x"}',
    '{"message":"x"}',
    '{"event":"message"}',
    '{"event":"message","message":null}',
    '{"event":"message","message":42}',
    '{"event":"message","message":["x"]}',
    '{"event":"message","message":{"toString":"x"}}',
    JSON.stringify({ event: 'message', message: 'A'.repeat(201) }),
    JSON.stringify({ event: 'message', message: 'ok', pad: 'A'.repeat(5000) }),
  ];
  for (const data of hostile) assert.equal(extractRelayMessage(data), null, data.slice(0, 60));
  for (const value of [undefined, null, 7, {}, { event: 'message', message: 'x' }]) {
    assert.equal(extractRelayMessage(value), null);
  }
  assert.equal(extractRelayMessage('{"event":"message","message":""}'), '');
  assert.equal(extractRelayMessage('{"__proto__":{"event":"message","message":"x"}}'), null);
});
