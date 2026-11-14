// DOM and timer wiring only. All protocol logic lives in ./lib/.
// Nothing here logs, and nothing secret is ever put in the URL, the DOM
// (beyond the input the user types into) or web storage.

import {
  parsePairingString,
  derivePairing,
  buildMessage,
  makeNonce,
  verifyAck,
  extractRelayMessage,
} from './lib/protocol.js';
import { publish, streamUrl } from './lib/relay.js';
import {
  TIMING,
  CALL_TIMERS,
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
} from './lib/flow.js';
import { loadPairing, savePairing, clearPairing } from './lib/store.js';
import { createTsClock } from './lib/tsstore.js';

const MAX_TRACKED = 64;
const RECONNECT_MIN_MS = 1500;
const RECONNECT_MAX_MS = 15000;

const subtle = globalThis.crypto.subtle;
const tsClock = createTsClock();
const el = (id) => document.getElementById(id);

const ui = {
  viewPair: el('view-pair'),
  viewMain: el('view-main'),
  pairForm: el('pair-form'),
  pairCode: el('pair-code'),
  pairError: el('pair-error'),
  pairButton: el('pair-button'),
  presence: el('presence'),
  presenceText: el('presence-text'),
  callButton: el('call-button'),
  status: el('status'),
  whatsapp: el('whatsapp-link'),
  cancelButton: el('cancel-button'),
  slotLabel: el('slot-label'),
  unpairButton: el('unpair-button'),
  unpairDialog: el('unpair-dialog'),
  unpairCancel: el('unpair-cancel'),
  unpairConfirm: el('unpair-confirm'),
};

let pairing = null; // { slot, topicDown, topicAck, kAuth, kAck }
let generation = 0; // bumped on pair and unpair so stale async work is dropped
let call = initialCall();
let presence = initialPresence();
let stream = null;
let reconnectTimer = null;
let reconnectDelay = RECONNECT_MIN_MS;
let lastPingAt = 0;
let ackQueue = Promise.resolve();
const timers = new Map();
const sentNonces = new Map(); // nonce -> 'ping' | 'alert' | 'cancel', this session only
const seenAcks = new Set(); // verified ack lines already applied

// ---------- rendering ----------

function setText(node, text) {
  if (node.textContent !== text) node.textContent = text;
}

function render() {
  const busy = isBusy(call);
  ui.callButton.disabled = busy;
  ui.callButton.dataset.busy = String(call.phase === 'waiting' || call.phase === 'cancelling');
  setText(ui.status, CALL_TEXT[call.key] ?? '');
  ui.status.dataset.tone = callTone(call);

  const cancel = canCancel(call);
  ui.cancelButton.disabled = !cancel;
  ui.cancelButton.classList.toggle('is-hidden', !cancel);
  ui.whatsapp.classList.toggle('is-hidden', !showWhatsApp(call));

  setText(ui.presenceText, PRESENCE_TEXT[presence.status] ?? '');
  ui.presence.dataset.tone = presenceTone(presence);
}

function showView(name) {
  ui.viewPair.hidden = name !== 'pair';
  ui.viewMain.hidden = name !== 'main';
}

// ---------- timers and effects ----------

function clearTimer(name) {
  const id = timers.get(name);
  if (id !== undefined) {
    clearTimeout(id);
    timers.delete(name);
  }
}

function startTimer(name, ms, onFire) {
  clearTimer(name);
  timers.set(
    name,
    setTimeout(() => {
      timers.delete(name);
      onFire();
    }, ms),
  );
}

function remember(map, key, value) {
  map.set(key, value);
  if (map.size > MAX_TRACKED) map.delete(map.keys().next().value);
}

function dispatchCall(event) {
  const result = callStep(call, event);
  call = result.state;
  runEffects(result.effects, dispatchCall);
  render();
}

function dispatchPresence(event) {
  const result = presenceStep(presence, event);
  presence = result.state;
  runEffects(result.effects, dispatchPresence);
  render();
}

function runEffects(effects, dispatch) {
  for (const effect of effects) {
    switch (effect.type) {
      case 'timer':
        startTimer(effect.name, effect.ms, () => dispatch({ type: 'timer', name: effect.name }));
        break;
      case 'clearTimer':
        clearTimer(effect.name);
        break;
      case 'clearTimers':
        CALL_TIMERS.forEach(clearTimer);
        break;
      case 'buildAlert':
        buildAndDispatch('alert', '-', 'alertBuilt');
        break;
      case 'buildCancel':
        buildAndDispatch('cancel', effect.ref, 'cancelBuilt');
        break;
      case 'post':
        post(effect.line, effect.tag);
        break;
      default:
        break;
    }
  }
}

// ---------- sending ----------

async function signLine(type, ref) {
  // Reserved synchronously, before any await, so every new line gets a ts
  // strictly above the previous one. Retries reuse the stored line instead.
  const ts = tsClock.reserve(Date.now());
  const nonce = makeNonce(globalThis.crypto.getRandomValues(new Uint8Array(16)));
  const line = await buildMessage({
    subtle,
    kAuth: pairing.kAuth,
    type,
    slot: pairing.slot,
    ts,
    nonce,
    ref,
  });
  return { line, nonce };
}

async function buildAndDispatch(type, ref, doneEvent) {
  const mine = generation;
  try {
    const { line, nonce } = await signLine(type, ref);
    if (mine !== generation) return;
    remember(sentNonces, nonce, type);
    dispatchCall({ type: doneEvent, line, nonce });
  } catch {
    if (mine === generation) dispatchCall({ type: 'buildFailed' });
  }
}

function timeoutSignal(ms) {
  if (typeof AbortSignal.timeout === 'function') return AbortSignal.timeout(ms);
  const controller = new AbortController();
  setTimeout(() => controller.abort(), ms);
  return controller.signal;
}

function send(line) {
  return publish({
    fetch: (url, options) => globalThis.fetch(url, options),
    topic: pairing.topicDown,
    line,
    online: navigator.onLine,
    signal: timeoutSignal(TIMING.postTimeoutMs),
  });
}

async function post(line, tag) {
  const mine = generation;
  if (stream === null) connect(); // make sure we are listening for the ack
  const result = await send(line);
  if (mine !== generation) return;
  if (result.ok) dispatchCall({ type: 'postOk', tag });
  else dispatchCall({ type: 'postFailed', tag, kind: result.kind });
}

async function sendPing() {
  if (pairing === null) return;
  const now = Date.now();
  if (!presenceNeedsPing(presence) && now - lastPingAt < TIMING.pingMinGapMs) return;
  lastPingAt = now;
  const mine = generation;
  try {
    const { line, nonce } = await signLine('ping', '-');
    if (mine !== generation) return;
    remember(sentNonces, nonce, 'ping');
    dispatchPresence({ type: 'pingSent', nonce });
    const result = await send(line);
    if (mine !== generation) return;
    if (!result.ok) dispatchPresence({ type: 'pingFailed', kind: result.kind });
  } catch {
    if (mine === generation) dispatchPresence({ type: 'pingFailed', kind: 'relay' });
  }
}

// ---------- ack stream ----------

function closeStream() {
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (stream !== null) {
    stream.close();
    stream = null;
  }
}

function scheduleReconnect() {
  if (reconnectTimer !== null || pairing === null || document.visibilityState !== 'visible') return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
}

function connect() {
  closeStream();
  if (pairing === null) return;
  if (!navigator.onLine) {
    dispatchPresence({ type: 'offline' });
    return; // the "online" event reconnects
  }
  let source;
  try {
    // Replay recent acks when this session is waiting on any: it covers the
    // gap while iOS had the page suspended.
    const since = sentNonces.size > 0 ? TIMING.replaySince : undefined;
    source = new EventSource(streamUrl(pairing.topicAck, { since }));
  } catch {
    dispatchPresence({ type: 'relayDown' });
    return;
  }
  stream = source;
  source.onopen = () => {
    if (stream !== source) return;
    reconnectDelay = RECONNECT_MIN_MS;
    sendPing();
  };
  source.onmessage = (event) => {
    if (stream !== source) return;
    const data = event.data;
    ackQueue = ackQueue.then(() => handleEvent(data)).catch(() => {});
  };
  source.onerror = () => {
    if (stream !== source) return;
    source.close();
    stream = null;
    dispatchPresence({ type: navigator.onLine ? 'relayDown' : 'offline' });
    scheduleReconnect();
  };
}

async function handleEvent(data) {
  const current = pairing;
  if (current === null) return;
  const line = extractRelayMessage(data);
  if (line === null || seenAcks.has(line)) return;
  const ack = await verifyAck({
    subtle,
    kAck: current.kAck,
    slot: current.slot,
    line,
    isKnownRef: (ref) => sentNonces.has(ref),
  });
  if (ack === null || pairing !== current) return; // not ours: ignore silently
  seenAcks.add(line);
  if (seenAcks.size > MAX_TRACKED) seenAcks.delete(seenAcks.values().next().value);

  const kind = sentNonces.get(ack.ref);
  if (kind === 'ping') {
    dispatchPresence({ type: 'ack', status: ack.status, ref: ack.ref });
  } else {
    dispatchPresence({ type: 'seen' });
    dispatchCall({ type: 'ack', status: ack.status, ref: ack.ref });
  }
}

// ---------- pairing ----------

function setPairError(text) {
  ui.pairError.textContent = text;
  if (text === '') ui.pairCode.removeAttribute('aria-invalid');
  else ui.pairCode.setAttribute('aria-invalid', 'true');
}

function startMain(record) {
  generation += 1;
  pairing = record;
  call = initialCall();
  presence = initialPresence();
  lastPingAt = 0;
  setText(ui.slotLabel, `Slot ${record.slot}`);
  showView('main');
  render();
  connect();
}

async function onPairSubmit(event) {
  event.preventDefault();
  if (ui.pairButton.disabled) return;
  // Take the code out of the page straight away.
  const typed = ui.pairCode.value;
  ui.pairCode.value = '';
  setPairError('');
  ui.pairButton.disabled = true;

  let seed = null;
  try {
    let parsed;
    try {
      parsed = parsePairingString(typed);
    } catch {
      setPairError('That code does not look right. Check it against the TV and type it again.');
      return;
    }
    seed = parsed.seed;
    const derived = await derivePairing(seed, subtle);
    const record = { slot: parsed.slot, ...derived };
    try {
      await savePairing(globalThis.indexedDB, record);
    } catch {
      setPairError('This browser would not save the pairing. Turn off Private Browsing and try again.');
      return;
    }
    startMain(record);
  } catch {
    setPairError('Pairing failed on this phone. Try again.');
  } finally {
    if (seed !== null) seed.fill(0);
    ui.pairButton.disabled = false;
  }
}

function stopSession() {
  generation += 1;
  pairing = null;
  closeStream();
  CALL_TIMERS.forEach(clearTimer);
  clearTimer('ping');
  sentNonces.clear();
  seenAcks.clear();
  call = initialCall();
  presence = initialPresence();
}

async function unpair() {
  stopSession();
  try {
    await clearPairing(globalThis.indexedDB);
    setPairError('');
  } catch {
    setPairError('The saved pairing could not be deleted. Clear this site’s data in Settings.');
  }
  ui.pairCode.value = '';
  showView('pair');
  render();
}

function askUnpair() {
  if (typeof ui.unpairDialog.showModal === 'function') {
    ui.unpairDialog.showModal();
    ui.unpairCancel.focus();
  } else if (globalThis.confirm('Unpair this phone? You will need the pairing code from the TV again.')) {
    unpair();
  }
}

// ---------- start ----------

function wire() {
  ui.pairForm.addEventListener('submit', onPairSubmit);
  ui.pairCode.addEventListener('input', () => {
    if (ui.pairError.textContent !== '') setPairError('');
  });
  ui.callButton.addEventListener('click', () => {
    if (pairing !== null) dispatchCall({ type: 'tap' });
  });
  ui.cancelButton.addEventListener('click', () => {
    if (pairing !== null) dispatchCall({ type: 'cancelTap' });
  });
  ui.unpairButton.addEventListener('click', askUnpair);
  ui.unpairCancel.addEventListener('click', () => ui.unpairDialog.close());
  ui.unpairConfirm.addEventListener('click', () => {
    ui.unpairDialog.close();
    unpair();
  });

  document.addEventListener('visibilitychange', () => {
    if (pairing === null) return;
    if (document.visibilityState === 'visible') {
      reconnectDelay = RECONNECT_MIN_MS;
      connect();
    } else {
      closeStream();
    }
  });
  globalThis.addEventListener('pageshow', (event) => {
    if (event.persisted && pairing !== null) connect();
  });
  globalThis.addEventListener('online', () => {
    if (pairing !== null && document.visibilityState === 'visible') connect();
  });
  globalThis.addEventListener('offline', () => {
    if (pairing === null) return;
    closeStream();
    dispatchPresence({ type: 'offline' });
  });
}

async function start() {
  wire();
  let record = null;
  try {
    record = await loadPairing(globalThis.indexedDB);
  } catch {
    record = null;
  }
  if (record !== null) {
    startMain(record);
  } else {
    showView('pair');
    render();
  }
}

start();
