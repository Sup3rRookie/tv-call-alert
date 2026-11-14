// Static checks on the shipped files: security headers, no inline code,
// relative URLs only, no secrets, no stray characters.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';

import { RELAY_BASE } from '../lib/relay.js';
import { TRIGGER_DIR, loadVectors } from './helpers.js';

const read = (...parts) => readFileSync(join(TRIGGER_DIR, ...parts), 'utf8');
const html = read('index.html');

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? walk(join(dir, entry.name)) : [join(dir, entry.name)],
  );
}
const allFiles = walk(TRIGGER_DIR);
const textFiles = allFiles.filter((f) => /\.(html|css|js|mjs|webmanifest)$/.test(f));
const shipped = textFiles.filter((f) => !relative(TRIGGER_DIR, f).startsWith('test'));

test('page: strict Content-Security-Policy meta tag', () => {
  const m = /<meta http-equiv="Content-Security-Policy" content="([^"]+)">/.exec(html);
  assert.ok(m, 'CSP meta tag present');
  const directives = Object.fromEntries(
    m[1].split(';').map((part) => {
      const [name, ...values] = part.trim().split(/\s+/);
      return [name, values.join(' ')];
    }),
  );
  assert.deepEqual(directives, {
    'default-src': "'none'",
    'script-src': "'self'",
    'style-src': "'self'",
    'img-src': "'self'",
    'manifest-src': "'self'",
    'connect-src': RELAY_BASE,
    'base-uri': "'none'",
    'form-action': "'none'",
  });
  assert.ok(html.indexOf('Content-Security-Policy') < html.indexOf('<script'), 'CSP comes before any script');
  assert.ok(html.indexOf('Content-Security-Policy') < html.indexOf('<link'), 'CSP comes before any link');
});

test('page: referrer policy and iOS home-screen tags', () => {
  assert.match(html, /<meta name="referrer" content="no-referrer">/);
  assert.match(html, /<meta name="viewport" content="[^"]*viewport-fit=cover[^"]*">/);
  assert.match(html, /<meta name="apple-mobile-web-app-capable" content="yes">/);
  assert.match(html, /<meta name="theme-color" /);
  assert.match(html, /<link rel="apple-touch-icon" [^>]*href="icons\/icon-180\.png">/);
  assert.match(html, /<link rel="manifest" href="manifest\.webmanifest">/);
  assert.match(read('styles.css'), /env\(safe-area-inset-bottom\)/);
  assert.match(read('styles.css'), /prefers-color-scheme: dark/);
  assert.match(read('styles.css'), /prefers-reduced-motion: reduce/);
});

test('page: no inline scripts, styles or event handlers', () => {
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1);
  assert.match(scripts[0][1], /type="module" src="app\.js"/);
  assert.equal(scripts[0][2].trim(), '');
  assert.doesNotMatch(html, /<style\b/i);
  assert.doesNotMatch(html, /\sstyle\s*=/i);
  assert.doesNotMatch(html, /\son[a-z]+\s*=/i);
  assert.doesNotMatch(html, /javascript:/i);
});

test('page: every referenced asset is relative and exists', () => {
  const refs = [...html.matchAll(/\s(?:href|src)="([^"]+)"/g)].map((m) => m[1]);
  assert.ok(refs.length >= 5);
  for (const ref of refs) {
    if (ref === 'whatsapp://') continue;
    assert.doesNotMatch(ref, /^([a-z]+:|\/)/i, `${ref} must be relative`);
    assert.ok(existsSync(join(TRIGGER_DIR, ref)), `${ref} exists`);
  }
  const manifest = JSON.parse(read('manifest.webmanifest'));
  assert.equal(manifest.start_url, './');
  assert.equal(manifest.scope, './');
  assert.equal(manifest.display, 'standalone');
  for (const icon of manifest.icons) {
    assert.doesNotMatch(icon.src, /^([a-z]+:|\/)/i);
    assert.ok(existsSync(join(TRIGGER_DIR, icon.src)), `${icon.src} exists`);
  }
});

test('page: icons are real PNGs of the declared size', () => {
  for (const size of [180, 512]) {
    const png = readFileSync(join(TRIGGER_DIR, 'icons', `icon-${size}.png`));
    assert.deepEqual([...png.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    assert.equal(png.readUInt32BE(16), size);
    assert.equal(png.readUInt32BE(20), size);
    assert.equal(png[25], 2, 'opaque RGB, as iOS wants for home-screen icons');
  }
});

test('page: live regions and the main controls are present', () => {
  assert.match(html, /<p id="status"[^>]*aria-live="polite"/);
  assert.match(html, /<p id="presence"[^>]*aria-live="polite"/);
  assert.match(html, /<button id="call-button"[^>]*type="button"/);
  assert.match(html, /<a id="whatsapp-link"[^>]*href="whatsapp:\/\/"/);
  assert.match(html, /<html lang="en">/);
  const input = /<input id="pair-code"[^>]*>/.exec(html)[0];
  assert.doesNotMatch(input, /\sname=/, 'an unnamed field can never be serialised into a URL');
  assert.match(input, /autocomplete="off"/);
  assert.doesNotMatch(input, /\svalue=/);
});

test('source: the relay is named in exactly one constant, and no other host appears', () => {
  const hits = [];
  // tools/ holds the local dev server and icon script, which the page never loads.
  for (const file of shipped.filter((f) => !relative(TRIGGER_DIR, f).startsWith('tools'))) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(/\b(?:https?|wss?):\/\/[^\s"'`)<>]+/g)) {
      hits.push(`${relative(TRIGGER_DIR, file).replaceAll('\\', '/')}: ${m[0]}`);
    }
  }
  const allowed = new Set([
    'index.html: https://ntfy.sh;', // the CSP connect-src entry
    'lib/relay.js: https://ntfy.sh', // the constant
  ]);
  for (const hit of hits) assert.ok(allowed.has(hit), `unexpected URL: ${hit}`);
  assert.equal(hits.filter((hit) => hit === 'lib/relay.js: https://ntfy.sh').length, 1);
});

test('source: dynamic text goes through textContent only, and nothing is logged or stored loosely', () => {
  for (const file of shipped.filter((f) => !f.includes('tools'))) {
    const text = readFileSync(file, 'utf8');
    const name = relative(TRIGGER_DIR, file).replaceAll('\\', '/');
    if (name === 'lib/tsstore.js') {
      // The one file allowed to use web storage, for the non-secret last ts.
      assert.equal(text.match(/localStorage/g).length, 1, name);
      assert.equal(text.match(/setItem\(/g).length, 1, name);
      assert.match(text, /setItem\(KEY, String\(ts\)\)/, name);
      continue;
    }
    assert.doesNotMatch(text, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function/, name);
    assert.doesNotMatch(text, /console\./, name);
    assert.doesNotMatch(text, /localStorage|sessionStorage|document\.cookie/, name);
    assert.doesNotMatch(text, /location\.(hash|search|href)|history\.(push|replace)State/, name);
  }
});

test('source: no test seed or code is baked into shipped files', () => {
  const needles = new Set();
  for (const v of loadVectors()) {
    needles.add(v.seedHex);
    needles.add(v.seedCode);
  }
  for (const file of shipped) {
    const text = readFileSync(file, 'utf8');
    for (const needle of needles) {
      assert.ok(!text.includes(needle), `${relative(TRIGGER_DIR, file)} contains a seed`);
    }
  }
});

test('source: no em-dashes anywhere under trigger/', () => {
  for (const file of textFiles) {
    const emDash = String.fromCharCode(0x2014);
    assert.ok(!readFileSync(file, 'utf8').includes(emDash), relative(TRIGGER_DIR, file));
  }
});
