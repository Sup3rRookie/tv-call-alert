// Relay access. The base URL below is the only place the relay is named;
// the Content-Security-Policy in index.html must list the same origin.

import { isTopic, MAX_LINE_CHARS } from './protocol.js';

export const RELAY_BASE = 'https://ntfy.sh';

const SINCE_RE = /^[1-9][0-9]{0,3}[smh]$/;

/** URL a line is POSTed to. Throws on a malformed topic. */
export function publishUrl(topic, base = RELAY_BASE) {
  if (!isTopic(topic)) throw new RangeError('bad topic');
  return `${base}/${topic}`;
}

/**
 * URL of the server-sent event stream for a topic. `since` (for example
 * "2m") asks the relay to replay recent cached messages first, which covers
 * acks that arrived while the page was suspended.
 */
export function streamUrl(topic, { since } = {}, base = RELAY_BASE) {
  if (!isTopic(topic)) throw new RangeError('bad topic');
  if (since === undefined) return `${base}/${topic}/sse`;
  if (typeof since !== 'string' || !SINCE_RE.test(since)) throw new RangeError('bad since');
  return `${base}/${topic}/sse?since=${since}`;
}

/**
 * POST one line to the relay. Never throws.
 * Resolves to { ok: true } or { ok: false, kind } where kind is
 * "offline" (the device has no connection) or "relay" (anything else:
 * timeout, DNS, HTTP error, rate limit).
 * `online` is the caller's reading of navigator.onLine.
 */
export async function publish({ fetch, topic, line, online = true, signal, base = RELAY_BASE }) {
  let url;
  try {
    url = publishUrl(topic, base);
    if (typeof line !== 'string' || line.length === 0 || line.length > MAX_LINE_CHARS) {
      throw new RangeError('bad line');
    }
  } catch {
    return { ok: false, kind: 'relay' };
  }
  if (online === false) return { ok: false, kind: 'offline' };
  try {
    const response = await fetch(url, {
      method: 'POST',
      body: line,
      mode: 'cors',
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      signal,
    });
    if (response.ok === true) return { ok: true };
    return { ok: false, kind: 'relay', status: response.status };
  } catch {
    return { ok: false, kind: 'relay' };
  }
}
