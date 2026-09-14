# tools/

Helper scripts that run **off-device** (on your computer / Colab), not part of the Android build.

## `convert_sface.py` — SFace ONNX → TFLite

Converts the [OpenCV Zoo **SFace**](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)
face-recognition model (MobileFaceNet architecture, **Apache-2.0**) to TFLite for Shield, and
**parity-checks** that the conversion didn't change the model's output.

This is **Phase 0** of [`../FACE_MODEL_MIGRATION_PLAN.md`](../FACE_MODEL_MIGRATION_PLAN.md). It does
not touch the app — it just produces a `.tflite` you later drop into `app/src/main/assets/`.

### Option A — Google Colab (easiest; TensorFlow preinstalled)

```python
!pip install -U onnx2tf onnx onnxsim onnx_graphsurgeon sng4onnx onnxruntime
!wget -q https://raw.githubusercontent.com/<your-fork>/.../tools/convert_sface.py -O convert_sface.py
!python convert_sface.py
```

…or just paste the four commands the script wraps:

```python
!pip install -U onnx2tf onnx onnxsim onnxruntime
!wget -q https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx -O sface.onnx
!onnxsim sface.onnx sface_sim.onnx
!onnx2tf -i sface_sim.onnx -o sface_out      # -> sface_out/sface_sim_float16.tflite
```

### Option B — local (Python 3.10 recommended; a venv keeps deps isolated)

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -U onnx2tf onnx onnxsim onnx_graphsurgeon sng4onnx onnxruntime tensorflow numpy
python tools/convert_sface.py                       # add --image aligned_face.png for a realistic parity check
```

### What "success" looks like

```
ONNX  input  : ... [1, 3, 112, 112]
TFLite input : [1, 112, 112, 3] float32
Embedding dim: ONNX=128  TFLite=128
PARITY cosine (ONNX vs TFLite): 0.999xxx
✅ PASS — conversion is faithful.
```

Then copy `sface_out/*_float16.tflite` → `app/src/main/assets/`.

### ⚠ The parity check is necessary but NOT sufficient

A passing parity check proves the **conversion** is faithful. It does **not** prove SFace will
recognize faces in the app — that depends on matching SFace's training-time preprocessing, which
differs from the current FaceNet path:

| Concern | Current model | SFace | Where it's handled |
|---|---|---|---|
| Channel order | RGB | **BGR** | `FaceModelConfig` (swap R↔B) |
| Normalization | `(x−127.5)/127.5` | **read `sface.py`** (differs) | `FaceModelConfig` |
| Alignment | none (raw crop) | **5-point aligned required** | migration Phase 2b (ML Kit landmarks) |
| Output dim | 128-d | confirm (printed above) | `FaceModelConfig` |
| Thresholds | 0.6 / 0.08 | **re-tune on real faces** | migration Phase 3 |

Source preprocessing reference: [`opencv_zoo/.../sface.py`](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/sface.py).

### License note

SFace's model directory carries an explicit **Apache-2.0** LICENSE placed by OpenCV. Keep the
license/attribution notice if you ship it. (See the migration plan's Decisions for the full
provenance reasoning vs. the current, license-unconfirmed model.)
