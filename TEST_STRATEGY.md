# Shield — Test Strategy (layers, tooling, automation)

Companion to `TESTING_PLAN.md` (the *what*) and `ACCEPTANCE_AND_TEST_CASES.md` (the *cases*). This is the
*how*: **coverage comes from layered techniques, not from a longer manual list.** No finite case count covers
"every scenario"; this strategy covers *classes* of bugs the manual suite structurally can't.

**Privacy/size notes up front:**
- All test tooling is `testImplementation` / `androidTestImplementation` — **none ships in the release APK** (no size cost).
- Prefer **Play Console Android vitals + Pre-launch report** over a crash SDK — they need **no SDK and collect nothing extra**, which fits the privacy-first stance. (Crashlytics is an option but adds data collection → a Data-Safety entry.)

---

## 1. The layers (what each catches that the others can't)

| # | Layer | Catches | Status in this app |
|---|---|---|---|
| L1 | **Unit** (pure logic, JUnit) | edge values, codec/crypto correctness, off-by-ones | ✅ **111 tests** — strong on `remote/`, `premium/`, `util/`, `data/` codecs |
| L2 | **Property / fuzz** | malformed input, "never crash" invariants, round-trip | ✅ **implemented** — `RemotePropertyTest`, `UsageMeasurePropertyTest`, `KidProfileCodecPropertyTest` (12 methods, ~tens of thousands of generated inputs) |
| L3 | **Instrumented / Compose UI** (Espresso + `compose.ui.test`) | navigation, gating, state, regressions per build | ❌ none yet — **deps already present**, automate P0 flows (§2) |
| L4 | **Integration** (Firestore emulator, Hilt test) | the remote read/write/listener pipe without two phones | ❌ none — high value for H/I |
| L5 | **Device matrix** (Firebase **Test Lab** Robo) | OEM/OS fragmentation: screen-off usage, overlay/lock quirks | ❌ none — the only practical fragmentation coverage |
| L6 | **Soak / stress / concurrency** (`adb monkey`, long-run) | races (listener vs identify), memory, Doze, leaks | ❌ none — needed before release |
| L7 | **Manual / exploratory** (the 155 cases) | human judgment, camera/face, two-phone flows | ✅ documented |
| L8 | **Beta + analytics** (Play vitals, Pre-launch) | the genuinely unknown, real-world emergent | ❌ set up before launch |
| L9 | **Security** (the 🔒 cases + static analysis) | bypass, clock-tamper, forged commands, spoofing | partial — has 🔒 cases; add static checks |

The manual suite is **L7**. "Cover every scenario" needs L1–L9 together.

---

## 2. What to automate first (instrumented/Compose UI — L3)

Deps are already wired (`androidx.compose.ui.test.junit4`, `espresso-core`, `androidx.test.ext:junit`). Automate the
**P0 regression flows** so they re-run every build. Target ~12–15 flows, highest value first:

| # | Flow to automate | Maps to | Why automate |
|---|---|---|---|
| 1 | Promo-on = everything free, no paywall | TC-1-03, TC-F-01 | guards the launch invariant |
| 2 | Entitlement gating off-promo (a premium feature locks) | TC-F-07 | the monetization core |
| 3 | `feature_tiers_json` flip converts one feature | TC-F-07 | config-driven flip correctness |
| 4 | Settings hub → every detail screen + back | TC-1-02 | navigation regression |
| 5 | Kid Mode budget math → lock decision (logic-level) | TC-B-01 | core enforcement |
| 6 | Stats window math / empty state | TC-D-02/04 | render + math regression |
| 7 | Remote pairing QR encode→parse→Pairing | TC-H-01 (logic) | already unit-covered; assert at VM level |
| 8 | Child opt-in OFF → no upload attempted | TC-H-04 | privacy invariant |
| 9 | Command gate: forged/stale/duplicate rejected | TC-I-04/05/10 | security-critical, fully unit-automatable |
| 10 | Paywall renders plans from fake ProductDetails | TC-F-03 | UI regression w/ injected billing |
| 11 | Theme accent applies + persists | TC-J-03 | cheap regression |
| 12 | DataStore round-trips (all codecs) | many | data-integrity guard |

**Keep manual (don't fight to automate):** anything needing the **camera/face** (enrollment, unlock, identify),
the **two-phone** remote round-trip, **Device Admin**, and **real Play purchases** — these are L4/L5/L7 territory.

> Pattern: use **Hilt test modules** to inject fakes for `BillingManager`, `RemoteConfigSource`, the Firestore
> repositories, and `DataStoreManager` — so UI/logic tests run deterministically without network/Play/camera.

---

## 3. Fuzz / property targets (L2 — cheap, high value, do first)

Feed random + malformed input; assert **(a) never throws** and **(b) round-trip/identity invariants**. Best targets:

| Target | Invariant to assert |
|---|---|
| `RemoteReportCodec` / `RemoteCommandCodec` | `decode(encode(x)) == x`; arbitrary bytes → null, never crash |
| `ReportCrypto` | `decrypt(encrypt(p,k),k) == p`; wrong key/tampered → null; random ciphertext → null |
| `PairingManager.parseQr` | random strings → null; `parseQr(encodeQr(p)) == p`; never throws |
| `QrScanner.decodeLuminance` | random byte buffers → null, never throws |
| `UsageMeasure` | random event streams → totals are finite, ≥0, ≤ window length (the phantom-usage class) |
| `KidProfileCodec` / `ScheduleCodec` / `PerAppLimitCodec` / `FaceGalleryCodec` | round-trip; malformed lines skipped, never crash |

Tooling: **jqwik** or **kotlin.test** with generated inputs (pure JVM — fast, no device). This is the single
**best ROI** for a solo dev: a few generators harden every parser/crypto path against the entire malformed-input class.

---

## 4. Integration without two phones (L4)

- **Firestore local emulator** + the repositories → test the **write → read → decrypt → decode** pipe and the
  **listener** deterministically (covers most of H/I logic without hardware).
- **Robolectric** for Android-dependent units (DataStore, simple Compose) that aren't worth a full instrumented run.

## 5. Device matrix (L5 — the only real fragmentation coverage)

- **Firebase Test Lab → Robo test**: upload the debug APK, run across a **matrix** of real devices (incl. API 24 +
  several OEMs). Robo crawls the UI and auto-detects crashes/ANRs you'd never script. Free tier covers a few runs/day.
- Specifically targets your known OEM risks: **screen-off usage events** and **overlay/lock behavior** vary by skin.
- **Play Console Pre-launch report** does a similar real-device crawl automatically on every upload — turn it on.

## 6. Soak / stress / concurrency (L6)

- `adb shell monkey -p com.appsecure.shield 50000` → random-input stress; assert no crash/ANR.
- **48h soak** per device (GATE-7): leave running, force Doze, reboot, toggle network — confirm FGS persistence + listener reconnect.
- Concurrency: scripts that fire rapid remote commands / toggle pairing while a lock is active (TC-I-12/13, TC-X-05).

## 7. Beta + quality signals (L8 — catches the unknown)

- **Closed testing track** with a small real cohort (also satisfies Play's pre-production testing requirement).
- **Android vitals** (automatic, no SDK): watch crash rate, ANR rate, wake-locks, excessive wakeups.
- Optional **Crashlytics** for stack traces — but weigh against the privacy stance (it collects crash data → Data-Safety entry). For a privacy-first app, **vitals + pre-launch report first**, add Crashlytics only if you need deeper traces.

## 8. Security testing (L9)

- Run the **🔒 cases** (clock-tamper TC-B-13/14, bypass TC-A-06/07/17, forged command TC-I-04, root TC-X-10) as a dedicated pass → **GATE-8**.
- Static: **Android Lint** (security/permission checks) + a quick **`SecurityException`/exported-component** audit (manifest is mostly `exported=false` ✅; `BootReceiver`/`AdminReceiver` are intentionally exported with the right guards).
- Confirm the documented residual risks (client-side entitlement spoofable on root; bearer-token model) are **accepted + written down**, not silently shipped.

---

## 9. Pragmatic rollout for a solo dev (do it in this order)

Don't boil the ocean. Highest confidence-per-hour first:

1. ✅ **Fuzz the parsers/crypto** (§3) — **done**: 3 property suites green, hardening the entire malformed-input + round-trip class. *(L2)*
2. **Automate 6–8 P0 logic/UI flows** (§2 rows 1–9) with Hilt fakes — your per-build safety net. *(L3)*
3. **Firestore-emulator integration** for the remote pipe (§4) — proves H/I without two phones. *(L4)*
4. **Run the 155 manual cases** P0-first on 2 phones (`ACCEPTANCE_AND_TEST_CASES.md`). *(L7)*
5. **Firebase Test Lab Robo** + enable **Pre-launch report** (§5) — fragmentation for free. *(L5)*
6. **48h soak + monkey** per device (§6). *(L6)*
7. **Closed beta + Android vitals** (§7). *(L8)*
8. **Security pass → GATE-8** (§8). *(L9)*

After 1–3, every build is self-checking; 4–8 are the pre-release gates.

## 10. "When is it enough?" (the honest exit bar)

Not "every scenario" — instead, **all of these true at once:**
- L1+L2 green; pure modules well-covered; parsers/crypto fuzzed.
- L3 P0 flows automated and green per build.
- All **P0** manual cases PASS on ≥2 devices (incl. non-Pixel); **GATE-1…8** green.
- One clean **Test Lab matrix** run + clean **Pre-launch report**.
- A **beta** with crash/ANR rate under your bar for ~1–2 weeks.
- Residual risks documented and accepted.

That's achievable confidence. A bigger manual list is not the path — **layers are.**

## Cross-references
- `ACCEPTANCE_AND_TEST_CASES.md` — the manual suite (L7) these layers wrap around.
- `TESTING_PLAN.md` — feature inventory + environment + gates.
- `PLAYSTORE_RELEASE_PLAN.md` — Pre-launch report, closed testing, Data Safety (relevant if adding Crashlytics).
