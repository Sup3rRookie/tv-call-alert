import { test } from 'node:test';
import assert from 'node:assert/strict';

import { RELAY_BASE, publishUrl, streamUrl, publish } from '../lib/relay.js';
import { isPairingRecord } from '../lib/store.js';
import { derivePairing } from '../lib/protocol.js';
import { subtle, hexToBytes } from './helpers.js';

const TOPIC = 'rt0f3736d3e08c1caac5225157b337f2690c9a9a2c';
const LINE = 'RT1|ping|1|1794700030|abcdefghijklmnopqrstuv|-|ZgUygAxQ3kaM0KxJAfUPXZxTcXBQVq3nCt3x5cf9SHc';

function fakeFetch(respond) {
  const calls = [];
  const fetch = async (url, options) => {
    calls.push({ url, options });
    return respond(url, options);
  };
  return { fetch, calls };
}

test('relay: the base URL is the one constant', () => {
  assert.equal(RELAY_BASE, 'https://ntfy.sh');
  assert.equal(publishUrl(TOPIC), `https://ntfy.sh/${TOPIC}`);
  assert.equal(streamUrl(TOPIC), `https://ntfy.sh/${TOPIC}/sse`);
  assert.equal(streamUrl(TOPIC, { since: '2m' }), `https://ntfy.sh/${TOPIC}/sse?since=2m`);
});

test('relay: malformed topics and since values never reach a URL', () => {
  const bad = ['', 'rt', TOPIC + '0', TOPIC.slice(1), TOPIC.toUpperCase(), `${TOPIC}/sse`, `${TOPIC}?x=1`, '../' + TOPIC, 'rt' + 'g'.repeat(40), null, undefined, 5];
  for (const topic of bad) {
    assert.throws(() => publishUrl(topic), RangeError);
    assert.throws(() => streamUrl(topic), RangeError);
  }
  for (const since of ['', '2', 'm', '2m&x=1', '0m', '99999s', 'all', 120, null]) {
    assert.throws(() => streamUrl(TOPIC, { since }), RangeError);
  }
});

test('publish: POSTs the line as the body with no credentials or referrer', async () => {
  const { fetch, calls } = fakeFetch(() => ({ ok: true, status: 200 }));
  const signal = new AbortController().signal;
  assert.deepEqual(await publish({ fetch, topic: TOPIC, line: LINE, signal }), { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, `https://ntfy.sh/${TOPIC}`);
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.body, LINE);
  assert.equal(calls[0].options.credentials, 'omit');
  assert.equal(calls[0].options.referrerPolicy, 'no-referrer');
  assert.equal(calls[0].options.signal, signal);
  assert.equal(calls[0].options.headers, undefined, 'no custom headers: keeps it a simple CORS request');
});

test('publish: offline is reported without touching the network', async () => {
  const { fetch, calls } = fakeFetch(() => ({ ok: true, status: 200 }));
  assert.deepEqual(await publish({ fetch, topic: TOPIC, line: LINE, online: false }), { ok: false, kind: 'offline' });
  assert.equal(calls.length, 0);
});

test('publish: network errors, timeouts and HTTP errors are relay failures', async () => {
  const throwing = fakeFetch(() => {
    throw new TypeError('Failed to fetch');
  });
  assert.deepEqual(await publish({ fetch: throwing.fetch, topic: TOPIC, line: LINE }), { ok: false, kind: 'relay' });

  const aborted = fakeFetch(() => {
    throw new DOMException('timed out', 'TimeoutError');
  });
  assert.deepEqual(await publish({ fetch: aborted.fetch, topic: TOPIC, line: LINE }), { ok: false, kind: 'relay' });

  for (const status of [400, 403, 429, 500, 503]) {
    const { fetch } = fakeFetch(() => ({ ok: false, status }));
    assert.deepEqual(await publish({ fetch, topic: TOPIC, line: LINE }), { ok: false, kind: 'relay', status });
  }
});

test('publish: refuses bad topics and bad lines without calling fetch', async () => {
  const { fetch, calls } = fakeFetch(() => ({ ok: true, status: 200 }));
  for (const args of [
    { topic: 'not-a-topic', line: LINE },
    { topic: TOPIC, line: '' },
    { topic: TOPIC, line: 'A'.repeat(201) },
    { topic: TOPIC, line: null },
  ]) {
    assert.deepEqual(await publish({ fetch, ...args }), { ok: false, kind: 'relay' });
  }
  assert.equal(calls.length, 0);
});

test('store: only complete records with sealed keys pass the shape check', async () => {
  const derived = await derivePairing(hexToBytes('000102030405060708090a0b0c0d0e0f'), subtle);
  const good = { slot: '1', ...derived };
  assert.equal(isPairingRecord(good), true);

  const extractable = await subtle.importKey('raw', new Uint8Array(32), { name: 'HMAC', hash: 'SHA-256' }, true, ['sign']);
  const bad = [
    null,
    undefined,
    'record',
    {},
    { ...good, slot: 1 },
    { ...good, slot: '5' },
    { ...good, topicDown: 'https://example.invalid/x' },
    { ...good, topicAck: good.topicDown },
    { ...good, kAuth: extractable },
    { ...good, kAuth: good.kAck },
    { ...good, kAck: good.kAuth },
    { ...good, kAck: undefined },
    { ...good, kAuth: 'not a key' },
  ];
  for (const record of bad) assert.equal(isPairingRecord(record), false);
});
