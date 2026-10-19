#!/usr/bin/env python3
"""Fetch the exact, bundled MediaPipe models; no downloads happen in the app."""
import hashlib
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS = {
    'face_landmarker.task': (
        'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task',
        '64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff'),
    'selfie_segmenter.tflite': (
        'https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite',
        '191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b'),
}


def main():
    dest = ROOT / 'redmi-vision/app/src/main/assets'
    dest.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in MODELS.items():
        target = dest / name
        data = target.read_bytes() if target.exists() else b''
        if hashlib.sha256(data).hexdigest() != expected:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(16 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Model SHA256 mismatch: ' + name)
        target.write_bytes(data)
        print(f'{name}: verified {expected} ({len(data)} bytes)')


if __name__ == '__main__':
    main()
