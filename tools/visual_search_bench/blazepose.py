#!/usr/bin/env python3
"""Minimal MediaPipe person-detector + BlazePose runner on ONNX Runtime.

A clean re-implementation of the pre/post-processing that OpenCV Zoo's
Apache-2.0 demo uses (models/person_detection_mediapipe, models/
pose_estimation_mediapipe @ 48b10fa), reduced to what the descriptor
needs: the 33 metric "world" landmarks of the most confident person.
Kept small and dependency-light so VS-S1 can port it to Kotlin 1:1.
"""
import numpy as np
import cv2
import onnxruntime as ort

DET_SIZE = 224
POSE_SIZE = 256


def ssd_anchors():
    """2254 anchor centres: 28x28x2, 14x14x2, 7x7x6 (strides 8, 16, 32x3)."""
    out = []
    for n, per in [(28, 2), (14, 2), (7, 6)]:
        for y in range(n):
            for x in range(n):
                out.extend([[(x + 0.5) / n, (y + 0.5) / n]] * per)
    return np.asarray(out, np.float32)


def _sess(path, threads):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


class BlazePose:
    def __init__(self, det_path, pose_path, threads=1, det_score=0.5, nms=0.3, pose_conf=0.8):
        self.det = _sess(det_path, threads)
        self.pose = _sess(pose_path, threads)
        self.anchors = ssd_anchors()
        self.det_score, self.nms, self.pose_conf = det_score, nms, pose_conf
        self.det_in = self.det.get_inputs()[0].name
        self.pose_in = self.pose.get_inputs()[0].name
        names = [o.name for o in self.det.get_outputs()]
        self.det_reg = names.index([n for n, o in zip(names, self.det.get_outputs()) if o.shape[-1] == 12][0])
        self.det_cls = 1 - self.det_reg

    def detect(self, rgb):
        """rgb uint8 HxWx3 -> best person as (score, hip_xy, full_body_xy) or None."""
        h, w = rgb.shape[:2]
        ratio = min(DET_SIZE / h, DET_SIZE / w)
        nh, nw = int(h * ratio), int(w * ratio)
        img = cv2.resize(rgb.astype(np.float32) / 127.5 - 1.0, (nw, nh))
        left, top = (DET_SIZE - nw) // 2, (DET_SIZE - nh) // 2
        canvas = np.zeros((DET_SIZE, DET_SIZE, 3), np.float32)
        canvas[top:top + nh, left:left + nw] = img
        outs = self.det.run(None, {self.det_in: canvas.transpose(2, 0, 1)[None]})
        reg, cls = outs[self.det_reg][0], outs[self.det_cls][0, :, 0]
        score = 1 / (1 + np.exp(-np.clip(cls.astype(np.float64), -100, 100)))
        scale = max(w, h)
        pad = np.array([left, top], np.float32) / ratio
        cxy = reg[:, :2] / DET_SIZE + self.anchors
        wh = reg[:, 2:4] / DET_SIZE
        boxes = np.concatenate([(cxy - wh / 2) * scale - pad, (cxy + wh / 2) * scale - pad], 1)
        xywh = np.c_[boxes[:, :2], boxes[:, 2:] - boxes[:, :2]]
        keep = cv2.dnn.NMSBoxes(xywh.tolist(), score.tolist(), self.det_score, self.nms)
        if len(keep) == 0:
            return None
        i = int(np.asarray(keep).reshape(-1)[np.argmax(score[np.asarray(keep).reshape(-1)])])
        kp = (reg[i, 4:12].reshape(4, 2) / DET_SIZE + self.anchors[i]) * scale - pad
        return float(score[i]), kp[0], kp[1]

    def world_landmarks(self, rgb):
        """Returns (33, 3) metric landmarks (y down, hip-centred) or None."""
        d = self.detect(rgb)
        if d is None:
            return None
        _, hip, full = d
        r = np.linalg.norm(hip - full)
        if r < 4:
            return None
        # Square RoI centred on the hip, radius = hip->full-body distance,
        # rotated so the body axis points up (as MediaPipe does).
        ang = np.pi / 2 - np.arctan2(-(full[1] - hip[1]), full[0] - hip[0])
        ang = ang - 2 * np.pi * np.floor((ang + np.pi) / (2 * np.pi))
        deg = float(np.rad2deg(ang))
        m = cv2.getRotationMatrix2D((float(hip[0]), float(hip[1])), deg, 1.0)
        # Map RoI -> 256x256: rotate about hip, then translate/scale.
        s = POSE_SIZE / (2 * r)
        a = np.vstack([m, [0, 0, 1]])
        t = np.array([[s, 0, POSE_SIZE / 2 - s * hip[0]], [0, s, POSE_SIZE / 2 - s * hip[1]], [0, 0, 1]])
        crop = cv2.warpAffine(rgb, (t @ a)[:2], (POSE_SIZE, POSE_SIZE), flags=cv2.INTER_AREA,
                              borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        outs = self.pose.run(None, {self.pose_in: (crop.astype(np.float32) / 255.0)[None]})
        by_shape = {o.shape[-1]: v for o, v in zip(self.pose.get_outputs(), outs) if len(o.shape) == 2}
        conf = float(by_shape[1][0, 0])
        if conf < self.pose_conf:
            return None
        world = by_shape[117][0].reshape(39, 3)[:33]
        # Undo the RoI rotation so "down" is the image's down again.
        rot = cv2.getRotationMatrix2D((0, 0), deg, 1.0)[:, :2]
        xy = world[:, :2] @ rot
        return np.c_[xy, world[:, 2]].astype(np.float32)
