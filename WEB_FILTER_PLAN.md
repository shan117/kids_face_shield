# Web / content filtering plan

Phase 0 implemented. Phases 1–3 designed, not started.

---

## The four approaches, and why they're ordered this way

| Phase | Mechanism | Play risk | Status |
|---|---|---|---|
| **0** | Android **Private DNS** + the existing Settings lock | None | **Done** |
| **1** | Block every browser; ship one filtered in-app browser | None — reuses app locking | Not started |
| **2** | Category blocklists, `Feature.WEB_FILTER`, per-kid, remote control | None | Not started |
| **3** | Local `VpnService` DNS filtering | Declaration + video + disclosure | Deferred, possibly permanently |

### What competitors do

Qustodio filters only in Chrome, Firefox and Edge, and **blocks unsupported browsers outright**
(Brave, Opera, Tor); filtering runs through a local on-device VPN. Bark uses the same local-VPN
approach, and reviewers note a child can disable it by switching the VPN off.

The insight that shapes this plan: **the real enforcement is browser blocking, not the VPN** — and app
blocking is already this app's strongest, most battle-tested capability. The expensive half of
Phase 1 is already built.

### Why Phase 3 is last

`VpnService` is explicitly permitted for parental control, but requires a Play Console declaration, a
≤90-second video of both the VPN usage and the consent flow, and a prominent in-app disclosure with
affirmative consent. In exchange it adds only one thing over Phases 0–2: filtering inside **webviews
belonging to other apps**.

That margin is thin here, because this app can already block those apps entirely. Meanwhile it costs a
permanent status-bar VPN icon, a toggle the child can flip, a conflict with any family VPN (only one
VpnService runs at a time), and hand-parsing DNS wire format.

**AccessibilityService URL reading is rejected outright.** It gives the broadest coverage, but Google
restricts non-accessibility use and removes abusers; this app already leans on a foreground service,
an overlay and device admin, and adding accessibility would raise removal risk for coverage largely
obtainable otherwise.

---

## Phase 0 — as implemented

### What it is

Android's **Private DNS** points the whole device at a DNS resolver of the parent's choosing. Family
resolvers refuse to answer for adult/malware domains, so setting one filters **every app on the phone**
with no filtering code, no VPN, and no battery cost.

### The hard constraint

**The app cannot set it.** `Settings.Global.private_dns_*` needs `WRITE_SECURE_SETTINGS` (privileged),
and `DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost()` is **Device Owner only** — which
requires provisioning a factory-reset device and is unreachable for a Play-installed consumer app.
(This is why Bark sells pre-provisioned hardware.)

So Phase 0 does the three things that *are* possible: **guide, verify, and notice when it stops.**

### Providers offered

| Provider | Hostname | Notes |
|---|---|---|
| **CleanBrowsing Family** (default) | `family-filter-dns.cleanbrowsing.org` | Adult + **blocks the proxy/VPN sites used to bypass filters**. Also blocks mixed-content sites like Reddit. |
| AdGuard Family | `family.adguard-dns.com` | Adult + ads/trackers. Reddit stays open. |
| Cloudflare for Families | `family.cloudflare-dns.com` | Malware + adult. Most permissive, fastest. |

CleanBrowsing is the default specifically because it blocks the escape hatches. `PrivateDnsStatesTest`
asserts that property of the default, so a future change to it gets reconsidered rather than slipped in.

### Files

| File | Role |
|---|---|
| `webfilter/PrivateDns.kt` | `DnsFilterProvider`, `PrivateDnsState`, pure `PrivateDnsStates` mapping |
| `webfilter/PrivateDnsMonitor.kt` | Reads (never writes) Private DNS; live `Flow` via `NetworkCallback` |
| `webfilter/WebFilterViewModel.kt` | Status + whether Settings are locked |
| `webfilter/WebFilterSection.kt` | Guided setup: status card, provider picker, copy hostname, open settings, limits |
| `MainActivity` | `SettingsRoute.WebFilter` behind `ParentGate`, plus a Settings hub row |
| `AppLockForegroundService` | Watches for filtering→off and notifies |

### The state that matters most

`PrivateDnsState.ActiveUnfiltered` exists because **Private DNS being *on* does not mean filtering is
on.** A resolver like `dns.google` is encrypted and answers every query honestly; Android's
"Automatic" mode likewise encrypts without filtering. Reporting either as "filtering is active" would
be a false assurance about a child's safety — worse than saying nothing. It renders as a warning, not
a success.

### Tamper alert

The service watches `PrivateDnsMonitor.state` and posts **"Web filtering turned off"** on a
filtering→not-filtering transition. Two guards: kid-owned devices only, and only once filtering has
actually been seen working — so it reports a *change*, never nags a parent who hasn't set it up.
Latches so a network blip produces one notice, not a stream.

### Privacy disclosure

Turning this on routes **every domain lookup on the phone to an outside company**. That is a real
privacy decision and not the app's to make quietly on a family's behalf — the same standard that makes
the child's phone announce when its location is shared.

`PrivacyDisclosure` sits between choosing a provider and copying the hostname, so it cannot be
scrolled past, and names the selected provider:

- **They see** the site names this phone opens
- **They don't see** pages, messages, or what is searched (a Google search is `google.com` at the DNS
  layer; the query is inside TLS)
- **Context:** the mobile network already sees this list today, unencrypted. This moves it to the
  chosen provider and encrypts it in transit. Their privacy policy governs retention.

Also a Play **Data Safety** consideration: the app doesn't collect this, but it directs it to a named
third party as part of a feature.

This is a further argument for Phase 1 — an on-device browser filtering against a shipped list sends
**nothing to anyone**, so it is strictly better on privacy as well as granularity.

### Honest limits, stated in-app

- Blocks whole sites, not pages or searches
- No report of what was blocked — the page just fails; the app learns nothing
- **Chrome's own "Use secure DNS" lives inside Chrome**, so the Settings lock doesn't cover it
- A determined teenager gets around it

That third gap is precisely what Phase 1 closes: block Chrome, ship your own browser, and there is no
Secure DNS toggle to find.

### Verification

- Build green; **221 tests, 0 failures** (209 before).
- 13 new tests on the pure state mapping, including lookalike-hostname rejection and that only
  `ActiveFiltered` ever reports `isFiltering`.
- `git diff` vs HEAD on `AllowedApps`, `RemoteReportSync`, `RemoteReportGatherer` and `location/` →
  **empty**. Service changes are additive: one injected monitor, one collector, one notification.
- `ACCESS_NETWORK_STATE` declared explicitly rather than inherited from Firebase's manifest merge, so
  the check can't break silently if a dependency changes.
- Detection needs API 29+; on 24–28 the card shows `Unsupported` and the instructions still work.

**Not device-tested.**

---

## Phases 1 + 2 — as implemented

Build green. **251 tests, 0 failures** (221 after Phase 0).

### Files

| File | Role |
|---|---|
| `webfilter/WebFilterCategory.kt` | Categories, `FilterDecision`, pure `DomainBlocklist`, `WebBlockCountCodec` |
| `webfilter/WebFilterLists.kt` | Seed domain lists per category |
| `webfilter/BrowserApps.kt` | Resolves installed browsers from the system, cached |
| `webfilter/ShieldBrowserActivity.kt` | The filtered browser, block page, WebView hardening |
| `webfilter/UrlNormalizer.kt` | Typed-text → URL, SafeSearch rewriting |
| `webfilter/ShieldBrowserViewModel.kt` | Live rules for the browser |
| `webfilter/WebFilterViewModel.kt` + `WebFilterSection.kt` | Parent UI, both layers |

### The two rules everything rests on

**Suffix matching is label-aware.** A naive `endsWith` makes `notexample.com` match `example.com` and
`example.com.evil.net` match too — a filter that looks like it works and doesn't. Tested explicitly.

**Precedence is allow > parent-block > category.** Allow-over-block is deliberate: the failure mode of
the other order is a parent adding an exception, seeing nothing change, and concluding the app is
broken.

### The escape hatches, which matter more than the filtering

A page can hand Android `intent://…#Intent;package=com.android.chrome;end` and walk the child straight
out of the filtered browser into an unfiltered one. **Every non-http(s) scheme is refused** and never
passed to the system. Also closed: multiple windows, `file://` access, long-press "Open in browser",
and downloads. A filter with a working `intent://` escape is theatre, so that one has its own test.

### Enforcement

One branch in `shouldLockForKidMode`, **after** the always-allowed bypass so emergency comms are never
collateral damage, gated on both the entitlement and the parent's own switch. `BrowserApps` excludes
our own package, so the app cannot lock itself out of the browser it provides.

Repeated in the Multiple-kids branch — that path returns early, so without it the whole feature was
silently inert once multi-kid was active. Found in the post-implementation audit, not by a test.

### Link handling

The browser registers the `http`/`https` intent filter, so taps in WhatsApp and games route here
instead of dead-ending once other browsers are blocked. The child can even set it as default.

### Phase 2

- `Feature.WEB_FILTER` (default-free) + `FeatureCopy`; the paywall list picks it up automatically
- Per-category switches, with counts of what each blocked
- Parent allow/block overrides, mutually exclusive by construction
- `CommandType.SET_WEB_CATEGORIES` — `arg` enables, `payload` is the category list. `CommandConsent`
  routes it correctly with no change (needs remote control, not location consent), and
  `CommandConsentTest` already covers it by iterating every command type.
- **Counts — never domains — in the weekly report.** `KidReport.webBlocks` carries category names and
  integers. A list of sites a child tried to reach is content, and would turn a safety report into a
  browsing log. A test asserts the encoded form contains no dots, so a hostname cannot be smuggled in
  later.

### Deferred, deliberately

- **Per-kid categories.** Designed (a `kid_web_categories` key, following the `kid_per_app_limits`
  precedent of staying out of `KidProfile`) but not built. Least valuable part, and a half-built
  version is worse than none.
- **Remote Config blocklist override.** The seed lists ship in the APK, so additions need a release.
  `web_blocklist_json` would fix that, reusing the feature-tier mechanism.

### Honest limits

- The seed lists are a **seed**: ~110 domains, not the hundreds of thousands a filtering service
  carries. Parent overrides are the escape valve.
- YouTube restricted mode cannot be forced by URL rewriting (it is cookie/header based). Left alone
  rather than faked; the real control for YouTube is blocking or time-limiting the app.
- **WebView is not Chrome.** This is the product risk, not a technical one.

**Not device-tested.**

---

## Phase 1 — original design notes

Block all browsers via the existing `protectedApps` enforcement; ship a WebView browser that inspects
every URL in `shouldOverrideUrlLoading` before loading.

Gives what no VPN-based competitor can: **full URL and path granularity**, plus visibility into what
was searched. `reddit.com/r/specific` becomes blockable while the rest stays open.

**The risk is product, not engineering:** WebView is not Chrome, some sites misbehave, and a
frustrating browser gets worked around socially long before it gets worked around technically.
Prototype it and put it in front of a real child before committing to Phase 2.
