// Draws the app icon (a TV with a call dot) and writes opaque PNGs.
// Node built-ins only. Run from anywhere: node trigger/tools/make-icons.mjs

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'icons');
const SIZES = [180, 512];
const BACKGROUND = [0x1a, 0x7f, 0x37];
const INK = [0xff, 0xff, 0xff];

const CRC_TABLE = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const out = Buffer.alloc(body.length + 8);
  out.writeUInt32BE(data.length, 0);
  body.copy(out, 4);
  out.writeUInt32BE(crc32(body), body.length + 4);
  return out;
}

function encodePng(size, rgb) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // colour type: RGB, no alpha
  const stride = size * 3 + 1;
  const raw = Buffer.alloc(stride * size);
  for (let y = 0; y < size; y += 1) {
    raw[y * stride] = 0; // filter: none
    rgb.copy(raw, y * stride + 1, y * size * 3, (y + 1) * size * 3);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// Signed distances, in units where the icon is 1 x 1.
function roundedBox(x, y, cx, cy, halfW, halfH, radius) {
  const qx = Math.abs(x - cx) - halfW + radius;
  const qy = Math.abs(y - cy) - halfH + radius;
  const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
  return outside + Math.min(Math.max(qx, qy), 0) - radius;
}

function shape(x, y) {
  const screen = Math.abs(roundedBox(x, y, 0.5, 0.455, 0.29, 0.19, 0.055)) - 0.021;
  const stand = roundedBox(x, y, 0.5, 0.745, 0.115, 0.021, 0.021);
  const dot = Math.hypot(x - 0.5, y - 0.455) - 0.05;
  const ring = Math.abs(Math.hypot(x - 0.5, y - 0.455) - 0.105) - 0.014;
  return Math.min(screen, stand, dot, ring);
}

function draw(size) {
  const rgb = Buffer.alloc(size * size * 3);
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const d = shape((x + 0.5) / size, (y + 0.5) / size) * size;
      const cover = Math.min(Math.max(0.5 - d, 0), 1); // one pixel soft edge
      const i = (y * size + x) * 3;
      for (let c = 0; c < 3; c += 1) {
        rgb[i + c] = Math.round(BACKGROUND[c] + (INK[c] - BACKGROUND[c]) * cover);
      }
    }
  }
  return rgb;
}

mkdirSync(OUT_DIR, { recursive: true });
for (const size of SIZES) {
  const file = join(OUT_DIR, `icon-${size}.png`);
  writeFileSync(file, encodePng(size, draw(size)));
  process.stdout.write(`wrote ${file}\n`);
}
