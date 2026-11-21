"""Draws the CouchMouse brand images: a white mouse cursor resting on a couch, on a violet
gradient. Run: python branding/make_icons.py  (needs Pillow). Writes into branding/out/ and the
TV app's res/ folders."""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
RES = os.path.join(HERE, "..", "tv", "src", "main", "res")
TOP, BOTTOM = (108, 92, 255), (52, 34, 168)      # gradient, top-left to bottom-right
COUCH, COUCH_DARK = (201, 196, 255), (163, 154, 250)
FONT = r"C:\Windows\Fonts\seguisb.ttf"


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return img


def glyph(size):
    """The couch + cursor drawing on a transparent square of the given size (4x supersampled)."""
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = S / 100.0
    # Couch: backrest, seat, two armrests, little feet.
    d.rounded_rectangle([18 * u, 44 * u, 82 * u, 70 * u], radius=8 * u, fill=COUCH_DARK)       # backrest
    d.rounded_rectangle([24 * u, 60 * u, 76 * u, 76 * u], radius=5 * u, fill=COUCH)            # seat
    d.rounded_rectangle([12 * u, 54 * u, 28 * u, 80 * u], radius=6 * u, fill=COUCH)            # left arm
    d.rounded_rectangle([72 * u, 54 * u, 88 * u, 80 * u], radius=6 * u, fill=COUCH)            # right arm
    d.rectangle([17 * u, 80 * u, 22 * u, 85 * u], fill=COUCH_DARK)
    d.rectangle([78 * u, 80 * u, 83 * u, 85 * u], fill=COUCH_DARK)
    # Cursor arrow, tip at upper left, resting on the seat; soft shadow first.
    ox, oy, k = 37 * u, 14 * u, 2.35 * u
    pts = [(1, 1), (1, 19), (6, 14.5), (9.5, 22), (13, 20.5), (9.5, 13.5), (16, 13.5)]
    arrow = [(ox + x * k, oy + y * k) for x, y in pts]
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x + 2 * u, y + 3 * u) for x, y in arrow], fill=(20, 10, 70, 110))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(2.5 * u)))
    d = ImageDraw.Draw(img)
    d.polygon(arrow, fill=(255, 255, 255, 255), outline=(30, 22, 90, 255), width=round(1.3 * u))
    return img.resize((size, size), Image.LANCZOS)


def app_icon(size, rounded=False):
    bg = gradient(size, size).convert("RGBA")
    g = glyph(round(size * 0.86))
    off = (size - g.width) // 2
    bg.alpha_composite(g, (off, off + round(size * 0.02)))
    if rounded:
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=size * 4 * 0.22, fill=255)
        bg.putalpha(mask.resize((size, size), Image.LANCZOS))
    return bg


def banner(w, h, label):
    img = gradient(w, h).convert("RGBA")
    g = glyph(round(h * 0.82))
    img.alpha_composite(g, (round(h * 0.06), round(h * 0.09)))
    d = ImageDraw.Draw(img)
    x = round(h * 0.06) + g.width + round(h * 0.02)
    size = round(h * 0.22)
    font = ImageFont.truetype(FONT, size)
    while font.getlength(label) > w - x - h * 0.08 and size > 8:  # shrink until it fits
        size -= 1
        font = ImageFont.truetype(FONT, size)
    d.text((x, h * 0.5), label, font=font, fill=(255, 255, 255), anchor="lm")
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path, HERE))


if __name__ == "__main__":
    save(app_icon(1024).convert("RGB"), os.path.join(OUT, "icon-1024.png"))   # App Store (no alpha)
    save(app_icon(512).convert("RGB"), os.path.join(OUT, "icon-512.png"))     # Google Play
    save(banner(1280, 720, "CouchMouse"), os.path.join(OUT, "tv-banner-1280x720.png"))
    for d, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(app_icon(px, rounded=True), os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
    save(banner(320, 180, "CouchMouse"), os.path.join(RES, "drawable-xhdpi", "banner.png"))
