#!/usr/bin/env python3
"""
Convert the OpenCV Zoo SFace face-recognition model (ONNX, Apache-2.0) to TFLite for the
Shield Android app, and verify the conversion preserved the model's output.

Why this script exists
----------------------
SFace ships as ONNX (NCHW). Android wants TFLite (NHWC). `onnx2tf` remaps the layout cleanly
(unlike the old `onnx-tf`, which litters the graph with Transpose ops). The *conversion* is one
command; the risk is that a converted file that loads might still be subtly wrong — so this
script runs a PARITY CHECK: the same input through ONNX and through the TFLite must produce the
same embedding (cosine ~1.0).

What this DOES verify: the ONNX->TFLite conversion is faithful.
What this does NOT verify: that your Android-side preprocessing matches how SFace was trained
(BGR channel order + SFace's normalization + 5-point alignment). Those are app-side concerns —
read opencv_zoo/models/face_recognition_sface/sface.py and match them in FaceModelConfig.

Usage
-----
    pip install -U onnx2tf onnx onnxsim onnx_graphsurgeon sng4onnx onnxruntime tensorflow numpy
    python convert_sface.py                       # download SFace, convert, parity-check (random input)
    python convert_sface.py --image aligned.png   # parity-check against a real 112x112 aligned face
    python convert_sface.py --onnx my_sface.onnx  # use a local ONNX instead of downloading

Ship the produced *_float16.tflite (smaller, plenty accurate for MobileFaceNet) by copying it to
    app/src/main/assets/

Easiest environment: Google Colab (TensorFlow is preinstalled). Just `pip install onnx2tf onnxsim
onnxruntime` and run the cells from tools/README.md.
"""

import argparse
import glob
import os
import subprocess
import sys
import urllib.request

SFACE_URL = (
    "https://github.com/opencv/opencv_zoo/raw/main/"
    "models/face_recognition_sface/face_recognition_sface_2021dec.onnx"
)
INPUT_SIZE = 112  # SFace input is 112x112


def run(cmd):
    print(f"\n$ {' '.join(cmd)}")
    subprocess.run(cmd, check=True)


def ensure_onnx(path):
    if os.path.exists(path):
        print(f"Using existing ONNX: {path}")
        return path
    print(f"Downloading SFace ONNX -> {path}")
    urllib.request.urlretrieve(SFACE_URL, path)
    print(f"  done ({os.path.getsize(path) / 1e6:.1f} MB)")
    return path


def simplify(onnx_path):
    out = onnx_path.replace(".onnx", "_sim.onnx")
    try:
        run([sys.executable, "-m", "onnxsim", onnx_path, out])
        return out
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("onnxsim not available or failed; continuing with the un-simplified ONNX.")
        return onnx_path


def convert(onnx_path, out_dir):
    # onnx2tf writes <basename>_float32.tflite and <basename>_float16.tflite into out_dir.
    run(["onnx2tf", "-i", onnx_path, "-o", out_dir])
    f32 = glob.glob(os.path.join(out_dir, "*_float32.tflite"))
    f16 = glob.glob(os.path.join(out_dir, "*_float16.tflite"))
    if not f32:
        raise SystemExit(f"No *_float32.tflite produced in {out_dir} — check onnx2tf output above.")
    return f32[0], (f16[0] if f16 else None)


def make_test_input(image_path):
    """Returns (nchw[1,3,112,112], nhwc[1,112,112,3]) float32. The same pixels, two layouts.

    For the parity test the exact normalization is irrelevant (it must only be IDENTICAL on both
    sides), so we feed raw 0-255 floats. Real Android preprocessing must instead match sface.py.
    """
    import numpy as np

    if image_path:
        try:
            import cv2
            img = cv2.imread(image_path)
            if img is None:
                raise SystemExit(f"Could not read image: {image_path}")
            img = cv2.resize(img, (INPUT_SIZE, INPUT_SIZE)).astype("float32")
        except ImportError:
            raise SystemExit("opencv-python needed for --image; `pip install opencv-python` or omit --image.")
    else:
        print("No --image given; using random input (verifies conversion fidelity, not realism).")
        img = (np.random.rand(INPUT_SIZE, INPUT_SIZE, 3) * 255.0).astype("float32")

    nhwc = img[None, ...]                         # [1,112,112,3]
    nchw = np.transpose(img, (2, 0, 1))[None, ...]  # [1,3,112,112]
    return nchw, nhwc


def parity_check(onnx_path, tflite_path, image_path):
    import numpy as np
    import onnxruntime as ort
    import tensorflow as tf

    nchw, nhwc = make_test_input(image_path)

    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    print(f"\nONNX  input  : {in_name} {sess.get_inputs()[0].shape}")
    print(f"ONNX  output : {sess.get_outputs()[0].shape}")
    onnx_out = np.array(sess.run(None, {in_name: nchw})[0]).flatten()

    interp = tf.lite.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    outp = interp.get_output_details()[0]
    print(f"TFLite input : {inp['shape'].tolist()} {inp['dtype'].__name__}")
    print(f"TFLite output: {outp['shape'].tolist()}")

    if list(inp["shape"]) != [1, INPUT_SIZE, INPUT_SIZE, 3]:
        print(f"  ⚠ Expected TFLite input [1,{INPUT_SIZE},{INPUT_SIZE},3] (NHWC) — got {inp['shape'].tolist()}.")
    interp.set_tensor(inp["index"], nhwc.astype(inp["dtype"]))
    interp.invoke()
    tfl_out = np.array(interp.get_tensor(outp["index"])).flatten()

    cos = float(onnx_out @ tfl_out / (np.linalg.norm(onnx_out) * np.linalg.norm(tfl_out) + 1e-9))
    print(f"\nEmbedding dim: ONNX={onnx_out.size}  TFLite={tfl_out.size}")
    print(f"PARITY cosine (ONNX vs TFLite): {cos:.6f}")
    if cos > 0.999:
        print("✅ PASS — conversion is faithful.")
    else:
        print("❌ FAIL — outputs diverge. Likely an onnx2tf layout/op issue; inspect the conversion log.")
    return cos


def main():
    ap = argparse.ArgumentParser(description="Convert OpenCV Zoo SFace ONNX -> TFLite + parity check.")
    ap.add_argument("--onnx", default="sface.onnx", help="Path to SFace ONNX (downloaded if missing).")
    ap.add_argument("--out", default="sface_out", help="Output dir for the .tflite files.")
    ap.add_argument("--image", default=None, help="Optional 112x112 aligned face for a realistic parity check.")
    ap.add_argument("--skip-sim", action="store_true", help="Skip onnxsim simplification.")
    args = ap.parse_args()

    onnx_path = ensure_onnx(args.onnx)
    if not args.skip_sim:
        onnx_path = simplify(onnx_path)

    os.makedirs(args.out, exist_ok=True)
    f32, f16 = convert(onnx_path, args.out)
    print(f"\nProduced:\n  float32: {f32}")
    if f16:
        print(f"  float16: {f16}   <-- ship this one (copy to app/src/main/assets/)")

    parity_check(onnx_path, f32, args.image)

    print(
        "\nNext, on the Android side (do NOT skip — these decide whether SFace actually recognizes faces):\n"
        "  1. Confirm input is 112x112 and note the output embedding dim printed above.\n"
        "  2. SFace is BGR — Android Bitmaps are RGB; swap R<->B in FaceModelConfig.\n"
        "  3. Match SFace's normalization from opencv_zoo .../sface.py (it differs from FaceNet's (x-127.5)/127.5).\n"
        "  4. Feed 5-point ALIGNED faces (SFace requires it) — use ML Kit landmarks (migration Phase 2b).\n"
        "  5. Re-tune the 0.6 / 0.08 thresholds on real faces (migration Phase 3)."
    )


if __name__ == "__main__":
    main()
