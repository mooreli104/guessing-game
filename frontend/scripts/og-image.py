"""Draws public/og.png, the 1200x630 card Discord and Twitter show for a shared link.

Run by hand when the card needs changing -- it is not part of `npm run build`:

    python scripts/og-image.py

The result is committed, so deploying the game needs no Python. Requires `npm install`
to have run, since the fonts are read out of node_modules.

Colours and proportions are copied from src/styles.css, which is the source of truth.
A preview card cannot share that stylesheet -- this is a raster image -- so the values
are duplicated here deliberately.

Fonts come from the .woff files rather than the .woff2 beside them: WOFF1 is
zlib-compressed, which fontTools reads with no extra dependency, while WOFF2 needs
brotli. fontTools flattens either to a TTF that PIL can load.
"""

import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

FRONTEND = Path(__file__).resolve().parent.parent
OUT = FRONTEND / "public" / "og.png"

# 1.91:1, the aspect every large-image card crops to.
WIDTH, HEIGHT = 1200, 630

GRAPE_DEEP = (36, 18, 69)     # --grape-deep #241245
GRAPE_GLOW = (85, 45, 156)    # the #552d9c at the centre of body's radial gradient
GRAPE_LINE = (23, 11, 46)     # --grape-line #170b2e
CREAM = (255, 246, 236)       # --cream  #fff6ec
SUN = (255, 201, 60)          # --sun    #ffc93c
PUNCH = (255, 77, 109)        # --punch  #ff4d6d
TAGLINE_MAUVE = (201, 184, 232)  # the .tagline colour #c9b8e8

TAGLINE = ["The title is scrubbed off the cover.", "Type it first."]


def font(family, weight, size):
    """Load a bundled @fontsource face at `size`, via a temporary flattened TTF."""
    woff = FRONTEND / "node_modules" / "@fontsource" / family / "files" / f"{family}-latin-{weight}-normal.woff"
    if not woff.exists():
        raise SystemExit(f"missing {woff} -- run `npm install` first")

    flattened = TTFont(woff)
    flattened.flavor = None  # drop the WOFF wrapper; PIL only reads bare TTF/OTF
    ttf = Path(tempfile.gettempdir()) / f"{woff.stem}.ttf"
    flattened.save(ttf)
    return ImageFont.truetype(str(ttf), size)


def canvas():
    """The page background: a violet glow rising from the top edge over --grape-deep.

    Mirrors `body`'s `radial-gradient(circle at 50% 0%, #552d9c 0%, transparent 60%)`.
    Painted small and scaled up -- per-pixel work over 756k pixels is slow in Python,
    and a gradient has no detail that upscaling can lose.
    """
    small_w, small_h = WIDTH // 6, HEIGHT // 6
    gradient = Image.new("RGB", (small_w, small_h), GRAPE_DEEP)
    pixels = gradient.load()

    centre_x, centre_y = small_w / 2, 0.0
    # CSS sizes this gradient to the farthest corner, then fades out by 60% of that.
    farthest = (centre_x**2 + small_h**2) ** 0.5
    fade = farthest * 0.6

    for y in range(small_h):
        for x in range(small_w):
            distance = ((x - centre_x) ** 2 + (y - centre_y) ** 2) ** 0.5
            strength = max(0.0, 1.0 - distance / fade)
            pixels[x, y] = tuple(
                round(deep + (glow - deep) * strength)
                for deep, glow in zip(GRAPE_DEEP, GRAPE_GLOW)
            )

    return gradient.resize((WIDTH, HEIGHT), Image.BICUBIC)


def draw_cover(draw):
    """The favicon's motif at card scale: a cover with its title blanked out.

    Kept to the game's palette rather than the favicon's red, since it sits on the
    violet canvas here. The offset dark shape beneath it is the same bottom lip every
    pressable thing in the interface carries.
    """
    left, top, right, bottom = 110, 120, 370, 510
    radius = 18  # --radius

    draw.rounded_rectangle((left, top + 14, right, bottom + 14), radius, fill=GRAPE_LINE)
    draw.rounded_rectangle((left, top, right, bottom), radius, fill=CREAM)

    # The scrubbed-out title band, at the same proportions as the favicon.
    band_top = top + round((bottom - top) * 0.63)
    draw.rounded_rectangle((left + 40, band_top, right - 40, band_top + 44), 8, fill=GRAPE_LINE)

    mark = font("baloo-2", 800, 150)
    draw.text(((left + right) / 2, band_top - 30), "?", font=mark, fill=PUNCH, anchor="ms")


def draw_wordmark(draw):
    """"Otaku" in cream, "Guessr" in sun -- the h1 from HomeScreen, drop shadow included."""
    wordmark = font("baloo-2", 800, 104)
    x, baseline = 440, 330
    shadow = 7  # `text-shadow: 0 4px 0` at the 62px heading scale, kept proportional

    for word, colour in (("Otaku", CREAM), ("Guessr", SUN)):
        draw.text((x, baseline + shadow), word, font=wordmark, fill=GRAPE_LINE, anchor="ls")
        draw.text((x, baseline), word, font=wordmark, fill=colour, anchor="ls")
        x += draw.textlength(word, font=wordmark)

    tagline = font("nunito", 700, 33)
    for i, line in enumerate(TAGLINE):
        draw.text((440, 400 + i * 46), line, font=tagline, fill=TAGLINE_MAUVE, anchor="ls")


def main():
    image = canvas()
    draw = ImageDraw.Draw(image)
    draw_cover(draw)
    draw_wordmark(draw)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT, optimize=True)
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
