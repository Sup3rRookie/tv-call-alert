# Architecture

## What it does

A caller taps one button on their phone. Within a few seconds the family TV pauses what is
playing and shows a full-screen animated "X is calling" card, so someone picks up the phone
for the WhatsApp call that follows. Alert only: no audio or video goes through the TV.

Success metric: the card appears within about 3 seconds of the tap, every time the TV is on.

## Components

```
 PHONE (static page, home screen)        ntfy.sh relay            TV (Android app)
 --------------------------------        -------------            ----------------
 keys in IndexedDB (non-extractable)
 [tap] sign "RT1|alert|..." ---- POST topicDown ----> stream ---> RelayService (foreground)
                                                                   BoundedLineReader
                                                                   AlertPipeline  (core, pure)
                                                                   AlertController
                                                                    - MediaPauser  pause show
                                                                    - AnimeCardView overlay
                                                                    - RingPlayer   ring sound
 status line  <---- SSE topicAck <---- POST ------------------------ AckSender "RA1|shown|..."
```

| Part | Where | Role |
|---|---|---|
| `core/` | pure Java 8, no Android | parsing, signatures, replay guard, rate limit, backoff, relay clock |
| `app/` | Android TV app | relay connection, overlay card, pause and resume, setup screen |
| `trigger/` | static web page | pairing, one-tap alert, ack display |
| `tools/` | Node scripts | test vector generator, developer send tool |

Protocol details are in [PROTOCOL.md](PROTOCOL.md).

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Trigger | caller taps a button | the family phones are iPhones, which cannot report an incoming WhatsApp call to another device |
| Relay | ntfy.sh, public | zero accounts, browser can publish and subscribe directly, TV needs only HTTPS. FCM needs a Firebase account, Play Services and a backend |
| Trust | HMAC-SHA256 per phone | the relay is public, so the topic name is not a secret worth relying on |
| Wire format | pipe-separated ASCII line | no JSON canonicalisation, strict split parser, no free text from the network ever reaches the screen |
| Caller name | stored on the TV | nothing personal crosses the relay; an attacker cannot put text on the TV |
| Card display | accessibility overlay, then "display over other apps" | overlays do not stop the app underneath, so HDMI and live TV keep running. Both grants are set at install so one covers the other |
| Pairing | code shown on TV, pasted on phone | an iPhone home-screen app has storage separate from Safari, so a QR link would pair the wrong storage |
| Pause | media pause key plus transient audio focus | needs no permission; resume only if this app caused the pause |
| TV clock | relay timestamps | TVs often have a wrong clock; freshness uses the relay's time |
| Language | Java 8, no runtime libraries | the dev machine has JDK 8, Gradle 6.1.1 and AGP 4.0.2 only |
| Android JSON | `org.json` for the relay envelope only | the envelope is not security relevant; the signed line inside it goes through the strict core parser |

Where the architect and tech lead differed: the tech lead proposed a hand-written JSON
parser for signed JSON messages; the architect's pipe format removed the need, so it was
dropped. The architect proposed a QR pairing flow and a full-screen activity as a third
display fallback; both were cut from the first version (code entry works for the iPhone
case, and a background activity start is blocked by Android without the same grants the
overlays need).

## Threat model

| Threat | Mitigation | What remains |
|---|---|---|
| Someone learns the topic and sends fake alerts | every message is signed; unsigned input is dropped before any state changes and never acknowledged | none without the key |
| Fake acks fool the phone | acks use a separate key and the phone checks the mac and that it sent the referenced nonce | none without the key |
| Replay of a captured alert | 60 s freshness window, nonce cache, persisted strictly increasing timestamp per phone | none |
| Relay replays its own cache after the TV app restarts | the persisted timestamp mark rejects anything already accepted | none |
| Flood of garbage on the topic | 200 character cap, cheap grammar check before any crypto, bounded line reader, no UI and no ack | could use up the relay's anonymous quota; fix is to re-pair |
| A paired phone spams cards | 10 s cooldown, 3 per 5 minutes, 30 per day, one card at a time, per-phone removal on the TV | a few nuisance cards |
| Malicious text on screen | no text from the network is displayed; the name is typed on the TV and sanitised | none |
| Relay operator or topic reader watches traffic | payload is a slot digit, a timestamp and random values | they learn that a call was signalled at a given time |
| Pairing code seen on the TV | shown only on request, setup screen is excluded from screenshots, stored app-private, backups off | anyone in the room during pairing, or anyone with adb access |
| adb left enabled on the TV | install steps end with turning debugging off | depends on that step being done |
| Compromised hosting serves a malicious page | no third-party scripts, strict CSP, keys are non-extractable | the attacker could send alerts while the page is open; the rate limit bounds it |

## Failure modes

| Situation | Phone shows | TV does |
|---|---|---|
| Normal | "TV is showing your call", then "They pressed OK" | card, pause, ring |
| TV off or offline | "No answer from the TV" after 8 s | nothing; the alert goes stale and is never shown late |
| TV in standby | "TV is in standby" | no card, screen stays off |
| Relay down | "Could not reach the relay" | reconnects with backoff |
| Too many alerts | "TV is ignoring repeated alerts" | no card |
| Display grants lost | "TV could not show the card" | setup screen shows a red status |
| Live TV or HDMI input | "TV is showing your call" | card over the picture; the sound cannot be paused |

## Known limits

- The accessibility overlay can fail right after the app is reinstalled until the service is
  toggled or the TV restarts (seen on the emulator). The "display over other apps" grant is
  the fallback for exactly this.
- No watchdog alarm: the listener relies on the foreground service, the boot receiver, the
  accessibility service bind and `START_STICKY`.
- The rate limiter and nonce cache are in memory; the persisted timestamp mark is what
  survives a restart.
- Everything TCL-specific is unverified until tested on the real TV: whether the firmware
  kills sideloaded background apps, standby and wake behaviour, which apps honour the pause
  key, and how the overlay stacks against TCL's own menus.
