# Handoff: Spark Ink – Android app for Wacom Spark Folio (BLE sync + OCR + Notion)

**Created:** 2026-10-27 18:51 UTC
**Repo / branch:** `Jeroentjj/Spark-Ink`, branch `main` (head `3c41266`), clean working tree
**Earlier home:** `Jeroentjj/Experiments`, branch `claude/wacom-spark-folio-ocr-app-a5y3zp`, folder `spark-ink/` (full commit history lives there; left in place, not cleaned up)
**Session:** 2026-10-25 → 2026-10-27

---

## Summary

The user bought a second-hand **Wacom Bamboo Spark / "Spark Folio"** (BLE name `Bamboo Spark ben`, address `CD:79:DE:4C:79:DD`, random static). Wacom Inkspace is end-of-life, so we built our own Android app: pair, download pages, render ink, OCR (ML Kit on-device or Claude vision), export to Notion, in-app update. Everything compiles and CI is green. **Pairing with the real pad has not yet succeeded.** The latest finding was that on a second phone the GATT connection works but enabling notifications timed out, because Android starts bonding there. A fix (wait up to 60 s, retry after the bond) is built but **not yet confirmed by the user**.

---

## Work Completed

- [x] `core/` pure-Kotlin module: Wacom smartpad BLE protocol, ported from the description in the open-source **Tuhi** project (tuhiproject/tuhi, `tuhi/protocol.py`, `tuhi/wacom.py`), plus the pen-data stroke decoder. 11 JVM tests, all passing.
- [x] `app/` Android app (Kotlin, framework Views, no Compose): pair, sync, note list and detail, rotate, edit text, copy and share, delete.
- [x] OCR: ML Kit Digital Ink (line segmentation, on-device, language setting) and Claude vision (renders a cropped PNG and calls the Messages API). Auto-OCR after sync; if Claude fails it falls back to ML Kit.
- [x] Notion export: one page per note (a database row or a sub-page), Markdown mapped to headings, lists and paragraphs. Manual button, or automatic after OCR.
- [x] In-app **Update** button: queries the GitHub release `sparkink-latest` of `Jeroentjj/Spark-Ink`, downloads the APK (token, manual redirect without the token), checks size and the `PK` header, then installs via FileProvider.
- [x] CI `.github/workflows/apk.yml`: runs `:core:test` and `:app:assembleDebug`, then republishes the prerelease `sparkink-latest` with `SparkInk.apk`.
- [x] BLE robustness iterations (see the debugging timeline).

### Key Decisions

| Decision | Rationale | Alternatives |
|---|---|---|
| Re-implement the protocol from Tuhi's description | Open documentation; nothing copied from the Inkspace APK (proprietary) | Decompiling Inkspace (rejected) |
| Separate pure-JVM `core` module | Testable here without the Android SDK (`dl.google.com` is blocked in this sandbox) | Everything in `app` |
| Framework Views and `org.json`, minimal dependencies | Small and simple, same style as the user's NextUp app | Compose, Retrofit |
| No explicit `createBond()` | Tuhi doesn't bond; a 25 s bond wait ate the short pairing window | Bond first |
| ML Kit plus Claude, user-selectable | User chose "Beide, instelbaar" | Only one engine |
| Fixed debug keystore (`app/debug.keystore`, password `android`) | Updates install over each other | Release signing |
| versionCode = `GITHUB_RUN_NUMBER`, `BuildConfig.GIT_SHA` = short sha | Update check compares the release's `target_commitish` with `GIT_SHA` | Semantic versions |

---

## Files

- `settings.gradle.kts`: plugin versions (AGP 8.5.2, Kotlin 1.9.24). `CORE_ONLY=1` excludes `:app`, so the core builds without the SDK.
- `core/src/main/kotlin/app/sparkink/core/`
  - `WacomSession.kt`: commands and flows (`connect`, `register`, `syncAll`). Spark and Slate differences are covered.
  - `StrokeParser.kt`: pen-data decoder (bitmask packets, accumulating deltas).
  - `Geometry.kt`: `Sketch.rotated`, `bounds`, `lines()` (line grouping for ML Kit).
  - `NotionBlocks.kt`: Markdown to Notion blocks, title, `extractId` (anchored at the end of the path).
  - `Bytes.kt` (BCD time and LE/BE helpers), `Errors.kt`, `Transport.kt`, `Model.kt`.
- `core/src/test/...`: `CoreTest.kt`, `NotionBlocksTest.kt`, `Fixtures.kt` (FakePad and PenFileBuilder).
- `app/src/main/kotlin/app/sparkink/`
  - `BleTransport.kt`: blocking GATT client. Up to 3 connect attempts, the last one with `autoConnect=true`; a per-attempt callback id ignores late callbacks; `enableNotify` waits 60 s for the bond.
  - `Pad.kt`: scan, `scanFor(address)` before sync, pair and sync orchestration, and `AppLog`.
  - `MainActivity.kt`: all UI (list, note, settings, log, update, Notion).
  - `Ocr.kt` (MlKitOcr, ClaudeOcr), `Notion.kt`, `Updater.kt`, `Render.kt`, `Store.kt` (Note JSON storage and Prefs).
- `app/src/main/AndroidManifest.xml`: BLE permissions, INTERNET, REQUEST_INSTALL_PACKAGES, FileProvider `app.sparkink.files`.

---

## Technical Context (protocol essentials)

- GATT:
  - UART write `6e400002-…`, notify `6e400003-…`.
  - Offline pen data `ffee0003-bbaa-9988-7766-554433221100`.
  - Sysevent `3a340721-…` exists **only** on Slate. If it is absent, the pad uses the Spark protocol.
  - The user's pad shows these characteristics: `2a00 2a01 2a04 2a05 1524 ffee0002 ffee0003 2a19 2a29 2a24 2a25 2a26 2a28 2a50 6e400003 6e400002`. So it is **Spark**.
- Messages: `[opcode, len, args]`, ack is `b3 <status>`. Spark auth error = GENERAL_ERROR (1); Slate = 7.
- Spark register: `connect(uuid)`, expected auth error ignored → `e3 01` → user presses the button → wait for `e4` → `e5` → set time.
- Sync (Spark): `e6 uuid`, `e3`, `b6` time, `b9` battery, `b1 01`, `c1` count (BE), `c5` (→ `c7` count, then `cd` BCD timestamp), `c3` (→ `c8 be`, data streams on `ffee0003`, then `c8 ed`, `c9` crc32 BE), then `ca` delete with no reply. The app deletes a page only after it has saved it.
- Tuhi README: with no drawings pending the Spark is hard to connect to. Connect first, *then* press the button. In pairing mode (LED blinking) a direct connect works. The first connection often drops after service discovery.

---

## Debugging timeline (real device)

1. On the first phone (Android 13), pairing failed with no GATT callback at all (status -1 time-outs, once 133). Adverts were fine: `connectable=true flags=5 rssi=-35`. Not resolved. Suspected: a stale bond, or too many concurrent BLE links (Amazfit, WH-1000XM3, Fitbit). nRF Connect test requested, **never answered**.
2. "Pair in BT settings" didn't work on the first phone. On a **second phone** the system pairing "succeeds" but the Folio keeps blinking.
3. Second phone with the app: `gatt state=2 status=0`, services found, then **"Enabling notifications timed out"** (10 s). Root-cause hypothesis: the CCCD write needs encryption, so Android starts bonding, and the app aborted before the user confirmed.
4. Fix pushed (Experiments run 6, included in Spark-Ink `main`): 60 s wait; on status 5 or 15, wait for BOND_BONDED and write again.
5. **2026-10-27 evening, OnePlus 9 log (from an opencode session):** notifications now enable without bonding (`bond=10` throughout), `pad family: SPARK`, `> e6 …` gives `< b3/01 01` (the expected auth error in pairing mode), `> e3 01` is sent, then about **5 s later** `gatt state=0 status=8` (link supervision timeout, the pad drops the link). Reproduced twice. So the BLE layer works; registration fails because the pad gives up while waiting for the button press.
6. **Probable root cause: the pad's memory is full.** The user sees the LED **alternately blue and orange**. Wacom support ("What do the LEDs on Bamboo Spark mean?"): blue blinking = pairing, orange blinking = low battery, orange solid = charging, blue solid = page has content, green solid = page empty, **blue-orange = memory full**. The app's own pairing text wrongly said "wait until it blinks blue/orange"; this is now fixed, and failures show an LED legend.
8. **2026-10-28:** the pad keeps blinking blue-orange however long the button is held. The main phone (old APK) gets no GATT response at all. Added **"Folio wissen + resetten"**. It scans, the user picks the pad, the app registers (user presses the button), then sends `e2 01 00` (DeleteAllFiles) and `e1 01 00` (ResetToDefaults). Both opcodes come from Inkspace's `wacom.smartPadCommunication.js` and expect a `b3` ack. The order follows the Inkspace reset recipe (mattiebee.io, 2017): pair, confirm with the button, then "Device Memory Reset". That recipe also says re-pairing worked while the LEDs flashed alternately. Not yet tried on hardware.
7. Not taken over from opencode: (a) its `WacomSession.register` edit only moved `onPressButton()` inside the `if` and added a 500 ms sleep; the prompt was already shown right after `e3`, so the edit changed nothing; (b) its `gradle.properties` edit (`-Xmx512m`, no daemon) was a workaround for a 32-bit Java 8 on the laptop. AGP 8.5 needs JDK 17, so a local build needs JDK 17 anyway; CI is unaffected.

Other gotchas:
- The repos are private. Downloading without being logged in gives a 404 or HTML page, which the phone reports as "pakket ongeldig". Use a browser logged in to GitHub.
- The installed app's updater may point at the old repo (`Jeroentjj/Experiments`). The first install from Spark-Ink must be manual.
- GitHub Actions on Spark-Ink needs write workflow permissions to create the release. Run 1 was green, and the release `sparkink-latest` with `SparkInk.apk` exists (verified 2026-10-27).
- This sandbox cannot reach `dl.google.com` (no Android SDK) or Google Drive. GitHub raw, Maven Central and plugins.gradle.org work.

---

## Current State

- **Working (verified on hardware, 2026-10-28, OnePlus 9, build 2b80d41):** "Folio wissen + resetten" emptied the full pad. `> e2 01 00` was sent after the refused login, the pad dropped the link (`status=19`), and the LED turned **green** (page empty). After that, registration (`e3`/`e4`) works, and so does unpair plus re-pair. The app reports the wipe as failed because the link drops after `e2`; the `e1` reset and the registration fallback then run on a dead link. Cosmetic, to fix.
- **Working (verified):** core tests (13/13), CI APK builds, scanning, GATT connect and service discovery on the OnePlus 9.
- **Unverified on hardware:** sync and download, the stroke parser on real data, rotation default, ML Kit and Claude OCR, Notion export, updater install.
- **Not working:** pairing on the user's main phone (no GATT response).

---

## Next Steps

### Immediate
1. Charge the Folio fully. Then hold the button 6+ s until the LED blinks **blue only** (not blue-orange) and pair from the OnePlus 9. Press the pad button within ~5 s of the "Bevestig op de Folio" prompt. Look for `< e4` in the log.
2. If the pad only ever shows blue-orange: memory is full and it may refuse a new registration. Emptying needs a registered app that syncs (sync deletes each page after saving). Options: Tuhi on a Linux machine, or the old Inkspace app if it was ever registered. There is no documented factory reset; don't guess one.
3. If registration works, test sync. Write on paper, tap Synchroniseer, *then* press the button. Verify the parser output; the raw bytes are saved as `filesDir/notes/<id>.raw`.
4. If notifications still fail: consider calling `createBond()` explicitly *after* connect, when the CCCD write returns 5/15. Also consider `requestConnectionPriority(HIGH)` and a short delay after `onServicesDiscovered`.

### Subsequent
- Main phone: ask for the nRF Connect result. Try with other BLE devices disconnected. Consider calling `BluetoothGatt.refresh()` via reflection (Inkspace references `refresh`, `autoConnect` and `autoconnectDelayInMillis`; it does not use `createBond`).
- Optionally remove `spark-ink/` and `.github/workflows/sparkink-apk.yml` from Experiments, but only if the user asks.
- Optional features: upload the ink image to Notion (File Upload API); show pressure-based stroke width.

---

## Commands

```bash
cd /home/user/spark-ink
CORE_ONLY=1 ./gradlew --no-daemon -q :core:test   # runs locally (no Android SDK here)
# The Android app only builds in CI: push to main, then check Actions on Jeroentjj/Spark-Ink
```

GitHub MCP: `actions_list` / `get_workflow_run` on owner `jeroentjj`, repo `spark-ink`; `get_release_by_tag` with tag `sparkink-latest`.

---

## Open Questions
- [x] Does the 60 s bond wait make pairing succeed on the second phone? Notifications now work (no bond needed); registration still fails, see timeline 5–6.
- [ ] Does pairing work once the LED blinks blue only (memory not full)?
- [ ] Does nRF Connect connect on the main phone?
- [ ] Is the Spark-Ink repo private? (Probably yes; the updater needs a token.)
- [ ] Is the default page rotation correct for the Folio?

## Session Notes
- The user writes in Dutch; the app UI is in Dutch. The user's own words: "Spark Folio", blinking blue and orange in pairing mode.
- The Claude model default in the app is `claude-sonnet-5-5` (configurable). Settings store API keys and tokens in plain SharedPreferences.
- Do not put model identifiers in commits or PRs. Use the commit trailer from the latest system attribution reminder.
