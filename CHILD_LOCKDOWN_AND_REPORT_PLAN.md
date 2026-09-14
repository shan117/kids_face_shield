# Parental-Control Hardening — Child Lockdown (A) + Richer Report (B)

Two plans, in priority order. **Part A is the one that makes the product trustworthy** — without it, the
remote report and remote control are meaningless (a child who can open the app can just turn them off).
Part B makes the report richer. The configurable "also lock phone & messages" option lives in A4.

> **Framing reality (read first):** the app is **role-based, one binary on both phones** (D5 of the premium
> plan). That's fine *only if the child instance is a cage* — the child must not be able to reach anything
> that weakens protection. Today it can (Settings, the remote pane, unpair, the toggles are all reachable).
> Part A closes that. **Honest ceiling:** without **Device Owner** provisioning, a determined kid can still
> *force-stop* the app or boot **Safe Mode**. We harden everything below that; full closure is a future
> Device-Owner "Strict/Pro" mode (§A6).

---

# PART A — Child Device Lockdown (the "cage")

## A0. The problem
The same app on the child's phone exposes the entire control surface to the child: open Shield → Settings →
disable Kid Mode / unprotect apps / **unpair / turn off "Allow remote control" / stop sharing / rotate key**.
A controlled party with full UI access isn't controlled. Family Link avoids this with a **separate, locked-down
child app**; we instead **cage our child instance behind the parent's face.**

## A1. Principle
> On a **Child device**, the child can reach **nothing** that weakens protection. Every such surface is behind
> the **parent's face** (the authority identity, D11). The background protection (service, locks, sync) keeps
> running without the UI ever being opened.

## A2. Decisions
| # | Decision |
|---|---|
| A-D1 | **Three device roles:** **Parent (viewer)**, **Child (managed)**, **Unmanaged**. Only *Child* enables the cage. Driven by an explicit `device_role` (extends the existing `owner_type` / `remote_role`). |
| A-D2 | **Lock the whole Shield UI on a Child device** — opening the app requires the **parent face** (`lock_own_app` ON by default in Child mode). One gate covers Settings, the remote pane, unpair, toggles — everything. |
| A-D3 | **Face-gate system Settings** (`lock_device_settings` ON) so the child can't reach **Device Admin** / **App-info → force-stop/uninstall** to disable protection. |
| A-D4 | **Device Admin ON** (anti-uninstall) in Child mode. |
| A-D5 | **Defense in depth:** the *critical* remote actions — unpair, rotate key, turn off Share / Allow-remote-control, change `device_role` — require the **parent face** *again* at the action, even if the app is somehow open. |
| A-D6 | **Child-setup wizard** + a **cage-status checklist** (face enrolled? admin on? settings-lock on? app-lock on? overlay/usage granted?) — the parent sees, at a glance, whether the cage is sealed. |
| A-D7 | **Honest ceiling:** force-stop + Safe Mode survive without Device Owner. Surface this in the UI ("for an unbreakable lock, set up Strict mode") rather than pretending it's airtight. |
| A-D8 | **Configurable lock strictness** (your request): the remote lock can optionally **also block Phone & Messages**. Default = keep them usable (safety). Even in full-lock, the **native emergency dialer stays reachable** (Android allows emergency calls from any lock; we never gate that). |

## A3. The cage = layered (components → files)
| Layer | What it blocks | Where |
|---|---|---|
| **App-lock** (parent face to open Shield) | Child reaching *any* in-app control | `lock_own_app` (exists) → enforce + default-on in Child mode; gate `MainActivity`/`AppLockGate` |
| **Settings-lock** (parent face for system Settings) | Child reaching Device Admin / App-info → force-stop/uninstall | `lock_device_settings` (exists); ensure App-info pages are covered, not just the Settings root |
| **Device Admin** | Uninstall | `AdminReceiver` (exists); make on-by-default in Child setup |
| **Action gates** | Unpair / rotate / toggle-off / role-change | new parent-face check in `ui/parent` + Settings actions |
| **Role + defaults** | Wrong-device behavior | `device_role` in `DataStoreManager`; Child mode flips the above defaults on |

## A4. Configurable remote-lock strictness (Phone & Messages)
- Extend the **LOCK_NOW** command with a strictness flag (reuse `RemoteCommand.arg` as a bitmask, e.g. `0`=
  normal, `1`=also block allow-listed Phone/SMS). No schema break — `arg` already exists.
- **Parent UI:** a toggle on the control card — *"Also block phone & messages (full lock)"* — next to "Lock now".
- **Child enforcement:** while a *full* remote lock is active, the service's allow-list check is bypassed for
  Phone/SMS too (the `isCriticalSideDoor`/allow-listed path is overridden by `remoteLockActive && fullLock`).
- **Safety guardrail:** never gate the **platform emergency dialer** (emergency calls work from the OS lock
  regardless). Show the parent a one-time warning that full-lock blocks normal calls/texts.

## A5. Build phases (each compiles + is testable)
| Phase | Scope | Verify |
|---|---|---|
| **A1** | `device_role` + Child-mode defaults (app-lock + settings-lock + admin on); gate the whole UI behind the parent face when role=Child | ✅ **built + device-verified (Run 2):** the root now branches on `remoteRole` — a **child** device gets `ChildDeviceRoot` (a locked-down **"This device is managed"** dashboard; the full `MainScreen` is revealed only after a **fresh parent face** via `AppFaceGate`, and that access **resets on app-background** so a child can't inherit the parent's unlock). Parent/unmanaged devices keep the exact `AppLockGate { MainScreen() }` path (zero regression, confirmed on-device). This closes the Face-reenrollment + Tamper-disable + share/control/unpair holes in one move (all behind the fresh face). Fail-open: no parent face → management open (parent can't lock themselves out). One-tap "Lock down this device" card still drives app-lock + settings-lock. **Child dashboard (built + device-verified):** shows **Time-left today** (exact enforcement formula `limit + extensions − used`), used/limit bar, allowed-apps count, "Ask for more time" (informational — only a parent grants), and the "Parent settings 🔒" entry. **Setup coupling (built):** "Set up as child" is disabled until **Kid Mode is on** (`ownerType=="kid"`) — a device only becomes a managed child once it's enforcing a budget. Gaps 3 (Tamper-guard) + 5 (Stats leak) are **subsumed** — the child can't reach Tamper/Stats without the fresh parent face. |
| **A2** | Action-level parent-face gates for unpair / rotate / toggle-off / role change (A-D5) | ✅ **built + device-verified (Run 2):** a `guard` in the child pane re-runs a fresh face check (reuses `AppFaceGate`) before the *weakening* actions — unpair, disable share/control, disable lockdown, rotate key (enabling stays direct; Cancel aborts). On-device: tapping **Share-off** popped the face gate with a Cancel and **left share ON** (action blocked without the face); a matching face then completed it. Active when a face is enrolled. Other Settings sections (Kid Mode/Protect/Tamper toggles) not yet wrapped. |
| **A3** | Child-setup **wizard** + **cage-status checklist** | 🟢 **built (checklist):** `CageChecklist` in the child pane shows the live state of all 4 protections — parent face / app-lock / settings-lock / **Device Admin** (read straight from `TamperProtection`, refreshed on resume) — with a "**N of 4 active**" header, a per-row fix hint, an inline **Enable** button that launches the add-device-admin flow, and the force-stop/Safe-Mode honesty note. VM exposes `appLocked`/`settingsLocked`. The one-tap "Lock down" toggle (A1) is the "seal in one flow" path. Compiles, 128 tests green; device-test pending. (A full multi-step wizard not needed — the checklist + one-tap covers it.) |
| **A4** | **Lock-strictness** option (A4): command flag + parent toggle + child enforcement + emergency-dialer guardrail | ✅ **built + device-verified (Run 2):** parent toggle ("Also block phone & messages" → "Full lock — only emergency calls work"); command flag `arg=1` delivered E2E; honest "Locked by parent" overlay; remote UNLOCK clears it; idempotent. **Full-lock Phone block fixed on-device** — the persistent-lock manager now holds the overlay over the dialer (was thrashing; see TEST_RUN_LOG Run 2). ⚠ residual minor overlay-recreate churn (battery nit, deferred); emergency-dialer-under-full-lock not live-tested (won't dial emergency services). |
| **A5** | UX honesty: "Strict mode unavailable / available" banner; document force-stop + Safe Mode gaps | 🟢 **built:** `StrictModeBanner` in the child pane reads the live Device-Owner state (`TamperProtection.isDeviceOwner`, refreshed on resume) and shows **"Standard protection"** — disclosing that force-stop + Safe Mode can still pause protection without Device-Owner setup — or flips to **"Strict mode active"** (force-stop/Safe-Mode blocked) once provisioned. Honest by default, Play-policy aligned. Compiles, 128 tests green. *(Also fixed the flaky `QrCodecTest`: single-frame `HybridBinarizer` decode of a random payload was a coin flip; pinned to a fixed valid pairing — deterministic, still exercises the full pipe.)* |
| **A6** *(future, Pro)* | **Device-Owner** provisioning → true kiosk (blocks force-stop, Safe Mode, factory bypass) | Unbreakable lock on a dedicated kid device |

## A6. Honest limitations (state them in-app)
- **Force-stop:** reachable via App-info; mitigated by settings-lock, **not** eliminated without Device Owner.
- **Safe Mode:** third-party services are suspended → protection off until normal boot. Device-Owner only.
- **Re-lock latency:** the overlay re-asserts on a ~1s poll, so there's a brief usable window (it is a strong
  deterrent, not a perfect cage — see the remote-lock notes).
- These are the **Device-Owner ceiling**; A6 (Pro) is the only full fix.

---

# PART B — Richer Remote Report

## B0. Goal
Make the parent's report far more expressive — **while staying strictly aggregate** (totals & patterns, never
content). More insight, same privacy moat. If it ever needs per-message/per-site detail, **stop** — that's the
line that turns it into the spyware we're differentiating against.

## B1. New aggregate fields (all privacy-safe)
Extend `KidReport` (and the codec, bumping `schemaVersion`; decode tolerates missing fields for back-compat):
| Field | Meaning | Source |
|---|---|---|
| `hourly: List<Long>` (24) | usage by hour-of-day → **time-of-day pattern + night usage** | `UsageMeasure` bucketed by hour |
| `categories: List<AppStat>` | **social / games / education / other** totals | existing `AppCategorizer` / `AppCategory` |
| `weeks: List<Long>` | 4-week daily-average **trend** + month-to-date | `StatsRepository.weeklyDailyAverages` |
| `sessions: Int`, `longestMs: Long` | **pickups / longest single session** | session aggregation |
| `extensionsCount: Int` | times the budget was extended / overridden | extension history |
| (have) `week`, `dailyAvgMs`, `topApps`, `budgetHitDays` | unchanged | — |

## B2. Architecture (reuse what exists)
- `remote/RemoteReportPayload.kt` + `RemoteReportCodec` — add fields, **bump schema**, keep round-trip tests.
- `remote/RemoteReportGatherer.kt` — compute the new aggregates (reuse `StatsRepository` + `AppCategorizer`).
- `ui/parent/ParentReportScreen.kt` — new cards: **time-of-day chart**, **category donut** (reuse `Donut`),
  **trend line** (reuse `WeeklyTrendCard`/`FourWeekBars`), **session stats** row.

## B3. Build phases
| Phase | Scope | Verify |
|---|---|---|
| **B1** | Extend payload + codec (pure) + tests; back-compat decode of old docs | ✅ **done** — `KidReport` + `RemoteReportCodec` carry hourly/categories/weeks/sessions/longestMs (default-empty, positional-tolerant); rich round-trip + old-6-field back-compat + fuzz all green |
| **B2** | Gatherer computes hourly / categories / trend / sessions | ✅ **done:** gatherer fills `weeks`, `categories`, `hourly` (24 buckets), `sessions`+`longestMs`. New pure `UsageMeasure.hourlyForegroundMs` / `sessionStats` (+ `eventsIn`) and `StatsRepository.hourlyControlledMs` / `sessionStatsForDays` / `categoryTotals`. ⚠ hourly+sessions are device-wide → filled for single-kid; multi-kid gets trend+categories only (per-kid hour/session-intersection deferred). Device-test pending |
| **B3** | Parent UI cards (time-of-day, categories, trend, sessions) | ✅ **done + device-verified (Run 2):** `ParentReportScreen` renders a 4-week trend chart, "By category" list, a 24-bar **time-of-day** chart (`HourlyBars`), and a **Sessions · longest** row. On-device E2E: child "Sync now" → parent "Refresh" decrypted + rendered all four new cards with real data ("4-week trend", "By category", "Time of day", "Sessions (pickups) 2062 · longest 34m"). ⚠ session count (2062/wk) looks high — `sessionStats` may over-count brief foreground blips; worth a sanity-check. |
| **B4** *(later)* | Insights ("most usage 8–10pm", "60% games") — plain-language summary | Honest, aggregate-only |

## B4. Privacy boundary (the hard line)
Allowed: totals, hour-of-day buckets, category splits, counts, trends. **Never:** which message/site/contact,
exact open timestamps of specific content, location, keystrokes. Crossing it forfeits the moat.

---

## Sequencing recommendation
**A1 → A2 → A4 → A3** first (the cage + the lock-strictness you asked for) — that's what makes remote control
*trustworthy*. Then **B1 → B3** for a richer report. A6 (Device Owner) and B4 (insights) are later.

## Cross-references
- `PARENT_REMOTE_REPORT_PLAN.md` / `PARENT_REMOTE_CONTROL_PLAN.md` — the features this hardens + enriches.
- `PREMIUM_FEATURE_PLAN.md` — D11 (face = authority), D12 (Device Owner = future Pro), tamper model.
- `PLAYSTORE_RELEASE_PLAN.md` — Device Admin / deceptive-overlay / stalkerware policy (the cage must stay disclosed + honest).
- `ACCEPTANCE_AND_TEST_CASES.md` — add cage + lock-strictness cases (child can't reach controls; full-lock blocks Phone/SMS but not emergency).
