# Standards

## Toolchain (pinned)

| Thing | Version |
|---|---|
| JDK | 8 (Android Studio's bundled `jre`) |
| Gradle | 6.1.1 via the wrapper, checksum pinned |
| Android Gradle Plugin | 4.0.2 |
| compileSdk / targetSdk / minSdk | 30 / 30 / 26 |
| Language | Java 8 |
| Runtime libraries | none |
| Test library | JUnit 4.13.2 |
| Trigger page | plain ES modules, Node 22 built-in test runner, no npm packages |

## Layout

- `core/` holds every decision (accept, reject, why, when to reconnect) as pure Java with no
  Android imports. Time and randomness are passed in so tests are deterministic.
- `app/` only wires Android to `core`. Android classes stay thin.
- `trigger/lib/` holds the page's pure logic; `trigger/app.js` only wires the DOM.

## Code rules

- Untrusted input never throws across the `core` boundary. Parsers and validators return a
  result with a `Rejection` reason. Exceptions are for programmer errors.
- Check order is fixed: size, charset, grammar, topic, signature, freshness, replay, rate
  limit. Nothing is stored or acknowledged before the signature passes.
- Fail closed: any doubt means no card.
- All limits are named constants in `Limits`.
- No empty catch blocks. Network errors are caught in one place and turned into a backoff.
- User-visible strings live in `strings.xml`.
- Naming: `UpperCamelCase` classes, `lowerCamelCase` members, `UPPER_SNAKE` constants, units
  in names (`timeoutMillis`, `maxBytes`). Tests are `ClassNameTest` with methods named
  `condition_expectedOutcome`.
- `core` compiles with `-Xlint:all -Werror`.
- No em-dashes in code, comments, UI text or docs.

## Logging

One wrapper (`L`). Log event names and rejection reasons only. Never log keys, signatures,
payloads, topic names, relay URLs or caller names. Exceptions are logged by class name only,
because their messages can contain the relay URL.

## Secrets

- Nothing secret in any tracked file. Pairing seeds are generated on the TV and live only in
  the TV app's private storage and, as non-extractable keys, in the phone's browser storage.
- The release keystore lives outside the repository. `keystore.properties` is gitignored.
- Test fixtures use obviously fake keys.
- The developer send tool reads the pairing string from an environment variable, not from
  arguments.

## Tests

- `core`: every branch, plus hostile input for every parser and validator.
- `testvectors/vectors.tsv` is generated independently with `node:crypto` and consumed by
  both the Java and JavaScript tests, which proves the two sides agree byte for byte.
- `app`: no instrumented tests. Verified by an emulator smoke run driven with adb and
  `tools/send-test.mjs`, then on the real TV.

## Commands

PowerShell, from the project root:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jre'; $env:ANDROID_SDK_ROOT="$env:LOCALAPPDATA\Android\Sdk"; $env:ANDROID_HOME=$env:ANDROID_SDK_ROOT
.\gradlew.bat :core:test
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
node --test trigger/test/
```

## Commits

Conventional prefix (`feat:`, `fix:`, `chore:`, `doc:`), one short line, no co-author line.
