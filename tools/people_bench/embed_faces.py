#!/usr/bin/env python3
"""Detect and embed every face of the licensed benchmark photos, the way the
app does: decode to a 960 px long edge, YuNet (score 0.9, NMS 0.3, 32 faces),
OpenCV SFace alignment, the shipped int8 SFace model through ONNX Runtime.

Writes faces.npz under --work (outside the repository): L2-normalised
vectors after the app's per-vector int8 storage codec, plus per-face image
index, box size in the 960 px frame, detector score and raw feature norm.
Nothing here is committed; only aggregates from the other scripts are.
"""
import argparse
import csv
import hashlib
import os

import cv2
import numpy as np
import onnxruntime as ort

YUNET_SHA = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
SFACE_SHA = "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"
LONG_EDGE = 960      # FaceDiscoveryContract.DEFAULT_DECODE_LONG_EDGE_PX
INPUT = 640          # YuNetModel.INPUT_SIZE
MAX_FACES = 32       # FaceDiscoveryContract.MAX_FACES_PER_IMAGE


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def int8_codec(unit):
    """FaceEmbedding's storage: int8 with one scale per vector, decoded."""
    scale = np.abs(unit).max() / 127.0
    q = np.clip(np.rint(unit / scale), -127, 127)
    out = q * scale
    return (out / np.linalg.norm(out)).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True)
    ap.add_argument("--set", required=True, help="licensed.csv from fetch_images.py")
    ap.add_argument("--models", required=True, help="app/src/main/assets/models")
    ap.add_argument("--work", required=True)
    ap.add_argument("--save-crops", action="store_true",
                    help="also keep the aligned 112x112 crops under --work (for silver_labels.py)")
    a = ap.parse_args()
    yunet = os.path.join(a.models, "yunet/face_detection_yunet_2023mar.onnx")
    sface = os.path.join(a.models, "sface/face_recognition_sface_2021dec_int8.onnx")
    assert sha(yunet) == YUNET_SHA and sha(sface) == SFACE_SHA, "model bytes differ from the APK assets"
    os.makedirs(a.work, exist_ok=True)

    det = cv2.FaceDetectorYN.create(yunet, "", (INPUT, INPUT), 0.9, 0.3, 5000)
    sess = ort.InferenceSession(sface, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0].name
    crops = []

    ids = [r["ImageID"] for r in csv.DictReader(open(a.set, newline=""))]
    vecs, img_idx, side, score, norm, ordinal = [], [], [], [], [], []
    for n, img_id in enumerate(ids):
        im = cv2.imread(os.path.join(a.images, img_id + ".jpg"))
        if im is None:
            continue
        h, w = im.shape[:2]
        s = LONG_EDGE / max(h, w)
        if s < 1:
            im = cv2.resize(im, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
            h, w = im.shape[:2]
        # Top-left letterbox to 640, scale only (YuNetLetterbox).
        ls = INPUT / max(h, w)
        small = cv2.resize(im, (max(1, round(w * ls)), max(1, round(h * ls))), interpolation=cv2.INTER_LINEAR)
        canvas = np.zeros((INPUT, INPUT, 3), np.uint8)
        canvas[:small.shape[0], :small.shape[1]] = small
        _, faces = det.detect(canvas)
        if faces is None:
            continue
        faces = faces[np.argsort(-faces[:, 14])][:MAX_FACES]
        for k, f in enumerate(faces):
            f = f.copy()
            f[:14] /= ls
            crop = align(im, f[4:14].reshape(5, 2))
            rgb = crop[:, :, ::-1].astype(np.float32).transpose(2, 0, 1)[None]
            raw = sess.run(None, {inp: rgb})[0][0]
            nr = float(np.linalg.norm(raw))
            if not np.isfinite(nr) or nr < 1e-6:
                continue
            vecs.append(int8_codec(raw / nr))
            if a.save_crops:
                crops.append(crop[:, :, ::-1].copy())
            img_idx.append(n); side.append(min(f[2], f[3])); score.append(f[14]); norm.append(nr); ordinal.append(k)
        if n % 200 == 0:
            print(f"{n}/{len(ids)} images, {len(vecs)} faces", flush=True)
    np.savez(os.path.join(a.work, "faces.npz"), v=np.stack(vecs), img=np.array(img_idx),
             side=np.array(side, np.float32), score=np.array(score, np.float32),
             norm=np.array(norm, np.float32), ordinal=np.array(ordinal))
    if a.save_crops:
        np.save(os.path.join(a.work, "crops_rgb.npy"), np.stack(crops))
    print(f"images {len(ids)}, faces {len(vecs)}, images with a face {len(set(img_idx))}")


# ArcFace 112x112 template, as OpenCV FaceRecognizerSF::alignCrop and SFaceAlignment.kt.
TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                     [41.5493, 92.3655], [70.7299, 92.2041]], np.float32)


def align(image, marks):
    """Similarity transform (Umeyama, no reflection) + bilinear warp, zero border."""
    src = marks.astype(np.float64)
    dst = TEMPLATE.astype(np.float64)
    ms, md = src.mean(0), dst.mean(0)
    sc, dc = src - ms, dst - md
    cov = dc.T @ sc / 5
    u, d, vt = np.linalg.svd(cov)
    sgn = np.ones(2)
    if np.linalg.det(cov) < 0:
        sgn[1] = -1
    r = u @ np.diag(sgn) @ vt
    scale = (d * sgn).sum() / (sc ** 2).sum() * 5
    m = np.zeros((2, 3))
    m[:, :2] = scale * r
    m[:, 2] = md - scale * r @ ms
    return cv2.warpAffine(image, m, (112, 112), flags=cv2.INTER_LINEAR,
                          borderMode=cv2.BORDER_CONSTANT, borderValue=0)


if __name__ == "__main__":
    main()
