// State machines for the page, as pure reducers.
//
//   callStep(state, event)     -> { state, effects }
//   presenceStep(state, event) -> { state, effects }
//
// Effects are plain objects the caller (app.js) carries out:
//   { type: 'buildAlert' }                   sign a new alert, then send 'alertBuilt'
//   { type: 'buildCancel', ref }             sign a cancel, then send 'cancelBuilt'
//   { type: 'post', line, tag }              POST the line, then send 'postOk' / 'postFailed'
//   { type: 'timer', name, ms }              start (or restart) a named timer, then send 'timer'
//   { type: 'clearTimer', name }
//   { type: 'clearTimers' }                  clear every call timer (CALL_TIMERS)

export const TIMING = Object.freeze({
  postTimeoutMs: 3500, // must stay below retryMs so the first POST has settled
  retryMs: 4000,
  noAnswerMs: 8000,
  settleMs: 60000,
  cancelMs: 6000,
  pingMs: 6000,
  pingMinGapMs: 10000,
  replaySince: '2m',
});

export const CALL_TIMERS = Object.freeze(['retry', 'noAnswer', 'settle', 'cancel']);

export const CALL_TEXT = Object.freeze({
  idle: 'Tap to show a call card on the TV.',
  sending: 'Sending…',
  sent: 'Sent',
  shown: 'TV is showing your call',
  dismissed: 'They pressed OK',
  expired: 'Nobody dismissed it',
  asleep: 'TV is in standby',
  stale: "TV rejected it: check this phone's clock",
  limited: 'TV is ignoring repeated alerts for a bit',
  failed: 'TV could not show the card',
  cancelled: 'Cancelled',
  noAnswer: 'No answer from the TV. It may be off. You can still call.',
  cancelling: 'Cancelling…',
  cancelNoAnswer: 'Cancel sent, but the TV did not confirm.',
  offline: 'No internet',
  relay: 'Could not reach the relay',
  internal: 'Something went wrong on this phone. Try again.',
});

export const PRESENCE_TEXT = Object.freeze({
  unknown: 'Connecting…',
  checking: 'Checking the TV…',
  online: 'TV online',
  standby: 'TV is in standby',
  clock: "TV online. Check this phone's clock",
  silent: 'TV not responding',
  offline: 'No internet',
  relay: 'Could not reach the relay',
});

// Ack statuses that end an alert. "shown" is the only one that does not.
const TERMINAL = new Set([
  'dismissed',
  'expired',
  'cancelled',
  'asleep',
  'stale',
  'limited',
  'failed',
]);

const BUSY = new Set(['building', 'waiting', 'shown', 'cancelling']);

export function initialCall() {
  return {
    phase: 'idle', // idle | building | waiting | shown | cancelling | settled
    key: 'idle', // index into CALL_TEXT
    alertNonce: null,
    alertLine: null,
    cancelNonce: null,
    retried: false,
    alertFailed: false, // the alert POST itself failed and nothing was acked
    final: false, // a terminal ack arrived: later acks are ignored
  };
}

/** The Call button is locked: a send is in flight or the card is up. */
export function isBusy(state) {
  return BUSY.has(state.phase);
}

/** The Cancel button should be offered. */
export function canCancel(state) {
  return (
    state.alertNonce !== null &&
    !state.final &&
    !state.alertFailed &&
    (state.phase === 'waiting' || state.phase === 'shown' || state.phase === 'settled')
  );
}

/** The WhatsApp link should be offered. */
export function showWhatsApp(state) {
  return state.phase !== 'idle' && state.phase !== 'building';
}

/** "good", "warn" or "neutral", for styling only. */
export function callTone(state) {
  switch (state.key) {
    case 'shown':
    case 'dismissed':
      return 'good';
    case 'expired':
    case 'asleep':
    case 'stale':
    case 'limited':
    case 'failed':
    case 'noAnswer':
    case 'cancelNoAnswer':
    case 'offline':
    case 'relay':
    case 'internal':
      return 'warn';
    default:
      return 'neutral';
  }
}

const same = (state) => ({ state, effects: [] });

export function callStep(state, event) {
  switch (event.type) {
    case 'tap': {
      if (isBusy(state)) return same(state); // double tap guard
      return {
        state: { ...initialCall(), phase: 'building', key: 'sending' },
        effects: [{ type: 'clearTimers' }, { type: 'buildAlert' }],
      };
    }

    case 'alertBuilt': {
      if (state.phase !== 'building') return same(state);
      return {
        state: { ...state, phase: 'waiting', alertNonce: event.nonce, alertLine: event.line },
        effects: [
          { type: 'post', line: event.line, tag: 'alert' },
          { type: 'timer', name: 'retry', ms: TIMING.retryMs },
          { type: 'timer', name: 'noAnswer', ms: TIMING.noAnswerMs },
        ],
      };
    }

    case 'buildFailed': {
      if (state.phase !== 'building' && state.phase !== 'cancelling') return same(state);
      return {
        state: { ...state, phase: 'settled', key: 'internal' },
        effects: [{ type: 'clearTimers' }],
      };
    }

    case 'postOk': {
      if (event.tag !== 'cancel' && state.phase === 'waiting' && state.key === 'sending') {
        return same({ ...state, key: 'sent' });
      }
      return same(state);
    }

    case 'postFailed': {
      const key = event.kind === 'offline' ? 'offline' : 'relay';
      if (event.tag === 'alert' && state.phase === 'waiting' && state.key === 'sending') {
        return {
          state: { ...state, phase: 'settled', key, alertFailed: true },
          effects: [{ type: 'clearTimers' }],
        };
      }
      if (event.tag === 'cancel' && state.phase === 'cancelling') {
        return {
          state: { ...state, phase: 'settled', key },
          effects: [{ type: 'clearTimer', name: 'cancel' }],
        };
      }
      return same(state); // a failed retry: the noAnswer timer reports it
    }

    case 'timer': {
      if (event.name === 'retry' && state.phase === 'waiting' && !state.retried) {
        return {
          state: { ...state, retried: true },
          effects: [{ type: 'post', line: state.alertLine, tag: 'retry' }],
        };
      }
      if (event.name === 'noAnswer' && state.phase === 'waiting') {
        return same({ ...state, phase: 'settled', key: 'noAnswer' });
      }
      if (event.name === 'settle' && state.phase === 'shown') {
        // The card has been up for a while: free the Call button, keep listening.
        return same({ ...state, phase: 'settled' });
      }
      if (event.name === 'cancel' && state.phase === 'cancelling') {
        return same({ ...state, phase: 'settled', key: 'cancelNoAnswer' });
      }
      return same(state);
    }

    case 'cancelTap': {
      if (!canCancel(state)) return same(state);
      return {
        state: { ...state, phase: 'cancelling', key: 'cancelling', cancelNonce: null },
        effects: [{ type: 'clearTimers' }, { type: 'buildCancel', ref: state.alertNonce }],
      };
    }

    case 'cancelBuilt': {
      if (state.phase !== 'cancelling' || state.cancelNonce !== null) return same(state);
      return {
        state: { ...state, cancelNonce: event.nonce },
        effects: [
          { type: 'post', line: event.line, tag: 'cancel' },
          { type: 'timer', name: 'cancel', ms: TIMING.cancelMs },
        ],
      };
    }

    case 'ack': {
      if (state.final || state.alertNonce === null) return same(state);
      const forAlert = event.ref === state.alertNonce;
      const forCancel = state.cancelNonce !== null && event.ref === state.cancelNonce;
      if (!forAlert && !forCancel) return same(state);

      if (forAlert && event.status === 'shown') {
        if (state.phase === 'cancelling') return same({ ...state, alertFailed: false });
        return {
          state: { ...state, phase: 'shown', key: 'shown', alertFailed: false },
          effects: [
            { type: 'clearTimers' },
            { type: 'timer', name: 'settle', ms: TIMING.settleMs },
          ],
        };
      }
      const ends = forAlert
        ? TERMINAL.has(event.status)
        : event.status === 'cancelled' || event.status === 'stale';
      if (!ends) return same(state);
      return {
        state: { ...state, phase: 'settled', key: event.status, alertFailed: false, final: true },
        effects: [{ type: 'clearTimers' }],
      };
    }

    case 'reset':
      return { state: initialCall(), effects: [{ type: 'clearTimers' }] };

    default:
      return same(state);
  }
}

export function initialPresence() {
  return { status: 'unknown', nonce: null, pending: false };
}

/** "good", "warn" or "neutral", for styling only. */
export function presenceTone(state) {
  if (state.status === 'online') return 'good';
  if (state.status === 'unknown' || state.status === 'checking') return 'neutral';
  return 'warn';
}

/** A fresh ping is wanted even if one went out very recently. */
export function presenceNeedsPing(state) {
  return (
    state.status === 'unknown' ||
    state.status === 'silent' ||
    state.status === 'offline' ||
    state.status === 'relay'
  );
}

export function presenceStep(state, event) {
  switch (event.type) {
    case 'pingSent':
      return {
        state: {
          status: state.status === 'online' ? 'online' : 'checking',
          nonce: event.nonce,
          pending: true,
        },
        effects: [{ type: 'timer', name: 'ping', ms: TIMING.pingMs }],
      };

    case 'pingFailed':
      return {
        state: { ...state, status: event.kind === 'offline' ? 'offline' : 'relay', pending: false },
        effects: [{ type: 'clearTimer', name: 'ping' }],
      };

    case 'ack': {
      if (!state.pending || event.ref !== state.nonce) return same(state);
      let status = 'online';
      if (event.status === 'asleep') status = 'standby';
      if (event.status === 'stale') status = 'clock';
      return {
        state: { ...state, status, pending: false },
        effects: [{ type: 'clearTimer', name: 'ping' }],
      };
    }

    case 'seen': // a verified ack for an alert or cancel: the TV is clearly there
      return {
        state: { ...state, status: 'online', pending: false },
        effects: [{ type: 'clearTimer', name: 'ping' }],
      };

    case 'timer':
      if (event.name === 'ping' && state.pending) {
        return same({ ...state, status: 'silent', pending: false });
      }
      return same(state);

    case 'offline':
      return {
        state: { ...state, status: 'offline', pending: false },
        effects: [{ type: 'clearTimer', name: 'ping' }],
      };

    case 'relayDown':
      return {
        state: { ...state, status: 'relay', pending: false },
        effects: [{ type: 'clearTimer', name: 'ping' }],
      };

    case 'reset':
      return { state: initialPresence(), effects: [{ type: 'clearTimer', name: 'ping' }] };

    default:
      return same(state);
  }
}
