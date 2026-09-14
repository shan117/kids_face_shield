# Face Model Migration — (mislabeled) MobileFaceNet → SFace (license-clean)

Source of truth for migrating the face-recognition embedder to OpenCV Zoo's **SFace** (Apache-2.0,
MobileFaceNet architecture), with proper **5-point alignment**. Update this file whenever rules
change. The **Phase tracker** near the bottom doubles as the status log.

> ⚠️ This touches the live auth path (parent unlock + multi-kid identify). Compile-pass ≠ works —
> every model-touching phase must be validated on a real device, like the existing camera work.

> 🕒 **Timing (user decision):** this migration is **deferred until just before monetization**.
> Ship the current model through the free promo; do the SFace swap **before** flipping
> `promo_active=false` (see `PREMIUM_FEATURE_PLAN.md`). The driver is **license**, not a feature gap.

---

## 1. Goal

The shipped model is **already a MobileFaceNet** — the asset is named `facenet.tflite` but it's the
`mobilefacenet.tflite` from the [MCarlomagno/FaceRecognitionAuth](https://github.com/MCarlomagno/FaceRecognitionAuth)
demo (4.99 MB; real FaceNet is ~90 MB). Its **weights have no documented, commercially-usable
license** (the repo's BSD-3 covers the author's code, not third-party weights of unknown training
provenance). So this is **not an accuracy upgrade — it's a license fix**:

Replace it with **OpenCV Zoo SFace** — same MobileFaceNet *class* (small, ~comparable accuracy,
~99.4% LFW) but with an explicit **Apache-2.0** license — plus **5-point alignment** (which SFace
requires and which lifts accuracy for any model). Goals:

- **Commercially-clean weights** so the app can be charged for.
- A modest accuracy/robustness bump from alignment, including **1:N sibling separation** (multi-kid).

…without ever locking a user out, leaking face images, or silently producing wrong matches from
stale embeddings.

> Heavier alternative considered & rejected for shipping: **AuraFace-v1** (Apache-2.0, ResNet100) —
> cleaner-license too, but its recognition model is **261 MB** (≈65 MB int8), impractical to bundle
> in a real-time overlay app. Useful only as a *teacher* if we ever distill our own model.

## 2. Why this is the *whole* story (not just a model swap)

The embedder is one stage in a pipeline that is **already** the recommended design:

```
CameraX → ML Kit detect (+eye-open probs) → crop → embed (TFLite) → cosine → blink liveness → N-streak → auth
                                                     └── this is what changes ──┘
```

Three facts make a naïve swap dangerous, and shape the plan:

1. **Embeddings are model-specific.** A stored 128-d FaceNet vector is meaningless to a 512-d
   ArcFace model. Comparing across models yields garbage scores → wrong unlocks or lockouts.
   → We must **invalidate all stored embeddings on model change and force re-enrollment.**
2. **We don't store face images** (privacy). So we cannot recompute new embeddings from old data —
   re-enrollment is the *only* correct path. (Dual-store both embeddings? Only helps *future*
   enrollments, not existing users — rejected as complexity with no payoff.)
3. **ArcFace expects aligned faces.** FaceNet tolerates a raw bounding-box crop; ArcFace is trained
   on faces warped so the eyes/nose/mouth sit at canonical positions. Skipping alignment throws away
   much of ArcFace's accuracy advantage. → Alignment ships **with** the model swap (one pipeline
   change = one re-enroll), not after.

**Best-migration principle:** ship the *invalidation safety net first* (while still on FaceNet),
then flip the model+alignment in a single version bump so every user re-enrolls exactly once, then
tune thresholds empirically on the final pipeline.

## 3. Decisions

| # | Decision |
|---|---|
| D1 | Target model = **OpenCV Zoo SFace** (`face_recognition_sface_2021dec`, Apache-2.0, MobileFaceNet, 112×112). It is **BGR**, uses **SFace's own normalization** (read `sface.py`, ≠ FaceNet's `(x−127.5)/127.5`), outputs a **128-d** embedding (confirm in Phase 0), and **requires 5-point aligned input**. All of these are captured in `FaceModelConfig`, never assumed. |
| D8 | **Deferred until pre-monetization.** Ship the current model during the free promo; perform the SFace swap before `promo_active=false`. Phases 1–2b (no behavior change) may land early; 2c (the flip) waits until the monetization run-up. |
| D2 | **Version-gated embeddings.** A single `face_model_version` string records which model the stored embeddings belong to. App ships a compile-time `CURRENT_FACE_MODEL_VERSION`. On mismatch, all stored embeddings are treated as **not enrolled** → re-enroll prompt; enforcement that needs a face stays inert until re-enrolled. |
| D3 | **Model + alignment flip together** = one version bump = users re-enroll **once**. |
| D4 | **No face images persisted.** Re-enrollment is the migration path. (Privacy + Data-Safety unchanged.) |
| D5 | **Thresholds are empirical.** Both the 1:1 (`FaceRecognitionManager.threshold`) and 1:N (`FaceMatcher.threshold`/`margin`) are re-tuned from measured score distributions on the new pipeline — the current `0.6`/`0.08` are FaceNet-specific and will not transfer. |
| D6 | **Fail safe, not open or shut.** Model-load failure / unusable frames must never (a) silently unlock, nor (b) permanently lock the parent out. Surface "re-enroll / face unavailable", keep the existing non-face escape hatches. |
| D7 | Keep `facenet.tflite` in the build until the migration is confirmed on devices, so rollback is a config flip + one re-enroll, not a broken build. |

## 4. Target model + conversion (Phase 0)

**OpenCV Zoo SFace** — `face_recognition_sface_2021dec.onnx`, Apache-2.0, MobileFaceNet, small
(int8/float16 variants), ~99.4% LFW. It ships as **ONNX**, so Phase 0 converts it to TFLite.

- **Convert + verify with [`tools/convert_sface.py`](tools/convert_sface.py)** (see `tools/README.md`):
  downloads SFace, runs `onnx2tf` (clean NCHW→NHWC, no Transpose litter), and **parity-checks**
  that ONNX vs TFLite outputs match (cosine ~1.0). Ship the produced `*_float16.tflite`.
- The parity check proves the *conversion* is faithful. It does **not** prove the app-side
  preprocessing is right — confirm before wiring:
  - input `1×112×112×3` (NHWC after conversion), dtype float32,
  - **channel order = BGR** (Android Bitmaps are RGB → swap R↔B),
  - **normalization = SFace's** (read [`sface.py`](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/sface.py); differs from FaceNet's),
  - output dim (expect 128-d; whether it L2-normalizes — we L2-normalize again, idempotent),
  - **5-point aligned** input (SFace requires it → Phase 2b is mandatory, not optional),
  - sanity: same face twice → high cosine; two different faces → low. If not, channel/normalization/alignment is wrong.

## 5. Architecture (components → files)

| File | Change |
|---|---|
| `app/src/main/assets/<arcface>.tflite` | New model asset (kept alongside `facenet.tflite` during transition). |
| `face/FaceModelConfig.kt` *(new)* | Pure config: `assetName`, `inputSize`, `normMean`, `normStd`, `channelOrder`, `outputDim`, `version`. The single place the model is described. |
| `face/FaceAligner.kt` *(new)* | 5-point similarity transform → canonical 112×112, using ML Kit landmarks. Pure geometry where possible (unit-testable transform math). |
| `face/FaceRecognitionManager.kt` | Read `FaceModelConfig` instead of hardcoded 112/127.5/128; enable `LANDMARK_ALL`; align before embedding; expose `currentVersion`. |
| `face/FaceMatcher.kt` | Re-tuned `threshold`/`margin` (Phase 3); dimension-agnostic already. |
| `data/DataStoreManager.kt` | `face_model_version` key + flow + setter; stale-aware `parentFaceEnrolled` / `kidFacesEnrolled(n)` derived flows. |
| `MainActivity.kt` (enrol + welcome gate) | Treat stale (version-mismatch) embeddings as **not enrolled**; stamp `CURRENT_FACE_MODEL_VERSION` on every enrol. |
| `service/AppLockForegroundService.kt` | Multi-kid enforce/identify guards consult the stale-aware enrolled flags (not just `size >= 2`). |
| `ui/profiles/MultiKidSection.kt` | "Re-enrol both kids — face engine upgraded" prompt when stale. |
| `doc/TECHNICAL_DOC.md` | Update model name, input/normalization, output dim, thresholds (Phase 2c/3). |

## 6. Data model additions (`DataStoreManager`)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `face_model_version` | String | `""` | Model tag the stored embeddings belong to. Written on every (re)enrol. |

Derived (no new storage):
- `parentFaceEnrolled = faceEmbedding != null && face_model_version == CURRENT_FACE_MODEL_VERSION`
- `kidFacesEnrolled(n) = kidFaceEmbeddings.size >= n && face_model_version == CURRENT_FACE_MODEL_VERSION`

A **single global** version is sufficient: any model change invalidates *all* embeddings at once, so
mixed-model state is impossible. Parent and kids always enrol on the current model.

## 7. Pipeline: before → after

```
BEFORE:  detect → crop boundingBox → resize 112 → (x−127.5)/127.5 → FaceNet 128-d → L2 → cosine
AFTER:   detect (+5 landmarks) → similarity-transform-align → 112 → model-norm → ArcFace 512-d → L2 → cosine
```

Enrol and verify **must run the identical align+normalize path**, or the embeddings won't compare.

## 8. Build phases (each compiles + is independently testable)

| Phase | Scope | Visible effect | Device? |
|---|---|---|---|
| **0** | Convert **SFace** ONNX→TFLite via `tools/convert_sface.py` (parity check); confirm BGR/normalization/output-dim/alignment; add the `.tflite` asset; wire nothing. | None | script only |
| **1** | **Safety net (still FaceNet):** `face_model_version` key, stale-aware enrolled flags, gate welcome-enrol + multi-kid enforce on them. Stamp version on enrol = current FaceNet tag. | None (everything already "current") | light |
| **2a** | `FaceModelConfig` + make `FaceRecognitionManager` config-driven, **still pointing at FaceNet**. | None | light |
| **2b** | `FaceAligner` (5-pt) behind a flag, validated on FaceNet (align shouldn't break existing matching). Default **off**. | None until 2c | yes |
| **2c** | Flip config → **ArcFace + alignment on**, bump `CURRENT_FACE_MODEL_VERSION`. Old embeddings auto-invalidated → re-enrol forced. Conservative initial thresholds. | Re-enrol prompt; new engine live | **yes** |
| **3** | **Empirical threshold tuning** (1:1 + 1:N margin) from measured genuine/impostor scores. Lock in constants. | Accuracy dialed in | **yes** |
| **4** | Re-enrol UX polish: parent prompt copy, multi-kid "re-enrol both" prompt, one-time messaging; confirm no lockout + fail-safe on model-load failure. | Clean re-enrol experience | **yes** |
| **5** *(optional, later)* | Liveness hardening — close the `framesWithNullEyeProb` streak-only fallback; evaluate a passive-liveness model. | Stronger anti-spoof | yes |

**Order:** 0 → 1 → 2a → 2b → 2c → 3 → 4 → (5). Phases 1–2b are **no-behavior-change** and safe to ship
incrementally. 2c is the single re-enroll point.

## 9. Threshold tuning method (Phase 3)

The current `0.6`/`0.08` are FaceNet numbers and **will not transfer** — ArcFace score distributions differ.

1. **Define the operating point** up front, e.g. target **FAR ≤ 0.1%**, **FRR ≤ 2%** (tune to taste;
   a child-safety locker leans toward low FAR).
2. **Collect scores on-device** (reuse the existing `Log.d` of match scores, or a temporary debug sink):
   - *Genuine:* enrolled parent unlocks ~20–30× across lighting/angles.
   - *Impostor:* 3–5 other people attempt ~20× each.
   - *1:N:* score each kid's probe against both gallery entries; record best vs runner-up gaps,
     especially for the most similar-looking pair.
3. **Pick thresholds** from the distributions: 1:1 threshold at the chosen FAR/FRR crossover; 1:N
   `threshold` similarly; 1:N `margin` = a value that separates the sibling best-vs-runner-up gaps
   (ambiguous → stays "unknown" → tap-to-confirm, never a wrong auto-pick).
4. **Bake into `FaceModelConfig` / `FaceMatcher`** and note the measured numbers + sample size in
   the Phase tracker.

> Don't trust a blog-post number. MobileFaceNet thresholds are commonly *lower* than FaceNet's 0.6,
> but the only correct threshold is the one your measurements support.

## 10. Edge cases & guardrails

- **Partial re-enrol during rollout** (parent done, kids not): multi-kid enforcement stays in
  single-kid / non-enforcing mode until `kidFacesEnrolled(2)` is true on the current version. No
  half-migrated enforcement.
- **Dimension mismatch:** `compareEmbeddings` already clamps to `minOf(size)` so it can't crash, but
  the version gate prevents any cross-model comparison in the first place. (Belt and suspenders.)
- **Model-load failure:** today a null interpreter returns a zero vector → cosine 0 → parent can't
  unlock. Phase 4 must detect this and surface "face unavailable" + keep the non-face escape hatches,
  rather than a silent permanent lock (D6).
- **Alignment failure** (landmarks missing for a frame): fall back to the bounding-box crop for that
  frame; don't drop the session. Tune so a few unaligned frames don't poison the streak.
- **Channel/normalization wrong:** caught by the Phase 0 same-face/different-face sanity check — if
  genuine cosine isn't clearly high, stop and fix before 2c.
- **Privacy/Data-Safety unchanged:** still on-device, still no images stored; re-enrol only.

## 11. Rollback

- **Phases 1–2b:** no behavior change — revert the commit.
- **Phase 2c (the model flip):** because embeddings are version-gated, flipping `FaceModelConfig`
  back to FaceNet + restoring `CURRENT_FACE_MODEL_VERSION` cleanly reverts — users simply re-enrol on
  FaceNet again. No corrupted state, no broken build (FaceNet asset retained per D7). Re-enroll is the
  only cost.

---

## Phase tracker

Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `[!]` reverted/blocked

| Phase | Description | Status | Notes |
|---|---|---|---|
| 0 | Convert SFace ONNX→TFLite (`tools/convert_sface.py`) + verify (shape, BGR, normalization, output dim, parity) | `[x]` | **Done.** onnx2tf+TF 2.21 converted `face_recognition_sface_2021dec.onnx` (38.7 MB). **Parity cosine = 1.000000.** TFLite input `[1,112,112,3]` float32 (NHWC), output **128-d**. Shipped `app/src/main/assets/face_recognition_sface_2021dec_float16.tflite` (19.3 MB) **alongside** `facenet.tflite` (D7). NOT wired (Phase 2c). ⚠ BGR + SFace normalization still to confirm on-device before 2c. |
| 1 | Versioned-embedding safety net (stale = not-enrolled), still FaceNet | `[x]` | **Done, no behavior change.** `face_model_version` key + `setFaceModelVersion`; stamped on every parent/kid enrol. Stale-aware flows `parentFaceEnrolled` / `kidFacesEnrolled(n)` (legacy unstamped counts as current while on FaceNet). Wired the welcome-enrol gate (`MainActivity` FirstRunWelcome) + multi-kid enforce (`isMultiKidActive`). `FaceModelConfigTest` (4) green. |
| 2a | `FaceModelConfig` + config-driven `FaceRecognitionManager` (FaceNet) | `[x]` | **Done, no behavior change.** `FaceModelConfig.ModelSpec` (assetName/inputSize/normMean/normStd/swapRB/outputDim/requiresAlignment/version); `active = FACENET`; `SFACE` spec defined but inactive. `FaceRecognitionManager` reads `active` (asset, size, normalization) + exposes `currentVersion`. |
| 2b | `FaceAligner` 5-pt alignment behind a flag, validated on FaceNet | `[~]` | **Code-complete, device-validation pending.** `FaceAligner` (similarity-transform fit `solveSimilarity` + Gaussian solve + `align` bitmap warp; ArcFace 112 template). Gated by `active.requiresAlignment` (FaceNet=false → **off**). `FaceAlignerTest` (2) green — recovers a known scale/rot/translate. NOT yet wired into the live embed path (that lands with 2c); ML Kit `LANDMARK_ALL` extraction + align-before-embed are the device-validated 2c steps. |
| 2c | Flip → ArcFace + alignment; bump version → forced re-enrol | `[ ]` | **Device.** Single re-enroll point. Remaining wiring: confirm SFace BGR/normalization (`swapRB` + `normMean/Std`) on-device; enable `LANDMARK_ALL` + extract 5 pts; align-before-embed when `requiresAlignment`; set `active = SFACE`. |
| 3 | Empirical threshold tuning (1:1 + 1:N) | `[ ]` | **Device.** Record measured numbers. |
| 4 | Re-enrol UX + fail-safe polish | `[ ]` | **Device.** |
| 5 | *(optional)* Liveness hardening | `[ ]` | Later. |

## How to resume in a future session
1. Read §1–§3 for the rules, §8 for the phase you're on.
2. Implement that phase **only**; keep Phases 1–2b no-behavior-change.
3. Build + (for device phases) validate on a phone; update the **Phase tracker** with the result
   (and, for Phase 3, the measured thresholds + sample size).
4. Never break the invariant: a model change must invalidate old embeddings and force re-enrol —
   never compare across models.
