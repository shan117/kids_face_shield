# Kids Shield — Competitor Comparison & Feature-Gap Matrix

Revisit doc. Shield's columns are grounded in the actual codebase. Competitor columns are general
market positioning **as of knowledge cutoff (Jan 2026)** — features change, so **verify current** before
using publicly. Legend: ✅ full · ⚠️ partial/limited · ❌ none.

**Positioning line:** _"The only shared-phone parental control where each child unlocks with their own
face — no accounts, no PINs to guess."_

---

## 1. Where Shield stands out (unique / rare)

| Standout | Why it matters | Who else has it |
|---|---|---|
| **Face-ID enforcement** (on-device ML Kit + TFLite) | Kid can't bypass with a learned PIN; parent unlocks in person with their face | Rare — most use PIN/account |
| **Multiple kids on ONE shared phone** via 1:N face identify | Right kid's budget/allow-list/caps auto-applied on a hand-me-down/shared device | Effectively none mainstream |
| **Per-app face lock** | Any single app gated behind a face check | Rare |
| **No child Google account / age rules needed** | Works for young kids + shared phones; not age-gated | Family Link is account+age gated |
| **Earned-time + free-play sessions** | Positive-reinforcement screen time, not just blocking | ⚠️ few |
| **OEM-resilient overlay + watchdog** | Enforcement holds on ColorOS/MIUI without MDM/device-owner | Varies |
| **On-device face processing** | Face never leaves device; only aggregate/encrypted data syncs | Privacy edge |

## 2. Feature matrix

| Feature | **Kids Shield** | Google Family Link | Qustodio | Bark | Kaspersky Safe Kids |
|---|---|---|---|---|---|
| Face-ID unlock / per-app face lock | ✅ | ❌ | ❌ | ❌ | ❌ |
| Multiple kids on one shared device (face identify) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Works without child account / age gate | ✅ | ❌ | ⚠️ | ⚠️ | ⚠️ |
| Parent unlocks child app in-person (face) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Daily screen-time budget | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| Per-app time limits | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| Night / bedtime lock | ✅ | ✅ | ✅ | ⚠️ | ✅ |
| Allowed-app presets (Phone/SMS/WhatsApp/custom) | ✅ | ⚠️ | ✅ | ❌ | ✅ |
| Earned time / free-play reward sessions | ✅ | ⚠️ | ⚠️ | ❌ | ⚠️ |
| Schedules | ✅ | ✅ | ✅ | ❌ | ✅ |
| Auto-block newly installed apps | ✅ | ⚠️ | ✅ | ⚠️ | ⚠️ |
| Remote control from parent phone (lock/grant/limit) | ✅ (E2E-encrypted) | ✅ | ✅ | ⚠️ | ✅ |
| Remote screen-time report to parent | ✅ (encrypted) | ✅ | ✅ | ✅ | ✅ |
| Tamper protection (block uninstall/settings) | ✅ (device admin) | ✅ | ⚠️ | ⚠️ | ⚠️ |
| Emergency dialer always reachable under lock | ✅ | ✅ | ✅ | ✅ | ✅ |
| On-device privacy (minimal data egress) | ✅ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| **Location tracking** | ❌ | ✅ | ✅ | ⚠️ | ✅ |
| **Web / content filtering** | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Message / social monitoring & alerts** | ❌ | ❌ | ⚠️ | ✅ | ⚠️ |
| **App-install approval (OS-level)** | ⚠️ | ✅ | ⚠️ | ❌ | ⚠️ |
| **OS-level hard device lock** | ⚠️ | ✅ | ⚠️ | ❌ | ⚠️ |
| Cross-platform (iOS/desktop) | ❌ (Android) | ⚠️ | ✅ | ✅ | ✅ |
| Price | Freemium | Free | Paid | Paid | Paid |

## 3. Honest gaps — where competitors beat Shield

| Gap | Who has it | Impact |
|---|---|---|
| **Location tracking** | Family Link, Qustodio, Kaspersky, Life360 | Big buyer expectation; absent in Shield |
| **Web / content filtering** | Family Link (Chrome), Qustodio, Canopy, Bark | No browsing safety story yet |
| **Message/social monitoring** | Bark (core), some others | Different philosophy (access control vs surveillance) — but buyers ask |
| **OS-level device lock + install approval** | Family Link (deep OS integration) | Shield relies on installed app + permissions + overlay |
| **Free + OS-integrated** | Family Link | Hard to out-price "free from Google" |
| **iOS / cross-platform** | Qustodio, Bark, Kaspersky | Shield is Android-only |

**Framing:** Shield is a **sharp niche**, not a full monitoring suite — _face-based access control + multiple
kids on a shared phone_. Best for: shared/hand-me-down phones, younger kids, no-account setups, privacy-conscious
parents who want control without surveillance.

## 4. Roadmap gaps (be honest in the pitch)

Ordered by buyer demand vs effort:

1. **Web/content filtering** — biggest "table-stakes" gap. Options: VPN-based DNS filter or accessibility-based
   URL block. High demand, moderate-high effort, privacy trade-offs.
2. **Location** — Family Link's headline feature. Foreground/periodic location + parent map. Moderate effort;
   raises Data-Safety obligations.
3. **App-install approval** — intercept new installs for parent approval (partial today via auto-block).
4. **iOS** — large market, but iOS restricts this class of enforcement heavily (Screen Time API only). Big lift.
5. **Message/social alerts** — philosophical choice; Shield is access-control, not surveillance. Likely _decline_
   and market that as a privacy positive.

**Positioning honesty:** lead with the unique face + shared-device story; state web-filter/location as
"on the roadmap," don't imply they exist.

---

_Caveats: competitor rows are general and time-sensitive (cutoff Jan 2026) — re-verify before any public/marketing
use. Shield rows reflect the current codebase and may include features still pending device validation
(see ROLLOUT_TEST_CHECKLIST.md)._
