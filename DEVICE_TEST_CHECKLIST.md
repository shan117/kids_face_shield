# Device test checklist — what unit tests cannot catch

282 unit tests pass. None of them can tell you whether a WebView renders, whether Google honours a URL
parameter, or whether a page can escape the filtered browser.

**Ordered by how a failure announces itself, not by feature.** A test that fails loudly costs you ten
minutes. A test that fails silently ships, and a parent finds out instead of you.

Devices: **P** = parent phone, **A** / **B** = child phones (Kid Mode on, paired).

---

## Part 1 — SILENT failures. Do these first.

Every one of these looks like success when it is broken.

### 1.1 ⚠️ SafeSearch from Google's own search box — the single most important test

The in-app browser forces SafeSearch two ways: searches typed into **our** address bar get
`&safe=active` appended, and the home page is `google.com/webhp?safe=active` so searches typed into
**Google's** box inherit it.

The second is a bet. Google loads results without a full page navigation, so
`shouldOverrideUrlLoading` never fires and `enforceSafeSearch` cannot rescue it. If `webhp` does not
carry the parameter, explicit results appear and **nothing anywhere indicates a problem.**

```
1. Open Kids Shield Browser from its icon (land on Google)
2. Type an explicit search term into GOOGLE'S OWN BOX — not the address bar
3. Search
```

| | |
|---|---|
| **PASS** | Results filtered. URL contains `safe=active`. Scrolling down shows no explicit images |
| **FAIL** | Explicit results. URL has lost `safe=active` |

**If it fails:** the honest fallback is to block `google.com` in the browser and route searches only
through our own address bar, or switch the default engine to one where SafeSearch is a sticky cookie
rather than a query parameter. Do not leave it as-is and hope.

Repeat with **Images** — it is the worst case and the likeliest to leak.

---

### 1.2 ⚠️ The `intent://` escape

A page can hand Android `intent://…#Intent;package=com.android.chrome;end` and walk the child straight
out of the filtered browser into an unfiltered one. `DomainBlocklist.isWebScheme` refuses every
non-http(s) scheme — if that ever stops working, the filter still *looks* perfect right up until a child
finds one such link.

```
1. In the Kids Shield Browser address bar, enter:
   intent://example.com#Intent;scheme=http;package=com.android.chrome;end
2. Then try:  market://details?id=com.android.chrome
```

| | |
|---|---|
| **PASS** | Block page: *"This link tried to open another app, which isn't allowed here."* Chrome does not open |
| **FAIL** | Chrome (or Play Store) opens |

A filter with a working `intent://` escape is theatre. Treat a failure here as blocking.

---

### 1.3 ⚠️ Web filtering under Multiple Kids

`shouldLockForKidMode` returns early in the multi-kid branch. The browser-blocking check had to be
repeated there — without it the whole feature is **silently inert** once Multiple Kids is active, with no
error and no visible difference.

```
1. On a child device: enable Multiple Kids, enrol two faces
2. Turn web filtering on
3. Open Chrome
```

| | |
|---|---|
| **PASS** | Chrome locks |
| **FAIL** | Chrome opens normally — the feature does not exist in this configuration |

---

### 1.4 ⚠️ Entitlement grant reaching the child

A parent pays on their phone; every premium feature runs on the child's. If the grant does not arrive,
the parent has paid for features that quietly do not run.

Needs Play license testing — see `LICENSE_TESTING.md` §2.

```
1. Remote Config: promo_active=false, paywall_enabled=true. Publish. Force-stop and reopen
2. Buy the subscription ON P ONLY (license tester = no charge)
3. P:  adb logcat -s AppLock | grep -i grant   → "premium grants renewed for N device(s)"
4. Firestore → a `grants` collection appears, one doc per child
5. A:  adb logcat -s AppLock | grep -i grant   → "premium grant adopted, valid until …"
6. ON A, confirm a premium feature actually enforces — per-app limits or schedules
```

| | |
|---|---|
| **PASS** | Step 6 works with no purchase on A |
| **FAIL** | Anything stops before step 6 |

**Step 6 is the whole test.** Revert `promo_active=true` afterwards.

---

### 1.5 ⚠️ Lapse banners for enforcement-only features

`APP_LOCK`, `KID_BUDGET`, `NIGHT_LOCK` and `TAMPER` are enforced purely by service flags. If one locks,
the service stops acting while every switch still reads "on". The `LapseBanner` is the only thing that
says so — and if it fails to render, the failure is invisible.

```
1. Remote Config: promo_active=false and
   feature_tiers_json = {"KID_BUDGET":"premium","APP_LOCK":"premium","TAMPER":"premium"}
2. Publish, force-stop, reopen
```

| | |
|---|---|
| **PASS** | Red banner on Kid Mode, on Protect, and on Tamper Protection (if a switch is on) |
| **FAIL** | Screens look normal while enforcement has stopped |

Revert `feature_tiers_json` to `{}` afterwards.

---

### 1.6 ⚠️ Private DNS "on but not filtering"

Private DNS being *active* does not mean it is *filtering* — `dns.google` encrypts and answers
everything. Claiming "filtering is on" there would be a false assurance about a child's safety.

```
1. On A, set Private DNS hostname to: dns.google
2. P or A → Settings → Web filtering
```

| | |
|---|---|
| **PASS** | Amber/red: *"Private DNS is on, but it isn't filtering"* |
| **FAIL** | Green "Web filtering is on" |

Then set it to `family-filter-dns.cleanbrowsing.org` and confirm it flips to the success state.

---

## Part 2 — LOUD failures. Quicker, and you will notice.

### 2.1 Browser basics

| Test | Pass |
|---|---|
| Two launcher icons, different artwork | **Kids Shield** (teal) and **Kids Shield Browser** (cream globe) |
| Each icon opens its own screen | The task-affinity fix. Force-stop or reboot first — old task groupings persist |
| Browser opens on Google, not blank | The load-path hardening |
| Phone back walks history, then exits | Not "quit on first press" |
| X in the address bar clears text | Does not close the browser |
| Link tapped in WhatsApp while browser open | Navigates to it, not the previous page (`onNewIntent`) |

### 2.2 Blocking and overrides

| Test | Pass |
|---|---|
| `pornhub.com` | Block page naming *"Adult content"* |
| `proxysite.com` | Blocked — the bypass category |
| `reddit.com` with Social **off** | Loads |
| `reddit.com` with Social **on** | Blocked |
| Allow `reddit.com`, Social still on | Loads — allow beats category |
| Chrome with the master switch on | Locks |
| Chrome with it off | Opens normally |

### 2.3 Location

| Test | Pass |
|---|---|
| Request with sharing on | Address or coordinates, with age and accuracy |
| Child gets a notification | *"Location shared"* |
| Sharing off on the child | *"Location sharing is off on their phone"* — not a spinner |
| Child's location services off | *"Location is turned off on their phone"* |
| Child offline / powered off | *"Couldn't reach their phone"* — distinct from "couldn't get a fix" |
| **Open in Maps** | Drops a pin |
| History behind the gate | Paywall card when `LOCATION_HISTORY` is premium |
| Rotate the child's key | *"X needs reconnecting"* + re-scan; afterwards **one** row, name kept |

### 2.4 Lock overlay

| Test | Pass |
|---|---|
| Budget exhausted | *"Your daily usage limit is over"* + **Allowed / Used today** figures |
| After 10 PM | *"Sleep time"*, calm blue, no figures |
| Phone / Messages buttons | Present on both, and they open |

### 2.5 Screen-time exclusions

| Test | Pass |
|---|---|
| Settings → What counts as screen time | Full list with usage, not just the top 5 |
| On a kid device | **Parent only** card, face scan required |
| No parent face enrolled on a kid device | Blocked with instructions — fails closed |
| Switch an app off | Leaves charts and stops using the budget |

---

## Part 3 — Verified already

Do not re-test unless something changes.

- Multi-device pairing, two children linked
- Anonymous auth, `pairings/` membership with two uids
- Strict `firestore.rules.phase7` live, app working under them
- A second child paired **with the strict rules already published** — the production order

---

## If something fails

```bash
adb -s <SERIAL> logcat -s AppLock ShieldAuth ShieldLocation ShieldWebFilter ShieldConfig
```

Reverting the rules is instant and needs no app change — paste the permissive `firestore.rules` and
publish.

**Do not test on the Realme child device where logs matter** — its app logcat is suppressed, so failures
are invisible there. Use it as the paired child and read logs on the other phone.
