import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  base32DecodeSeed,
  parsePairingString,
  derivePairing,
  buildMessage,
  parseAck,
  verifyAck,
} from '../lib/protocol.js';
import { subtle, loadVectors, hexToBytes, groupCode } from './helpers.js';

const vectors = loadVectors();

test('vectors.tsv has rows to check', () => {
  assert.ok(vectors.length >= 6);
});

for (const [index, v] of vectors.entries()) {
  const name = `vector ${index + 1} (${v.type}, slot ${v.slot})`;

  test(`${name}: seed code decodes to the seed bytes`, () => {
    assert.deepEqual(base32DecodeSeed(v.seedCode), hexToBytes(v.seedHex));
  });

  test(`${name}: pairing string parses in every tolerated spelling`, () => {
    const spellings = [
      `${v.slot}-${groupCode(v.seedCode)}`,
      `${v.slot}${v.seedCode}`,
      `${v.slot}-${groupCode(v.seedCode)}`.toLowerCase(),
      `  ${v.slot} ${groupCode(v.seedCode).replaceAll('-', ' ')}  `,
      `${v.slot}-${v.seedCode.toLowerCase()}\n`,
    ];
    for (const spelling of spellings) {
      const { slot, seed } = parsePairingString(spelling);
      assert.equal(slot, v.slot);
      assert.deepEqual(seed, hexToBytes(v.seedHex));
    }
  });

  test(`${name}: derived topics match`, async () => {
    const derived = await derivePairing(hexToBytes(v.seedHex), subtle);
    assert.equal(derived.topicDown, v.topicDown);
    assert.equal(derived.topicAck, v.topicAck);
  });

  test(`${name}: message line matches byte for byte`, async () => {
    const { kAuth } = await derivePairing(hexToBytes(v.seedHex), subtle);
    const line = await buildMessage({
      subtle,
      kAuth,
      type: v.type,
      slot: v.slot,
      ts: Number(v.ts),
      nonce: v.nonce,
      ref: v.ref,
    });
    assert.equal(line, v.message);
    assert.ok(line.length <= 200);
  });

  test(`${name}: ack verifies with kAck`, async () => {
    const { kAck } = await derivePairing(hexToBytes(v.seedHex), subtle);
    const parsed = parseAck(v.ack);
    assert.equal(parsed.status, v.ackStatus);
    assert.equal(parsed.ts, Number(v.ackTs));
    assert.equal(parsed.ref, v.nonce);
    assert.equal(`${parsed.body}|${parsed.mac}`, v.ack);

    const ack = await verifyAck({
      subtle,
      kAck,
      slot: v.slot,
      line: v.ack,
      isKnownRef: (ref) => ref === v.nonce,
    });
    assert.deepEqual(ack, {
      status: v.ackStatus,
      slot: v.slot,
      ts: Number(v.ackTs),
      ref: v.nonce,
    });
  });
}
