#!/usr/bin/env python3
"""Regenerates the launcher icon layers and the store icon from art/headlink-icon-source.png.

The source is a rounded-square app icon on a black margin. Android draws its own shape
(a circle on Pixels), so the launcher layer is the artwork with its edges extended outward
to full bleed, sized so the art fills 90% of the 72dp visible area and the circle clips
none of it. The store icon (F-Droid's 512x512) keeps the artwork's own rounded square.

Host tool, not part of the build: needs Python 3 with pillow, numpy and scipy.

    python3 tools/make-icon.py
"""
import os
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOURCE = os.path.join(ROOT, "art/headlink-icon-source.png")
# The artwork's rounded square in the source image, and its corner radius.
X0, Y0, X1, Y1, RADIUS = 118, 118, 1136, 1120, 245
INSET = 6  # stay clear of the anti-aliased edge into the black margin
ART_SHARE = 0.9  # of the 72dp visible area
# Named launcher_art, not ic_launcher_*: lint takes ic_launcher* images for legacy
# launcher icons and rejects a full-bleed square (IconLauncherShape).
DENSITIES = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]


def push_pull(img, known):
    """Fills unknown pixels with a smooth extension of the known ones."""
    if min(img.shape[:2]) <= 8:
        out = img.copy()
        out[~known] = img[known].mean(0) if known.any() else 0
        return out
    h, w = img.shape[0] // 2 * 2, img.shape[1] // 2 * 2
    weights = known[:h, :w].reshape(h // 2, 2, w // 2, 2).sum((1, 3))
    sums = (img[:h, :w] * known[:h, :w, None]).reshape(h // 2, 2, w // 2, 2, 3).sum((1, 3))
    small = np.where(weights[..., None] > 0, sums / np.maximum(weights, 1)[..., None], 0)
    up = np.repeat(np.repeat(push_pull(small, weights > 0), 2, 0), 2, 1)
    up = np.pad(up, ((0, img.shape[0] - h), (0, img.shape[1] - w), (0, 0)), mode="edge")
    up = ndimage.uniform_filter(up, size=(3, 3, 1), mode="nearest")
    out = img.copy()
    out[~known] = up[~known]
    return out


def full_bleed(source):
    crop = np.asarray(source).astype(float)[Y0:Y1, X0:X1]
    h, w, _ = crop.shape
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [INSET, INSET, w - 1 - INSET, h - 1 - INSET], RADIUS - INSET, fill=255)
    inside = np.asarray(mask) > 0
    art = max(w, h)
    side = round(art / ART_SHARE / 72 * 108)
    canvas = np.zeros((side, side, 3))
    known = np.zeros((side, side), bool)
    oy, ox = (side - h) // 2, (side - w) // 2
    canvas[oy:oy + h, ox:ox + w][inside] = crop[inside]
    known[oy:oy + h, ox:ox + w] = inside
    filled = push_pull(canvas, known)
    # Feather the seam between the artwork and its extension.
    alpha = np.clip(ndimage.distance_transform_edt(known) / 6.0, 0, 1)[..., None]
    filled = filled * alpha + ndimage.gaussian_filter(filled, (4, 4, 0)) * (1 - alpha)
    return Image.fromarray(np.clip(filled, 0, 255).astype(np.uint8))


def main():
    source = Image.open(SOURCE).convert("RGB")
    layer = full_bleed(source)
    for density, px in DENSITIES:
        out = os.path.join(ROOT, f"app/src/main/res/mipmap-{density}/launcher_art.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        layer.resize((px, px), Image.LANCZOS).save(out, optimize=True)
    store = source.crop((X0, Y0, X1, X1 - X0 + Y0))
    mask = Image.new("L", store.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, store.width - 1, store.height - 1], RADIUS, fill=255)
    store.putalpha(mask)
    out = os.path.join(ROOT, "fastlane/metadata/android/en-US/images/icon.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    store.resize((512, 512), Image.LANCZOS).save(out, optimize=True)


if __name__ == "__main__":
    main()
