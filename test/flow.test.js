import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  TIMING,
  CALL_TEXT,
  PRESENCE_TEXT,
  initialCall,
  callStep,
  isBusy,
  canCancel,
  showWhatsApp,
  callTone,
  initialPresence,
  presenceStep,
  presenceTone,
  presenceNeedsPing,
} from '../lib/flow.js';

const ALERT = 'AAAAAAAAAAAAAAAAAAAAAA';
const CANCEL = 'BBBBBBBBBBBBBBBBBBBBBB';
const OTHER = 'CCCCCCCCCCCCCCCCCCCCCC';
const LINE = 'RT1|alert|1|1794700000|AAAAAAAAAAAAAAAAAAAAAA|-|fake-mac';
const CANCEL_LINE = 'RT1|cancel|1|1794700020|BBBBBBBBBBBBBBBBBBBBBB|AAAAAAAAAAAAAAAAAAAAAA|fake-mac';

/** Feed events through the reducer, collecting every effect. */
function run(events, start = initialCall(), step = callStep) {
  let state = start;
  const effects = [];
  for (const event of events) {
    const result = step(state, event);
    state = result.state;
    effects.push(...result.effects);
  }
  return { state, effects };
}

const sent = () =>
  run([{ type: 'tap' }, { type: 'alertBuilt', line: LINE, nonce: ALERT }, { type: 'postOk', tag: 'alert' }]);

const posts = (effects) => effects.filter((e) => e.type === 'post');

test('texts: every status from the brief is worded exactly', () => {
  assert.equal(CALL_TEXT.sent, 'Sent');
  assert.equal(CALL_TEXT.shown, 'TV is showing your call');
  assert.equal(CALL_TEXT.dismissed, 'They pressed OK');
  assert.equal(CALL_TEXT.expired, 'Nobody dismissed it');
  assert.equal(CALL_TEXT.asleep, 'TV is in standby');
  assert.equal(CALL_TEXT.stale, "TV rejected it: check this phone's clock");
  assert.equal(CALL_TEXT.limited, 'TV is ignoring repeated alerts for a bit');
  assert.equal(CALL_TEXT.failed, 'TV could not show the card');
  assert.equal(CALL_TEXT.cancelled, 'Cancelled');
  assert.equal(CALL_TEXT.noAnswer, 'No answer from the TV. It may be off. You can still call.');
  assert.equal(CALL_TEXT.offline, 'No internet');
  assert.equal(CALL_TEXT.relay, 'Could not reach the relay');
  assert.equal(PRESENCE_TEXT.online, 'TV online');
  assert.equal(PRESENCE_TEXT.silent, 'TV not responding');
});

test('timing: retry about 4 s, give up about 8 s, ping about 6 s', () => {
  assert.equal(TIMING.retryMs, 4000);
  assert.equal(TIMING.noAnswerMs, 8000);
  assert.equal(TIMING.pingMs, 6000);
  assert.ok(TIMING.postTimeoutMs < TIMING.retryMs);
});

test('call: idle state offers only the Call button', () => {
  const state = initialCall();
  assert.equal(isBusy(state), false);
  assert.equal(canCancel(state), false);
  assert.equal(showWhatsApp(state), false);
  assert.equal(CALL_TEXT[state.key], CALL_TEXT.idle);
});

test('call: tap builds, posts once and arms both timers', () => {
  const tapped = run([{ type: 'tap' }]);
  assert.equal(tapped.state.phase, 'building');
  assert.deepEqual(tapped.effects, [{ type: 'clearTimers' }, { type: 'buildAlert' }]);

  const { state, effects } = sent();
  assert.equal(state.phase, 'waiting');
  assert.equal(state.key, 'sent');
  assert.equal(state.alertNonce, ALERT);
  assert.deepEqual(posts(effects), [{ type: 'post', line: LINE, tag: 'alert' }]);
  assert.ok(effects.some((e) => e.type === 'timer' && e.name === 'retry' && e.ms === 4000));
  assert.ok(effects.some((e) => e.type === 'timer' && e.name === 'noAnswer' && e.ms === 8000));
  assert.equal(isBusy(state), true);
  assert.equal(canCancel(state), true);
  assert.equal(showWhatsApp(state), true);
});

test('call: double taps are ignored while a call is in flight', () => {
  const double = run([{ type: 'tap' }, { type: 'tap' }, { type: 'tap' }]);
  assert.equal(double.effects.filter((e) => e.type === 'buildAlert').length, 1);

  const { state } = sent();
  for (const phaseState of [
    state,
    run([{ type: 'ack', status: 'shown', ref: ALERT }], state).state,
    run([{ type: 'cancelTap' }], state).state,
  ]) {
    const again = callStep(phaseState, { type: 'tap' });
    assert.equal(again.state, phaseState);
    assert.deepEqual(again.effects, []);
  }
});

test('call: a second alertBuilt cannot post a second alert', () => {
  const { state } = sent();
  const again = callStep(state, { type: 'alertBuilt', line: 'other', nonce: OTHER });
  assert.equal(again.state, state);
  assert.deepEqual(again.effects, []);
});

test('call: retry re-sends the identical line exactly once', () => {
  const { state } = sent();
  const first = callStep(state, { type: 'timer', name: 'retry' });
  assert.deepEqual(first.effects, [{ type: 'post', line: LINE, tag: 'retry' }]);
  const second = callStep(first.state, { type: 'timer', name: 'retry' });
  assert.deepEqual(second.effects, []);
});

test('call: no retry once an ack has arrived', () => {
  const shown = run([{ type: 'ack', status: 'shown', ref: ALERT }], sent().state);
  assert.ok(shown.effects.some((e) => e.type === 'clearTimers'));
  const late = callStep(shown.state, { type: 'timer', name: 'retry' });
  assert.deepEqual(late.effects, []);
});

test('call: about 8 s of silence ends in the no answer message', () => {
  const { state } = run(
    [{ type: 'timer', name: 'retry' }, { type: 'postFailed', tag: 'retry', kind: 'relay' }, { type: 'timer', name: 'noAnswer' }],
    sent().state,
  );
  assert.equal(state.phase, 'settled');
  assert.equal(CALL_TEXT[state.key], 'No answer from the TV. It may be off. You can still call.');
  assert.equal(isBusy(state), false);
  assert.equal(showWhatsApp(state), true);
  assert.equal(canCancel(state), true);
  assert.equal(callTone(state), 'warn');
});

test('call: a late ack after the no answer message still counts', () => {
  const silent = run([{ type: 'timer', name: 'noAnswer' }], sent().state).state;
  const shown = callStep(silent, { type: 'ack', status: 'shown', ref: ALERT }).state;
  assert.equal(shown.key, 'shown');
  const done = callStep(shown, { type: 'ack', status: 'dismissed', ref: ALERT }).state;
  assert.equal(CALL_TEXT[done.key], 'They pressed OK');
});

test('call: each ack status maps to its message', () => {
  const expected = {
    shown: 'TV is showing your call',
    dismissed: 'They pressed OK',
    expired: 'Nobody dismissed it',
    asleep: 'TV is in standby',
    stale: "TV rejected it: check this phone's clock",
    limited: 'TV is ignoring repeated alerts for a bit',
    failed: 'TV could not show the card',
    cancelled: 'Cancelled',
  };
  for (const [status, text] of Object.entries(expected)) {
    const { state, effects } = run([{ type: 'ack', status, ref: ALERT }], sent().state);
    assert.equal(CALL_TEXT[state.key], text);
    assert.ok(effects.some((e) => e.type === 'clearTimers'));
    if (status === 'shown') {
      assert.equal(state.phase, 'shown');
      assert.equal(state.final, false);
      assert.equal(isBusy(state), true);
      assert.equal(canCancel(state), true);
    } else {
      assert.equal(state.phase, 'settled');
      assert.equal(state.final, true);
      assert.equal(isBusy(state), false, 'a new call is allowed');
      assert.equal(canCancel(state), false);
      assert.equal(showWhatsApp(state), true);
    }
  }
});

test('call: shown then dismissed, and nothing after a terminal ack', () => {
  const shown = run([{ type: 'ack', status: 'shown', ref: ALERT }], sent().state).state;
  const dismissed = callStep(shown, { type: 'ack', status: 'dismissed', ref: ALERT }).state;
  assert.equal(dismissed.key, 'dismissed');
  for (const status of ['shown', 'expired', 'cancelled', 'failed']) {
    const replay = callStep(dismissed, { type: 'ack', status, ref: ALERT });
    assert.equal(replay.state, dismissed);
    assert.deepEqual(replay.effects, []);
  }
});

test('call: acks for other refs, and pong for an alert, change nothing', () => {
  const { state } = sent();
  for (const event of [
    { type: 'ack', status: 'shown', ref: OTHER },
    { type: 'ack', status: 'dismissed', ref: CANCEL },
    { type: 'ack', status: 'pong', ref: ALERT },
    { type: 'ack', status: 'nonsense', ref: ALERT },
  ]) {
    const result = callStep(state, event);
    assert.equal(result.state, state);
    assert.deepEqual(result.effects, []);
  }
  const idle = callStep(initialCall(), { type: 'ack', status: 'shown', ref: ALERT });
  assert.equal(idle.state.phase, 'idle');
});

test('call: the card staying up for a minute frees the button but keeps listening', () => {
  const shown = run([{ type: 'ack', status: 'shown', ref: ALERT }], sent().state);
  assert.ok(shown.effects.some((e) => e.type === 'timer' && e.name === 'settle'));
  const settled = callStep(shown.state, { type: 'timer', name: 'settle' }).state;
  assert.equal(isBusy(settled), false);
  assert.equal(settled.key, 'shown');
  const expired = callStep(settled, { type: 'ack', status: 'expired', ref: ALERT }).state;
  assert.equal(expired.key, 'expired');
});

test('call: offline and relay failures on the first POST are reported and unlock the button', () => {
  for (const [kind, text] of [
    ['offline', 'No internet'],
    ['relay', 'Could not reach the relay'],
  ]) {
    const { state, effects } = run([
      { type: 'tap' },
      { type: 'alertBuilt', line: LINE, nonce: ALERT },
      { type: 'postFailed', tag: 'alert', kind },
    ]);
    assert.equal(CALL_TEXT[state.key], text);
    assert.equal(isBusy(state), false);
    assert.equal(canCancel(state), false, 'nothing to cancel');
    assert.equal(showWhatsApp(state), true);
    assert.ok(effects.some((e) => e.type === 'clearTimers'));
    // No retry or timeout may fire afterwards.
    assert.deepEqual(callStep(state, { type: 'timer', name: 'retry' }).effects, []);
    assert.equal(callStep(state, { type: 'timer', name: 'noAnswer' }).state, state);
    // Tapping again starts a fresh alert.
    assert.equal(callStep(state, { type: 'tap' }).state.phase, 'building');
  }
});

test('call: an ack that arrives despite a reported POST failure is honoured', () => {
  const failed = run([
    { type: 'tap' },
    { type: 'alertBuilt', line: LINE, nonce: ALERT },
    { type: 'postFailed', tag: 'alert', kind: 'relay' },
  ]).state;
  const shown = callStep(failed, { type: 'ack', status: 'shown', ref: ALERT }).state;
  assert.equal(shown.key, 'shown');
  assert.equal(canCancel(shown), true);
});

test('call: signing failure is reported and recoverable', () => {
  const { state } = run([{ type: 'tap' }, { type: 'buildFailed' }]);
  assert.equal(state.key, 'internal');
  assert.equal(isBusy(state), false);
});

test('cancel: sends a cancel that references the alert nonce', () => {
  const tapped = run([{ type: 'cancelTap' }], sent().state);
  assert.equal(tapped.state.phase, 'cancelling');
  assert.deepEqual(
    tapped.effects.filter((e) => e.type === 'buildCancel'),
    [{ type: 'buildCancel', ref: ALERT }],
  );
  assert.ok(tapped.effects.some((e) => e.type === 'clearTimers'), 'no alert retry after a cancel');

  const built = run([{ type: 'cancelBuilt', line: CANCEL_LINE, nonce: CANCEL }], tapped.state);
  assert.deepEqual(posts(built.effects), [{ type: 'post', line: CANCEL_LINE, tag: 'cancel' }]);
  assert.equal(canCancel(built.state), false, 'no double cancel');
  assert.deepEqual(callStep(built.state, { type: 'cancelTap' }).effects, []);
  assert.deepEqual(callStep(built.state, { type: 'cancelBuilt', line: 'x', nonce: OTHER }).effects, []);
  assert.deepEqual(callStep(built.state, { type: 'timer', name: 'retry' }).effects, []);

  const done = callStep(built.state, { type: 'ack', status: 'cancelled', ref: CANCEL }).state;
  assert.equal(CALL_TEXT[done.key], 'Cancelled');
  assert.equal(done.final, true);
  assert.equal(isBusy(done), false);
});

test('cancel: the cancelled ack may reference the alert nonce instead', () => {
  const state = run(
    [{ type: 'cancelTap' }, { type: 'cancelBuilt', line: CANCEL_LINE, nonce: CANCEL }, { type: 'ack', status: 'cancelled', ref: ALERT }],
    sent().state,
  ).state;
  assert.equal(state.key, 'cancelled');
});

test('cancel: a shown ack does not undo an in-flight cancel', () => {
  const cancelling = run([{ type: 'cancelTap' }, { type: 'cancelBuilt', line: CANCEL_LINE, nonce: CANCEL }], sent().state).state;
  const after = callStep(cancelling, { type: 'ack', status: 'shown', ref: ALERT }).state;
  assert.equal(after.phase, 'cancelling');
  assert.equal(after.key, 'cancelling');
});

test('cancel: only cancelled or stale end the flow through the cancel nonce', () => {
  const cancelling = run([{ type: 'cancelTap' }, { type: 'cancelBuilt', line: CANCEL_LINE, nonce: CANCEL }], sent().state).state;
  assert.equal(callStep(cancelling, { type: 'ack', status: 'shown', ref: CANCEL }).state, cancelling);
  assert.equal(callStep(cancelling, { type: 'ack', status: 'pong', ref: CANCEL }).state, cancelling);
  assert.equal(callStep(cancelling, { type: 'ack', status: 'stale', ref: CANCEL }).state.key, 'stale');
});

test('cancel: no confirmation and send failures are reported, and cancel can be tried again', () => {
  const cancelling = run([{ type: 'cancelTap' }, { type: 'cancelBuilt', line: CANCEL_LINE, nonce: CANCEL }], sent().state).state;

  const silent = callStep(cancelling, { type: 'timer', name: 'cancel' }).state;
  assert.equal(silent.key, 'cancelNoAnswer');
  assert.equal(canCancel(silent), true);
  assert.equal(callStep(silent, { type: 'ack', status: 'cancelled', ref: CANCEL }).state.key, 'cancelled');

  const failed = callStep(cancelling, { type: 'postFailed', tag: 'cancel', kind: 'offline' }).state;
  assert.equal(CALL_TEXT[failed.key], 'No internet');
  assert.equal(canCancel(failed), true);
  const again = callStep(failed, { type: 'cancelTap' });
  assert.deepEqual(again.effects.filter((e) => e.type === 'buildCancel'), [{ type: 'buildCancel', ref: ALERT }]);
});

test('cancel: not offered before anything was sent', () => {
  assert.deepEqual(callStep(initialCall(), { type: 'cancelTap' }).effects, []);
  const building = callStep(initialCall(), { type: 'tap' }).state;
  assert.deepEqual(callStep(building, { type: 'cancelTap' }).effects, []);
});

test('call: a new tap after a finished call starts clean', () => {
  const done = run([{ type: 'ack', status: 'dismissed', ref: ALERT }], sent().state).state;
  const next = run([{ type: 'tap' }, { type: 'alertBuilt', line: 'line-2', nonce: OTHER }], done).state;
  assert.equal(next.alertNonce, OTHER);
  assert.equal(next.final, false);
  assert.equal(next.retried, false);
  assert.equal(next.cancelNonce, null);
  // Acks for the previous alert no longer matter.
  assert.equal(callStep(next, { type: 'ack', status: 'expired', ref: ALERT }).state, next);
});

test('call: reset and unknown events', () => {
  const reset = callStep(sent().state, { type: 'reset' });
  assert.deepEqual(reset.state, initialCall());
  assert.deepEqual(reset.effects, [{ type: 'clearTimers' }]);
  const state = sent().state;
  assert.equal(callStep(state, { type: 'mystery' }).state, state);
});

test('reducers never mutate the state they are given', () => {
  const state = Object.freeze(sent().state);
  for (const event of [
    { type: 'tap' },
    { type: 'postOk', tag: 'alert' },
    { type: 'postFailed', tag: 'alert', kind: 'relay' },
    { type: 'timer', name: 'retry' },
    { type: 'timer', name: 'noAnswer' },
    { type: 'cancelTap' },
    { type: 'ack', status: 'shown', ref: ALERT },
    { type: 'ack', status: 'dismissed', ref: ALERT },
    { type: 'reset' },
  ]) {
    assert.doesNotThrow(() => callStep(state, event));
  }
  const p = Object.freeze(initialPresence());
  for (const event of [{ type: 'pingSent', nonce: ALERT }, { type: 'offline' }, { type: 'seen' }, { type: 'reset' }]) {
    assert.doesNotThrow(() => presenceStep(p, event));
  }
});

// ---------- presence ----------

const pinged = () => presenceStep(initialPresence(), { type: 'pingSent', nonce: ALERT });

test('presence: ping then pong shows TV online', () => {
  const sentPing = pinged();
  assert.equal(sentPing.state.status, 'checking');
  assert.deepEqual(sentPing.effects, [{ type: 'timer', name: 'ping', ms: 6000 }]);
  const online = presenceStep(sentPing.state, { type: 'ack', status: 'pong', ref: ALERT });
  assert.equal(PRESENCE_TEXT[online.state.status], 'TV online');
  assert.equal(presenceTone(online.state), 'good');
  assert.deepEqual(online.effects, [{ type: 'clearTimer', name: 'ping' }]);
  // The timer firing late changes nothing.
  assert.equal(presenceStep(online.state, { type: 'timer', name: 'ping' }).state, online.state);
});

test('presence: about 6 s without a pong shows TV not responding', () => {
  const silent = presenceStep(pinged().state, { type: 'timer', name: 'ping' }).state;
  assert.equal(PRESENCE_TEXT[silent.status], 'TV not responding');
  assert.equal(presenceTone(silent), 'warn');
  assert.equal(presenceNeedsPing(silent), true);
});

test('presence: a pong for another ping is ignored', () => {
  const state = pinged().state;
  assert.equal(presenceStep(state, { type: 'ack', status: 'pong', ref: OTHER }).state, state);
  const online = presenceStep(state, { type: 'ack', status: 'pong', ref: ALERT }).state;
  assert.equal(presenceStep(online, { type: 'ack', status: 'stale', ref: ALERT }).state, online);
});

test('presence: standby and clock answers get their own wording', () => {
  assert.equal(presenceStep(pinged().state, { type: 'ack', status: 'asleep', ref: ALERT }).state.status, 'standby');
  const clock = presenceStep(pinged().state, { type: 'ack', status: 'stale', ref: ALERT }).state;
  assert.match(PRESENCE_TEXT[clock.status], /clock/);
});

test('presence: offline and relay errors are shown', () => {
  assert.equal(PRESENCE_TEXT[presenceStep(pinged().state, { type: 'pingFailed', kind: 'offline' }).state.status], 'No internet');
  assert.equal(
    PRESENCE_TEXT[presenceStep(pinged().state, { type: 'pingFailed', kind: 'relay' }).state.status],
    'Could not reach the relay',
  );
  assert.equal(presenceStep(initialPresence(), { type: 'offline' }).state.status, 'offline');
  assert.equal(presenceStep(initialPresence(), { type: 'relayDown' }).state.status, 'relay');
});

test('presence: a verified alert ack proves the TV is online', () => {
  const silent = presenceStep(pinged().state, { type: 'timer', name: 'ping' }).state;
  assert.equal(presenceStep(silent, { type: 'seen' }).state.status, 'online');
});

test('presence: re-pinging while online does not flicker back to checking', () => {
  const online = presenceStep(pinged().state, { type: 'ack', status: 'pong', ref: ALERT }).state;
  assert.equal(presenceNeedsPing(online), false);
  const again = presenceStep(online, { type: 'pingSent', nonce: OTHER }).state;
  assert.equal(again.status, 'online');
  assert.equal(presenceStep(again, { type: 'timer', name: 'ping' }).state.status, 'silent');
});
