# Shield — Pre-Rollout Device Test Checklist

Run on the real pair: **Motorola = parent**, **Realme (ColorOS) = child**. Child logcat is suppressed on
Realme — use `diag.log` (pull command below) instead of logcat for the freeze/overlay checks.

**Build:** debug (billing/RC test-friendly; `run-as` works). Confirm `promo_active=true` in Firebase for a
free rollout dry-run, or flip per the case.

Legend: **P0** = safety/hard blocker (must pass to ship) · **P1** = core feature · **P2** = polish.
Result: ✅ pass · ❌ fail · ⚠️ partial · — not run.

```
Pull child diagnostics:
adb -s <realme-serial> shell "run-as com.appsecure.shield cat files/diag.log" > diag.log
Resolve serial:
adb devices | awk -F'\t' '$2=="device"{print $1}'
```

---

## A. Frozen-screen / overlay integrity (P0 — the rollout gate)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| A1 | **Multi-day no-freeze** | Child phone runs normally 2–3 days (incl. overnight). | No frozen screen / dead taps at any point. | |
| A2 | **`ovLive` never sticks** | After A1, pull `diag.log`. Inspect `ovLive` column. | Returns to 0 after every lock; **never stuck ≥1** across the whole run. | |
| A3 | **No leak trend** | In `diag.log`, scan `heapMB`, `natMB`, `thr`. | Flat/bounded over days (no steady climb). | |
| A4 | **Loop alive** | In `diag.log`, `poll` increments every line; `mainStall_ms` stays low. | `poll` never flat for long; `mainStall` mostly < ~200ms. | |
| A5 | **Overlay teardown** | Trigger a budget lock, then clear it (grant time). Repeat 10× fast. | Overlay always disappears; `ovLive` back to 0 each time; no stacking. | |
| A6 | **Reboot recovery** | Reboot child. | Service restarts, monitoring resumes, `diag.log` gets a fresh `up_s=0` line. | |

## B. Emergency & safety (P0 — must never fail)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| B1 | **Emergency dialer under budget lock** | Budget exhausted → lock up → open dialer / emergency call. | Dialer reachable; emergency call possible. | |
| B2 | **Phone/SMS under default remote lock** | Parent sends default (non-full) remote lock. | Phone & Messages stay usable; other apps locked. | |
| B3 | **Full remote lock** | Parent sends FULL lock. | Everything locked except platform emergency dialer. | |
| B4 | **Night lock window** | Set clock into 22:00–07:00. | Night lock engages; **still dismissable via parent face**; not a dead freeze. | |
| B5 | **Parent-face unlock always works** | On any kid lock, tap unlock → parent face. | Camera opens, recognizes parent, unlocks. No dead taps on the overlay. | |

## C. Budget & grant-extra-time (P1 — recently fixed, must verify)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| C1 | **Single-kid budget lock** | Multi-kid OFF. Exhaust budget. Open app. | Locks at limit. | |
| C2 | **Single-kid remote grant** | Parent grants extra time remotely. | Child unlocks; reflects new time; opening app works. | |
| C3 | **Single-kid local grant** | Parent-face into child app → grant extra time. | Child unlocks after grant. | |
| C4 | **Multi-kid remote grant** *(fixed)* | Multi-kid ON, kid over budget. Parent grants extra time remotely. | **Locked kid unlocks**; stays unlocked across a poll cycle (no re-lock). | |
| C5 | **Multi-kid local grant** *(fixed)* | Multi-kid ON, in-app grant to the active kid. | Locked kid unlocks; persists. | |
| C6 | **Grant not clobbered** | After C4/C5, wait 2–3 min (several polls). | Kid stays unlocked (poll doesn't wipe the grant). | |
| C7 | **Grant resets next day** | Grant time, roll clock past 07:00. | Per-kid + global extension zeroed; budget enforces fresh. | |

## D. Multi-kid daily limit & identify (P1)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| D1 | **Per-kid limit raise** *(fixed)* | Multi-kid ON. Settings → Multiple kids → raise Kid A's limit above used. | Kid A frees; Kid B unaffected. | |
| D2 | **Global slider redirect** *(fixed)* | Multi-kid ON. Kid Mode → daily budget card. | Shows "Set each kid's limit" → tapping opens Multiple Kids (no dead slider). | |
| D3 | **Identify picks right kid** | Both kids enrolled. Each opens a controlled app. | Correct kid identified; their budget/allow-list applied. | |
| D4 | **Per-kid per-app cap** | Set a per-app cap for Kid A. Kid A hits it. | That app locks for Kid A only; allowed apps still open. | |
| D5 | **Stale identity re-scan** | Idle > grace, open controlled app. | Identify camera runs before enforcing; no wrong-kid attribution. | |
| D6 | **Half-enrolled fallback** | Multi-kid ON but only 1 face enrolled. | Falls back to single-kid; UI prompts to finish enrolment. | |

## E. Locking edge cases on ColorOS (P1)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| E1 | **Recents re-open of locked app** | Lock an app, background it, re-open from Recents. | Re-locks (watchdog re-asserts). | |
| E2 | **Minimize → audio keeps playing** | YouTube-style app, hit budget, minimize. | Audio suppressed (audio focus grabbed). | |
| E3 | **App-switch lock** | Rapidly switch between two locked apps. | Each shows lock; no overlay stacking; `ovLive` ≤ 1. | |
| E4 | **Settings side-door** | Try to reach uninstall / usage-access while locked. | Tamper-critical pages challenge (if admin+tamper on). | |
| E5 | **Overlay permission revoked** | Revoke "Draw over other apps" at runtime. | Falls back to LockActivity; no crash. | |

## F. Onboarding & first run (P1)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| F1 | **First-run permission gate** | Fresh install → open. | Blocked on "Get Started" until face + camera + usage + overlay + notifications granted. | |
| F2 | **Face enrolment** | Enrol parent face. | Stored; stamps model version; unlock works. | |

## G. Entitlements / paywall (P1 for free rollout; P0 for paid)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| G1 | **Promo = free** | `promo_active=true`. | All premium features open; no lock cards; paywall shows "free for now". | |
| G2 | **Promo flip reflects (debug)** | Flip `promo_active=false`, relaunch. | Gates lock instantly (debug 0-throttle). Premium features show lock cards. | |
| G3 | **Gate → paywall on tap** | Locked feature (Stats, Multiple kids, presets C, remote report). | Tap opens paywall from any tab. | |
| G4 | **Revoke-on-lapse** | With paid mode, lapse a feature that was ON. | Toggle switches OFF; theme reverts; sharing stops. | |
| G5 | **Restore feedback** | Paywall → Restore (no purchase). | Snackbar: "No previous purchases…" (not silent). | |

## H. Remote report / control E2E (P1)

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| H1 | **Pair parent↔child** | Complete pairing. | Paired; child opt-in on. | |
| H2 | **Report sync** | Child uses apps → parent report. | Encrypted totals + app names appear on parent. | |
| H3 | **Consent disclosure** | Enable Share on child. | App-name-sharing consent popup shown before sync. | |
| H4 | **Remote commands** | Parent: lock / grant / set limit / set allowed. | Applied on child (E2E-decrypted); ignored if opt-out. | |
| H5 | **Firestore rules** | With rules published. | Reads/writes only within the pair; unauthorized denied. | |

---

## Ship gate (all must be true for **free** production rollout)
- [ ] A1–A6 clean (multi-day, no freeze, `ovLive` never stuck)
- [ ] B1–B5 pass (emergency + parent-face never dead)
- [ ] C1–C7, D1–D6 pass (budget/grant/multi-kid correct)
- [ ] Data Safety form updated (declares installed-app-name list shared)
- [ ] Privacy Policy + Terms URLs hosted & set
- [ ] Firestore security rules published
- [ ] E, F, G(1–3), H pass

## Additional gate for **paid** rollout
- [ ] SFace license swap done + device-validated (migration Phase 2c/3/4)
- [ ] BillDesk PA-CB verification complete
- [ ] Play Console `premium` product + base plans + license testers live
- [ ] G4–G5 pass; real test purchase + restore verified from a Play track install
