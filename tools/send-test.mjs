// Developer tool: sends one signed message to a TV and prints the acks that come back.
// The pairing string is read from the TVCA_PAIRING environment variable so it never lands
// in shell history or process listings.
//
//   TVCA_PAIRING=1-XXXX-... node tools/send-test.mjs alert
//   TVCA_PAIRING=1-XXXX-... node tools/send-test.mjs cancel <alert nonce>
//   TVCA_PAIRING=1-XXXX-... node tools/send-test.mjs ping
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const RELAY = "https://ntfy.sh/";
const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const WAIT_MILLIS = Number(process.env.TVCA_WAIT_MS ?? 8000);

const pairing = process.env.TVCA_PAIRING ?? "";
const type = process.argv[2] ?? "alert";
const ref = type === "cancel" ? process.argv[3] : "-";
const slot = Number(pairing[0]);
const clean = pairing.slice(1).replace(/[\s-]/g, "").toUpperCase();
if (!(slot >= 1 && slot <= 4) || !/^[A-Z2-7]{26}$/.test(clean) || !ref) {
  console.error("usage: TVCA_PAIRING=<slot>-<code> node tools/send-test.mjs alert|ping|cancel <nonce>");
  process.exit(2);
}
let bits = "";
for (const c of clean) bits += B32.indexOf(c).toString(2).padStart(5, "0");
const seed = Buffer.from(bits.slice(0, 128).match(/.{8}/g).map((b) => parseInt(b, 2)));

const hmac = (key, text) => createHmac("sha256", key).update(text, "ascii").digest();
const kAuth = hmac(seed, "rt1/auth");
const kAck = hmac(seed, "rt1/ack");
const topicDown = "rt" + hmac(seed, "rt1/down").toString("hex").slice(0, 40);
const topicAck = "rt" + hmac(seed, "rt1/up").toString("hex").slice(0, 40);

const nonce = randomBytes(16).toString("base64url");
const ts = Math.floor(Date.now() / 1000) + Number(process.env.TVCA_TS_OFFSET ?? 0);
const signed = `RT1|${type}|${slot}|${ts}|${nonce}|${ref}`;
const line = `${signed}|${hmac(kAuth, signed).toString("base64url")}`;

const abort = new AbortController();
setTimeout(() => abort.abort(), WAIT_MILLIS);
const stream = fetch(`${RELAY}${topicAck}/json`, { signal: abort.signal });
const sent = await fetch(RELAY + topicDown, { method: "POST", body: line });
console.log(`sent ${type} nonce=${nonce} http=${sent.status}`);

try {
  const res = await stream;
  const decoder = new TextDecoder();
  let buffer = "";
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const row = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      let event;
      try {
        event = JSON.parse(row);
      } catch {
        continue;
      }
      if (event.event !== "message") continue;
      const cut = event.message.lastIndexOf("|");
      const expected = hmac(kAck, event.message.slice(0, cut)).toString("base64url");
      const got = event.message.slice(cut + 1);
      const valid = got.length === expected.length
        && timingSafeEqual(Buffer.from(got), Buffer.from(expected));
      const fields = event.message.split("|");
      console.log(`ack ${fields[1]} ref=${fields[4]} mac=${valid ? "valid" : "INVALID"}`);
    }
  }
} catch (e) {
  if (e.name !== "AbortError") throw e;
}
console.log("done");
