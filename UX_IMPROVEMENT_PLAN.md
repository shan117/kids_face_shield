# UX improvement plan

Goal: make the app obvious. Ordered by impact per unit of effort, from a second pass over the real
navigation, screens and copy.

---

## The two findings that reordered everything

### 1. The child's experience is better designed than the parent's

| | Child's first screen | Parent's first screen |
|---|---|---|
| Shows | **"Time left today"**, progress, limit status | **Face enrolment** |
| Status at a glance | yes | no — assemble it from two tabs |

`ChildDashboard` answers "am I OK?" in one glance. The parent — the buyer, and the one deciding whether
to keep paying — has no equivalent. `StatusBanner` only speaks when setup is *incomplete*; when
everything works, nothing says so.

The right screen already exists. It was built for the wrong user.

### 2. "Ask for more time" is a dead end

The child's limit is reached. They tap their only available control and get:

> *"Only a parent can add time. Ask them — they can grant more from 'Parent settings' here, or remotely
> from their own phone."* **[OK]**

A button that promises a request and delivers instructions to go ask in person.

This outranks everything aesthetic, for a reason that is not aesthetic: **a child's frustration is what
gets parental controls uninstalled.** Not technical bypass — social pressure. That dialog lands at the
moment of maximum frustration and tells the child the app cannot help.

Every piece needed already exists: the pairing channel, encrypted documents, the service listener, and
`GRANT_EXTRA_TIME`.

---

## Corrections to the first pass

Two things were criticised wrongly and are withdrawn:

- **Permission copy.** Claimed Android's engineering names leak into the UI. They don't: each pairs the
  system name (what to hunt for on the system screen) with a plain reason — *"Usage Access / Detect
  which app is on screen"*. That is the correct pattern.
- **The app list.** Already has search and category grouping. No change needed.

---

## Priority

| # | Change | Effort | Status |
|---|---|---|---|
| 1 | Make "Ask for more time" actually ask | ~1 day | **implemented** |
| 2 | Parent Home dashboard; Face out of the nav | ~2 days | planned |
| 3 | Group Settings into three sections | ~0.5 day | planned |
| 4 | Split "Remote report"; surface Location | ~0.5 day | planned |
| 5 | Remove the fake Stats switcher | ~1 hour | planned |
| 6 | Ask once: "does your child have their own phone?" | ~1 day | planned |

---

## 1. Make "Ask for more time" actually ask — implemented

```
child taps → picks 15 / 30 / 60 min
           → request stored locally (drives the child's "waiting" state)
           → if paired, published to requests/{pairingId}, encrypted
parent     → service listener → notification
           → card in the report screen → Grant / Dismiss
           → existing extension path applies the minutes
```

Design notes:

- **Local record is the source of truth for the child's own UI**, so the "waiting" state works with no
  network and on an unpaired single-device family.
- **Encrypted under the pairing key** for authenticity, like grants — otherwise anyone holding a pairing
  id could spam a parent with fake requests.
- **Requests expire.** A stale ask from yesterday must not be granted today by a parent clearing
  notifications. 4 hours.
- **One pending request at a time.** Re-asking replaces it rather than queueing, so a frustrated child
  tapping repeatedly cannot flood the parent.
- The child is told the truth: *"Asked — waiting for your parent"*, never a false *"granted"*.

---

## 2. Parent Home dashboard

Replace the `Face` tab with a dashboard. Face enrolment moves into Settings and first-run — it is a
one-time task holding prime navigation space.

```
Aarav's phone                         ● Protected
1h 12m of 2h today             ▓▓▓▓▓▓▓░░░
Filtering on · 4 apps locked · Night lock 10 PM
Located 8 min ago — Park Street              [ Find ]
                                      [ Give 15 min ]
```

Per child when paired; a single card otherwise. `ChildDashboard` is the model.

Also solves setup fragmentation: first-run covers five items, then Kid Mode, pairing, web filtering and
tamper protection are four separate later discoveries. A *"3 of 5 set up"* row on Home turns scattered
into a visible path.

## 3. Group Settings

Nine flat rows in arbitrary order — web filtering, a core feature, sits below Appearance.

```
YOUR CHILD    Kid Mode · Web filtering · Multiple Kids · What counts as screen time
KEEP IT ON    Tamper Protection · Permissions
THIS APP      Appearance · Help
```

Same rows, grouped and reordered.

## 4. Split "Remote report"

That screen holds five things — pairing, the dashboard, remote control, **location**, and three consent
toggles — and is named after one of them. A parent looking for *"where is my child"* would never open
"Remote report".

- **"My child's phone"** — pairing, dashboard, remote controls
- **Location** — its own Settings row and its own Home card

## 5. Remove the fake Stats switcher

A `SegmentedButton` with `onClick = {}`, the inactive half `enabled = false`, and a **padlock icon**. It
looks like a control, does nothing, and implies something is paywalled. Replace with a plain
*"Parent view"* label.

## 6. Ask once whether the child has their own phone

The app serves two products through one UI:

- **A** — child uses the parent's phone: no pairing, everything local
- **B** — child has their own phone: pairing, remote everything

Both see identical Settings. In A, "Remote report" is noise. In B, the **Protect** tab on the parent's
phone locks the *parent's* apps — almost certainly not the intent.

One setup question, then hide the irrelevant half. Removes more confusion than any amount of copy
polish.
