#!/usr/bin/env python3
"""Validate shipped, editable CC0 mask art and its embedded PNG payloads."""
import base64, io, re
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = root / 'assets/masks'
assert source.exists(), 'Missing editable face-mask sources'
java = (root / 'v84/MaskAssets.java').read_text()
payloads = re.findall(r'// PNG (\d+)\n\s*"([A-Za-z0-9+/=]+)"', java)
assert len(payloads) == 8, 'Eight embedded mask textures required'
alphas = []
for slot, encoded in payloads:
    im = Image.open(io.BytesIO(base64.b64decode(encoded))).convert('RGBA')
    assert im.size == (512, 512)
    assert (source / (slot + '.svg')).exists()
    alpha = im.getchannel('A')
    assert alpha.getextrema() == (0, 255), 'Must include transparent openings and opaque art'
    for point in ((176,194),(336,194),(256,355)):
        assert alpha.getpixel(point) == 0, 'Eyes and mouth must remain open'
    assert 1500 < sum(v > 20 for v in alpha.tobytes()) < 220000
    alphas.append(alpha.tobytes())
assert len(set(alphas)) == 8, 'Styles must have different coverage, not only recoloring'
assert 'CC0' in (source / 'LICENSE.txt').read_text()
print('Eight editable CC0 masks: PNG embedding, coverage, and distinct silhouettes pass')
