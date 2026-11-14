#!/usr/bin/env python3
"""Turns a wallpaper picture into the three files a scene needs (see launcher SceneView):

  scene_<name>.jpg        the picture, 1920x1080
  scene_<name>_glass.jpg  a heavily blurred, brighter copy: it fills the clock's digits like frosted glass
  scene_<name>_fg.webp    the part of the picture in FRONT of the clock (trees, mountains), the rest transparent,
                          so the digits sit behind the scenery

The foreground is found by cutting the sky out: the sky is the smooth region joined to the seed points (--seed).
A picture with no open sky (a forest, a close-up) does not suit a clock behind the scenery: leave it out.

    python3 tools/make_scene.py in.jpg name outdir --seed 900,40 --seed 1500,60 [--grad 16]

needs: pip install pillow numpy scipy
"""
import argparse, os
import numpy as np
from PIL import Image, ImageFilter, ImageEnhance
from scipy import ndimage as ndi

W, H = 1920, 1080


def sky_mask(im, seeds, grad_max, hole=4000, trim=4):
    a = np.asarray(im.filter(ImageFilter.GaussianBlur(2)), float)
    g = np.sqrt((ndi.sobel(a, axis=1) ** 2 + ndi.sobel(a, axis=0) ** 2).sum(2)) / 8
    low = ndi.binary_opening(g < grad_max, iterations=1)
    lab, _ = ndi.label(low)
    keep = [lab[y, x] for x, y in seeds if lab[y, x] > 0]
    if not keep:
        raise SystemExit("no seed point is on a smooth patch of sky: move it or raise --grad")
    m = ndi.binary_closing(np.isin(lab, keep), iterations=3)
    fg = ~m
    l2, n2 = ndi.label(fg)
    for i, s in enumerate(ndi.sum(fg, l2, range(1, n2 + 1)), 1):
        if s < hole:
            m[l2 == i] = True   # a speck of foreground inside the sky is noise
    # a cloud or a wisp inside the sky is not scenery: only foreground that reaches the lower part of the picture stays
    fg = ~m
    l2, n2 = ndi.label(fg)
    low = set(np.unique(l2[int(H * 0.55):, :])) - {0}
    m = ~np.isin(l2, list(low))
    # the edge pixels of the foreground still carry the sky's light: grow the sky over them
    m = ndi.binary_dilation(m, iterations=trim)
    return ndi.gaussian_filter(m.astype(float), 1.0)


def cover(im, anchor):
    """Fills 1920x1080 without stretching: scales to cover, then crops the excess (anchor 0 = keep the top, 1 = the bottom)."""
    k = max(W / im.width, H / im.height)
    im = im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS)
    x, y = round((im.width - W) / 2), round((im.height - H) * anchor)
    return im.crop((x, y, x + W, y + H))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src"); ap.add_argument("name"); ap.add_argument("out")
    ap.add_argument("--seed", action="append", default=[], help="x,y on the sky (at 1920x1080), repeatable")
    ap.add_argument("--grad", type=float, default=8, help="how smooth the sky is; raise for clouds and auroras")
    ap.add_argument("--shade", help="the scene's Look.shade as AARRGGBB hex: baked into the foreground so it matches the shaded sky behind it")
    ap.add_argument("--anchor", type=float, default=0.5, help="for a picture that is not 16:9: which part to keep, 0 top .. 1 bottom")
    a = ap.parse_args()
    im = cover(Image.open(a.src).convert("RGB"), a.anchor)
    m = sky_mask(im, [tuple(map(int, s.split(","))) for s in a.seed], a.grad)
    os.makedirs(a.out, exist_ok=True)
    p = os.path.join(a.out, "scene_" + a.name)
    im.save(p + ".jpg", quality=88, optimize=True)
    glass = ImageEnhance.Color(ImageEnhance.Brightness(im.filter(ImageFilter.GaussianBlur(40))).enhance(1.12)).enhance(1.35)
    glass.save(p + "_glass.jpg", quality=80, optimize=True)
    fgim = im
    if a.shade:   # SceneView draws this gradient over the picture's top 55% but not over the cut-out, so do it here
        v = int(a.shade, 16)
        alpha, rgb = (v >> 24) / 255.0, np.array([(v >> 16) & 255, (v >> 8) & 255, v & 255], float)
        t = np.clip(1 - np.arange(H) / (H * 0.55), 0, 1)[:, None, None]
        x = np.asarray(im, float)
        al = alpha * t
        fgim = Image.fromarray((x * (1 - al) + rgb * t * al).clip(0, 255).astype("uint8"))
    fg = fgim.convert("RGBA")
    fg.putalpha(Image.fromarray((np.clip(1 - m, 0, 1) * 255).astype("uint8")))
    fg.save(p + "_fg.webp", quality=88, method=6)
    print(p, "sky share %.0f%%" % (m.mean() * 100))


if __name__ == "__main__":
    main()
