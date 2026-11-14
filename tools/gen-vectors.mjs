// Generates testvectors/vectors.tsv using only node:crypto, independent of the Java core
// and the trigger page library. Keys here are fixed test values, never real pairing seeds.
import { createHmac } from "node:crypto";
import { writeFileSync } from "node:fs";

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32(buf) {
  let bits = "";
  for (const b of buf) bits += b.toString(2).padStart(8, "0");
  let out = "";
  for (let i = 0; i < bits.length; i += 5) {
    out += B32[parseInt(bits.slice(i, i + 5).padEnd(5, "0"), 2)];
  }
  return out;
}

const hmac = (key, text) => createHmac("sha256", key).update(text, "ascii").digest();
const b64u = (buf) => buf.toString("base64url");

const cases = [
  ["000102030405060708090a0b0c0d0e0f", "alert", 1, 1794700000, "AAAAAAAAAAAAAAAAAAAAAA", "-", "shown", 1794700002],
  ["000102030405060708090a0b0c0d0e0f", "cancel", 1, 1794700020, "BBBBBBBBBBBBBBBBBBBBBB", "AAAAAAAAAAAAAAAAAAAAAA", "cancelled", 1794700021],
  ["000102030405060708090a0b0c0d0e0f", "ping", 1, 1794700030, "abcdefghijklmnopqrstuv", "-", "pong", 1794700030],
  ["ffffffffffffffffffffffffffffffff", "alert", 4, 1999999999, "_-_-_-_-_-_-_-_-_-_-_-", "-", "dismissed", 2000000010],
  ["00000000000000000000000000000000", "alert", 2, 1000000000, "0123456789ABCDEFabcdef", "-", "expired", 1000000045],
  ["8f3a1c5e7b9d2f4a6c8e0b1d3f5a7c9e", "ping", 3, 1794712345, "Zz9-Yy8_Xx7-Ww6_Vv5-Uu", "-", "asleep", 1794712346],
];

const header = [
  "seedHex", "seedCode", "topicDown", "topicAck", "type", "slot", "ts", "nonce", "ref",
  "message", "ackStatus", "ackTs", "ack",
];
const lines = [header.join("\t")];
for (const [seedHex, type, slot, ts, nonce, ref, ackStatus, ackTs] of cases) {
  const seed = Buffer.from(seedHex, "hex");
  const kAuth = hmac(seed, "rt1/auth");
  const kAck = hmac(seed, "rt1/ack");
  const topicDown = "rt" + hmac(seed, "rt1/down").toString("hex").slice(0, 40);
  const topicAck = "rt" + hmac(seed, "rt1/up").toString("hex").slice(0, 40);
  const signed = `RT1|${type}|${slot}|${ts}|${nonce}|${ref}`;
  const message = `${signed}|${b64u(hmac(kAuth, signed))}`;
  const ackSigned = `RA1|${ackStatus}|${slot}|${ackTs}|${nonce}`;
  const ack = `${ackSigned}|${b64u(hmac(kAck, ackSigned))}`;
  lines.push([
    seedHex, base32(seed), topicDown, topicAck, type, slot, ts, nonce, ref, message,
    ackStatus, ackTs, ack,
  ].join("\t"));
}
writeFileSync(new URL("../testvectors/vectors.tsv", import.meta.url), lines.join("\n") + "\n");
console.log(`wrote ${cases.length} vectors`);
