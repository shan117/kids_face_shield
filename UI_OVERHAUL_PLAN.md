# UI overhaul plan

A full-transformation plan, not a polish list. Written after measuring the app rather than reacting
to it. Supersedes `UX_IMPROVEMENT_PLAN.md` items 2-6, which were right but too small to produce the
effect being asked for.

Goal, stated by the owner: *a user opens the app and understands it without reading.*

---

## 1. What the audit measured

| Measure | Value | Where |
|---|---|---|
| Prose blocks of 60+ chars in UI code | **149** | 37 in `MainActivity.kt` alone |
| Permanent nav tabs | 4 | Face, Protect, Settings, Stats |
| Features living *inside* Settings | 9 flat rows, no grouping | `MainActivity.kt:1097` |
| Coach-mark engine | 508 lines + `FirstRunWelcome` + 3 tours | `ui/CoachMarks.kt` |
| Largest UI file | **3051 lines** | `MainActivity.kt` |
| Padlock icon usages | **24**, meaning 8 different things | nav tab, premium, parent gate, night lock, locked app, disabled segment, browser TLS, lapse |
| Names for one product | 4 | "Kids Shield" (30), "Face ID" (10), "App Shield" (3), "Shield" |
| Toggles | 15 `Switch()` | |

The 149 count *understates* it: multi-line concatenated strings don't register in the scan.

---

## 2. Diagnosis: three root causes

Everything visible is downstream of these. Fixing symptoms one at a time will not produce the effect
being asked for.

### Cause 1 — The app is organised around its own build history, not the user's question

Navigation exposes **capabilities** (Face, Protect, Stats) and hides **products** (Kid Mode,
Multiple Kids, Web filtering, Remote report) behind a gear icon.

A parent has exactly one recurring question: *"is my kid OK on their phone right now?"* That question
has no home in the app. Meanwhile **face enrollment — a once-ever setup task — holds 25% of permanent
navigation.**

### Cause 2 — Two products ship through one UI, and the app never asks which one you have

- **Product A** — app lock on a phone shared with a child. Local, no pairing.
- **Product B** — parental control of a child's own phone. Paired, remote.

Every user is shown both products' furniture, so **every user sees roughly half a UI that cannot
apply to them.** Because the app never asks, it can never hide anything; because it can't hide
anything, everything needs explaining; and that is where the 149 prose blocks come from.

> The copy problem is not a writing problem. Long sentences are the symptom of an unasked question.

### Cause 3 — Explanation is used where structure is needed

508 lines of coach marks and 3 tours exist to narrate a UI that doesn't explain itself. A tour is the
interface confessing: it fires once, gets dismissed, goes stale after a redesign, and the confusion
returns permanently. Inline paragraphs are the same confession in a different font.

---

## 3. Thesis

> **Ask one question at first run. Then show one product. Replace every paragraph with a correct
> default plus one line of text.**

Intuitive does not mean *well explained*. It means **fewer things on screen, each obviously mine.**
Every item below serves that sentence; anything that doesn't is out of scope.

---

## 4. New information architecture

### First run: one screen, one question, no prose

Two large tappable cards, illustrated, under a single heading — *"Whose phone is this?"*

- **"Mine, shared with my child"** → Product A
- **"My child has their own phone"** → Product B

The third case (this phone *is* the child's) is never a question — it is established by scanning the
parent's code, which already works. Mode is changeable later from one Settings row; it must never
trap anyone.

### Then: three tabs, never four, different per mode

**Mode A — shared phone**

| Tab | Holds |
|---|---|
| **Home** | What's locked right now, time left today, unlock |
| **Apps** | Choose locked apps, budget, schedule |
| **Settings** | Face enrollment, appearance, permissions, help |

**Mode B — parent of a child with their own phone**

| Tab | Holds |
|---|---|
| **Home** | One card per child: used today, limit, locked now, last seen, location, pending ask |
| **Kids** | Add/remove a child, per-child rules, web filtering |
| **Settings** | Account, premium, appearance, permissions, help |

No "Protect" tab — locking apps on the *parent's own* phone is meaningless here, and today it is a
permanent tab.

**Mode C — the child's phone**: one screen, unchanged. Per the earlier audit this is already the best
screen in the app. Do not touch it.

### What moves

| Today | Tomorrow |
|---|---|
| `Face` tab | Settings → Security (setup task, not furniture) |
| `Protect` tab | Mode A only, renamed **Apps** |
| Settings → Kid Mode | **Home** + **Apps** (it *is* the product) |
| Settings → Remote report | **Home** (it *is* the answer to the parent's question) |
| Settings → Multiple Kids | **Kids** |
| Settings → Web filtering | **Kids** → per child |
| Settings → What counts as screen time | **Apps** → "Not counted" |
| Stats tab | Merged into **Home**; history one tap down |

Result: 9 flat settings rows become 4, and no feature is discovered by opening a gear.

---

## 5. The copy system

Six rules. Mechanical, checkable, and they delete most of the 149 blocks without a judgement call.

1. **One line under a control, 8 words maximum.** Needing two lines means the label is wrong.
2. **No paragraph may explain what a control does.** Its label does that, or it gets a better label.
3. **A failure is a headline plus a button** — never a headline plus two sentences of causes.
4. **Numbers beat sentences.** "42 min left today" beats "Your child has used most of their time."
5. **One product name.** Keep **Kids Shield**. Drop "App Shield", "Face ID", "Shield" as surface
   names (the top bar currently says "App Shield" while 30 strings say "Kids Shield").
6. **Explanation lives behind an info affordance**, one tap, never inline.

### Real before/after, from `ui/parent/LocationSection.kt:298`

| Now | Becomes |
|---|---|
| "Location sharing is off on their phone" + *"They need to turn on location sharing in Kids Shield on their own device. You can't switch it on from here."* (23 words) | **"Sharing is off on their phone"** + button **[Show them how]** |
| "Couldn't get a fix" + *"Their phone may be indoors, out of signal, switched off, or offline. Try again in a moment."* (18 words) | **"No signal where they are"** + button **[Try again]** |
| "Kids Shield can't access location on their phone" + *"The location permission was denied or revoked there. It has to be granted on their device."* (16 words) | **"Location permission denied there"** + button **[Show them how]** |

The pattern already has a precedent in this codebase: the "Ask for more time" dialog was a 24-word
dead-end paragraph and became three tappable numbers. Apply that move 149 times.

### Also fix: the padlock means eight things

24 usages covering premium, parent gate, night lock, locked app, a disabled segment, browser TLS and
a nav tab. An icon with eight meanings has none. Reserve the padlock for **premium only**; night lock
gets a moon, parent gate a shield, a locked app its own app icon dimmed, TLS stays in browser chrome.

---

## 6. Phases

Ordered so visible wins land first and the risky structural work happens after its enabler.

| Phase | Work | Effort | Visible? |
|---|---|---|---|
| **P0** | Copy pass: apply the six rules across 149 blocks. Zero logic change. | 1 d | Immediately, everywhere |
| **P1** | Delete the fake Stats switcher; group Settings; one product name; padlock de-overload. | 1 d | Yes |
| **P2** | **Split `MainActivity.kt`** (3051 lines) into per-screen files. Pure refactor. | 1 d | No — but P3/P4 are unsafe without it |
| **P3** | First-run question + mode-aware 3-tab nav. | 3 d | This is the transformation |
| **P4** | Home dashboard per mode; merge Stats into Home. | 2 d | Yes, large |
| **P5** | Delete the 3 coach tours; replace with empty states that teach by doing. | 1 d | Yes, by subtraction |

~9 days. P0+P1 alone (2 days) will already feel like a different app, and neither touches logic.

**P5 note:** an empty state is a tour that cannot be dismissed, cannot go stale, and is read exactly
when it is relevant. "No children yet" plus one button beats a three-step overlay tour.

---

## 7. Risks and what must not break

- **`MainActivity.kt` at 3051 lines is the main hazard.** Do P2 before P3/P4 or they become
  dangerous edits. P2 must be a pure move with no behaviour change.
- **The mode question must be reversible** from Settings. A wrong tap at first run must never strand
  a user in the wrong product.
- **Do not touch** the child dashboard (Mode C), `AllowedApps`, the enforcement loop, the overlay, or
  anything in `location/`, `remote/`, `webfilter/`. This plan is presentation only.
- **Coach-mark deletion** must also clear `seenTours` readers and `replayTour` callers, or Help
  breaks.
- **Stats merge** must keep `ownerType`-driven view selection; that logic is correct, only its fake
  switcher is not.
- Every phase ships with the build and unit tests green (currently 293 passing).
