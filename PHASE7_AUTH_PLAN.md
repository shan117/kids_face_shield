# Phase 7 — real access control for the relay

Status: **done and verified on device.**

Anonymous sign-in live, `pairings/{pairingId}` membership records created and claimed (two uids
confirmed in the console), strict rules published, and the app verified working under them — including
linking a second child **with the strict rules already live**, which is the order production will always
use.

The pairing id is no longer a bearer token. It stays unguessable and unlistable, but on its own it now
grants nothing: read, write and delete all require being one of the two device uids recorded at pairing.

### What the device test actually proved

| | |
|---|---|
| Anonymous sign-in | works, and persists across restarts |
| `claimDeadline is int` | holds — Kotlin `Long` → Firestore number → rules `int` |
| `arrayUnion` claim under strict rules | the parent can append itself to a record it is not yet in |
| Rules-internal `get()` on `pairings/` | resolves for all four relay collections |
| A second child, rules already live | pairs cleanly — the production order |

### One note on the ordering advice in §3

Step 3 said to pair *before* publishing. That was debugging convenience dressed up as a requirement: in
production the rules are always live first, so the useful test is the one actually performed here.
Keeping the step only as "verify two uids before trusting it".

---

## 0. The problem

There is no authentication in the app today. Identity is the **pairing**: a random 128-bit id that is
both the Firestore document key and a bearer token, plus an AES-256 key, exchanged by QR.

`firestore.rules` currently reads:

```
allow get, create, update, delete: if true;
allow list: if false;
```

So anyone who learns a pairing id can read, overwrite or **delete** that family's documents. The E2E
encryption still protects confidentiality — they cannot decrypt without the key, and they cannot forge
valid ciphertext — but it does nothing for availability. Deleting a document, or overwriting it with
garbage, needs only the id.

What is already fine and stays that way:

- Families cannot *discover* each other. `list: if false` plus 128 random bits of key space.
- Google cannot read any of it. The relay stores ciphertext only.
- Collisions are not a risk: ~2^64 pairings for a 50% chance.

**This phase closes the gap between "encrypted" and "access-controlled".**

---

## 1. Why now, while still in development

Everything expensive about this phase is migration, and with no field users none of it applies:

| With live users | In development |
|---|---|
| Grandfather documents that have no `uids` | not needed |
| "Adopt your own document" code on both sides | not needed |
| Two-release transition before tightening | not needed |
| A later release to remove the grandfathering | not needed |

Roughly half a day instead of two days plus two release cycles. And an `if true` rule that has been
live for months becomes the rule nobody dares touch, because nobody can tell who it would break.

The cost that remains is now the developer's, not a user's: **a new anonymous UID locks a device out of
its own documents.** `adb install -r` keeps app data so the UID survives; `adb uninstall` or clearing
data means re-pairing with a fresh QR.

---

## 2. Design

### Anonymous sign-in

`FirebaseAuth.signInAnonymously()` gives each install a stable UID. No login, no email, no password —
neither parent nor child ever sees an account. It exists only so the server can tell one device from
another.

### Corrected design — one shared membership record

The first attempt stamped the creating device's uid onto each relay document. **That cannot work**, and
the fault was only visible once a real document existed — its `uids` held exactly one entry, the
creator's.

Every relay collection has one writer and one reader:

| Collection | Created by | Read by |
|---|---|---|
| `reports`, `locations` | child | parent |
| `commands`, `grants` | parent | child |

So the reader was never in the document's own `uids`, and a reader **cannot add itself** — claiming is a
write. Strict rules on that model would have denied all four directions at once. `grants` made it
undeniable: that document may be created weeks after pairing, long after any per-document claim window
could close.

**Fix:** one record per pairing, `pairings/{pairingId}`, holding `uids` and `claimDeadline`. Every
collection's rule consults it via `get()`. Membership is established once, at pairing, when both devices
are demonstrably present — the child is holding up its QR.

Cost: one extra document read per request, billed, negligible at this scale. It is the standard Firestore
pattern for exactly this.

### Membership recorded at pairing

```
reports/{pairingId}
  uids:          [childUid, parentUid]
  claimDeadline: <epoch ms, ~10 min after creation>
  iv, ciphertext, updatedAt, schemaVersion
```

1. The child mints the pairing and creates the document with `uids: [childUid]` and a `claimDeadline`.
2. The parent scans the QR and appends its own UID.

### The claim window, and why it is time-boxed

There is a chicken-and-egg problem: the parent must write to a document it is not yet a member of. So
the rules permit appending **exactly one** UID while `uids.size() < 2` and the deadline has not passed.

QR scanning happens in person, in seconds. A 10-minute window is generous for the real flow and shrinks
the exposure for a leaked QR from *forever* to *minutes*. After it closes, the document is locked to
those two devices and a stolen id buys nothing.

### Rules

Staged in **`firestore.rules.phase7`**, deliberately NOT in the live `firestore.rules`.

> ⚠️ Publishing these before an authenticating build is installed on both devices breaks everything
> instantly — no device would be able to read or write. Install first, publish second.

---

## 3. Firebase console work

| # | Action | When |
|---|---|---|
| 1 | **Authentication → Sign-in method → Anonymous → Enable** | Required. `signInAnonymously()` fails without it. |
| 2 | **Delete dev documents** in `reports/`, `commands/`, `locations/` | They have no `uids`, so the strict rules would make them permanently unreachable — and the app could not even delete them, since delete requires membership. The console bypasses rules, so it can. |
| 3 | **Publish `firestore.rules.phase7`** | ONLY after the authenticating build is on both test devices. |
| 4 | *Optional:* auto-delete unused anonymous accounts | Authentication → Settings. Housekeeping; anonymous accounts otherwise accumulate. |

Re-pair both devices after step 2 — fresh QR, fresh documents, now carrying `uids`.

---

## 4. Implementation status

### Done (offline-safe)

| Piece | File |
|---|---|
| Pure membership + claim-window logic | `remote/PairingMembership.kt` |
| Tests for it | `remote/PairingMembershipTest.kt` |
| Strict rules, staged | `firestore.rules.phase7` |
| `DeviceIdentity` seam | `remote/DeviceIdentity.kt` |

`DeviceIdentity.uid()` returns null until the dependency lands. Every call site already handles null by
writing no `uids` field — which is exactly today's behaviour, so the app is unchanged and the build
stays green.

### Also done — anonymous sign-in is live

`firebase-auth` added (BoM-managed, resolves to 23.1.0) and `FirebaseAnonymousIdentity` now really signs
in. Build green, 282 tests.

Three details in that implementation worth keeping:

- **Fast path first.** `auth.currentUser?.uid` before any I/O. Firebase persists the anonymous user
  locally, so after the first launch this needs no network and survives restarts.
- **A `Mutex` around sign-in.** The report upload, a grant renewal and a location response can coincide,
  and without it each would fire its own `signInAnonymously()` — wasted round trips exactly when the
  network is busy. Re-checks `currentUser` inside the lock.
- **Listener-based, not `.await()`.** `kotlinx-coroutines-play-services` is not a dependency, and this
  matches how the repositories in the same package already wrap Play Services tasks.

Warmed once at startup from `AppLockApplication`, fire-and-forget, so the first relay write does not pay
for sign-in.

### What remains: the console steps, in order

Nothing left in code. §3 steps 1–4:

1. Enable Anonymous sign-in **← this is now required, or `uid()` returns null forever**
2. Delete the dev documents in `reports/` `commands/` `locations/` `grants/`
3. Install on both devices and re-pair; confirm a document carries a `uids` array
4. Only then replace `firestore.rules` with `firestore.rules.phase7` and publish

Step 3 is the gate. If `uids` is absent from a freshly created document, publishing the strict rules
locks every device out — so check the console before publishing, not after.

---

## 5. Must not regress

| Risk | Guard |
|---|---|
| **Sign-in failure breaks local enforcement.** A child offline at first launch must still have budgets, night lock and app locking. | None of those touch Firestore. `uid()` returning null must never throw and never block — verify, do not assume. |
| **Publishing rules too early** locks every device out. | Rules staged in a separate file; the switchover order is step 3 of §3. |
| **Claim window too short** → pairing fails in a bad-signal moment. | 10 minutes, against a flow that takes seconds. |
| **Claim window reusable** → a third device claims the second slot. | `uids.size() < 2` plus the deadline. Once two UIDs are recorded the window is shut permanently. |
| Report upload failing on auth error looks like "no report yet". | Repositories already return false / `Missing` rather than throwing. |

---

## 6. Deliberately out of scope

**Firebase App Check.** Attests that requests come from the real app binary rather than a script — it
answers *"is this even my app"*, where auth answers *"which device"*. The right thing eventually, but it
needs Play Integrity configuration and only matters once someone has reason to attack the API. Auth is
the structural fix; App Check is hardening on top of it.

---

## 7. Sequencing

1. Device-test web filtering and location (both unverified on hardware)
2. One online build → add `firebase-auth`
3. Finish this phase: real `uid()`, console steps 1–2, re-pair, publish rules
4. Then flip `promo_active=false`

Auth touches the same Firestore paths as the two unverified features. Debugging two unknowns at once is
how a day disappears.
