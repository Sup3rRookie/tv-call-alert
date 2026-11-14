import { test } from 'node:test';
import assert from 'node:assert/strict';

import { nextTs, isTs, derivePairing, buildMessage } from '../lib/protocol.js';
import { createTsClock } from '../lib/tsstore.js';
import { callStep, initialCall } from '../lib/flow.js';
import { subtle, hexToBytes } from './helpers.js';

const T = 1794700000;
const ms = (seconds, extra = 0) => seconds * 1000 + extra;

function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
  };
}

test('nextTs: first message uses the clock', () => {
  assert.equal(nextTs(ms(T, 999), null), T);
  assert.equal(nextTs(ms(T), undefined), T);
});

test('nextTs: ping then alert in the same second are strictly increasing', () => {
  const ping = nextTs(ms(T, 100), null);
  const alert = nextTs(ms(T, 400), ping);
  assert.equal(ping, T);
  assert.equal(alert, T + 1);
});

test('nextTs: alert then cancel in the same second are strictly increasing', () => {
  const alert = nextTs(ms(T, 10), T - 50);
  const cancel = nextTs(ms(T, 900), alert);
  assert.equal(alert, T);
  assert.equal(cancel, T + 1);
  // A burst stays strictly increasing, then rejoins the clock.
  let last = cancel;
  for (let i = 0; i < 5; i += 1) {
    const next = nextTs(ms(T, 950), last);
    assert.equal(next, last + 1);
    last = next;
  }
  assert.equal(nextTs(ms(T + 60), last), T + 60);
});

test('nextTs: clock stepping backwards never goes below the last ts', () => {
  assert.equal(nextTs(ms(T - 3600), T), T + 1);
  assert.equal(nextTs(ms(T - 1), T), T + 1);
  assert.equal(nextTs(ms(T), T), T + 1);
  assert.equal(nextTs(ms(T + 1), T), T + 1);
  assert.equal(nextTs(ms(T + 2), T), T + 2);
});

test('nextTs: garbage lastTs is ignored, out of range results are refused', () => {
  for (const junk of ['1794700000', 12.5, Number.NaN, Infinity, -1, 0, 999999999, 10000000000, {}, []]) {
    assert.equal(nextTs(ms(T), junk), T);
  }
  assert.throws(() => nextTs(ms(T), 9999999999), RangeError);
  assert.throws(() => nextTs(0, null), RangeError);
  assert.equal(isTs(T), true);
  assert.equal(isTs(String(T)), false);
});

test('ts clock: reserve is strictly increasing and persists the last ts', () => {
  const storage = fakeStorage();
  const clock = createTsClock(() => storage);
  assert.equal(clock.reserve(ms(T)), T); // ping
  assert.equal(clock.reserve(ms(T, 300)), T + 1); // alert, same second
  assert.equal(clock.reserve(ms(T, 600)), T + 2); // cancel, same second
  assert.equal(storage.data.get('rt1.lastTs'), String(T + 2));
  assert.equal(storage.data.size, 1, 'nothing else is ever stored');
});

test('ts clock: survives a reload', () => {
  const storage = fakeStorage();
  createTsClock(() => storage).reserve(ms(T + 5));
  const afterReload = createTsClock(() => storage);
  assert.equal(afterReload.reserve(ms(T + 5, 500)), T + 6);
  assert.equal(afterReload.reserve(ms(T - 100)), T + 7, 'clock stepped back after the reload');
});

test('ts clock: sees a newer ts written by another tab', () => {
  const storage = fakeStorage();
  const a = createTsClock(() => storage);
  const b = createTsClock(() => storage);
  assert.equal(a.reserve(ms(T)), T);
  assert.equal(b.reserve(ms(T)), T + 1);
  assert.equal(a.reserve(ms(T)), T + 2);
});

test('ts clock: corrupt or hostile stored values are ignored', () => {
  const values = ['', 'abc', '-5', '1e12', '17947000001', '0794700000', ' 1794700000', '1794700000.5', '9'.repeat(400)];
  for (const value of values) {
    const storage = fakeStorage({ 'rt1.lastTs': value });
    assert.equal(createTsClock(() => storage).reserve(ms(T)), T, JSON.stringify(value.slice(0, 20)));
  }
});

test('ts clock: blocked storage still keeps one session monotonic', () => {
  const throwing = () => {
    throw new DOMException('denied', 'SecurityError');
  };
  const clock = createTsClock(throwing);
  assert.equal(clock.reserve(ms(T)), T);
  assert.equal(clock.reserve(ms(T)), T + 1);

  const halfBroken = { getItem: () => null, setItem: throwing };
  const clock2 = createTsClock(() => halfBroken);
  assert.equal(clock2.reserve(ms(T)), T);
  assert.equal(clock2.reserve(ms(T - 10)), T + 1);
});

test('retry re-sends the identical line: no new ts, no new signature', async () => {
  const { kAuth } = await derivePairing(hexToBytes('000102030405060708090a0b0c0d0e0f'), subtle);
  const clock = createTsClock(() => fakeStorage());
  const nonce = 'AAAAAAAAAAAAAAAAAAAAAA';
  const line = await buildMessage({ subtle, kAuth, type: 'alert', slot: '1', ts: clock.reserve(ms(T)), nonce });

  let state = initialCall();
  const posted = [];
  let builds = 0;
  for (const event of [
    { type: 'tap' },
    { type: 'alertBuilt', line, nonce },
    { type: 'postOk', tag: 'alert' },
    { type: 'timer', name: 'retry' },
  ]) {
    const result = callStep(state, event);
    state = result.state;
    posted.push(...result.effects.filter((e) => e.type === 'post'));
    builds += result.effects.filter((e) => e.type === 'buildAlert').length;
  }
  assert.equal(builds, 1, 'the retry does not sign anything new');
  assert.equal(posted.length, 2);
  assert.equal(posted[0].tag, 'alert');
  assert.equal(posted[1].tag, 'retry');
  assert.equal(posted[1].line, posted[0].line);
  assert.equal(posted[1].line, line);
});
