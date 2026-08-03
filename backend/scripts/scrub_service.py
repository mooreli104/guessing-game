"""
Scene-text detection sidecar for the ingest job.

Tesseract cannot read anime title logotypes -- heavily stylised display type,
often light-on-light over busy artwork -- so this uses EasyOCR's CRAFT scene-text
detector instead.

Detection alone can't tell an English title from a Japanese one, and guesses are
always typed in English/romaji, so a visible Japanese-only logo doesn't hand over
the answer the way a visible English one does. Recognising each detected box (not
just locating it) lets Japanese-only text stay on the cover while English text is
still blanked. Recognition on these stylised fonts is unreliable, so any box that
isn't confidently Japanese-only -- including recognition failing outright -- is
blanked; the risk we're managing is leaking the English answer, not leaving a few
extra Japanese boxes covered.

Runs as a long-lived process because loading the model takes several seconds and
the ingest job scrubs hundreds of images:

    stdin   one JSON request per line:  {"in": "cover.jpg", "out": "scrubbed.jpg"}
    stdout  one JSON reply per line:    {"accepted": true, "reason": null, "boxedPct": 0.24}

It prints {"ready": true} once the model is loaded. Send a blank line to stop.

Pillow decodes the input, which is why WebP covers work here -- Java's ImageIO has
no WebP reader, and about a quarter of MAL's covers are WebP.
"""

import json
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

# Pad each detected box outwards to swallow the anti-aliasing halo around glyphs.
PADDING = 6
# A scrub that covers more than this much of the frame leaves too little to guess from.
MAX_BOXED_FRACTION = 0.35
JPEG_QUALITY = 88
# A skipped Japanese box is only "the same box" on re-detection if most of it lines up.
OVERLAP_THRESHOLD = 0.5

_LATIN_LETTER = re.compile(r"[A-Za-z]")
_JAPANESE_ONLY = re.compile(r"^[぀-ゟ゠-ヿ一-鿿]+$")


def reply(**kwargs):
    sys.stdout.write(json.dumps(kwargs) + "\n")
    sys.stdout.flush()


def classify(text):
    """"latin" (must be blanked), "japanese" (safe to leave alone), or "ambiguous"
    (blanked like latin -- empty/garbled recognition might still be English hiding
    in a stylised font)."""
    if _LATIN_LETTER.search(text):
        return "latin"
    if _JAPANESE_ONLY.match(text.replace(" ", "")):
        return "japanese"
    return "ambiguous"


def read_regions(reader, image):
    """[(x0, y0, x1, y1, text), ...] for every text region found, axis-aligned."""
    regions = []
    for quad, text, _confidence in reader.readtext(np.array(image)):
        a = np.array(quad)
        regions.append((
            int(a[:, 0].min()), int(a[:, 1].min()),
            int(a[:, 0].max()), int(a[:, 1].max()),
            text,
        ))
    return regions


def _overlaps(box, others):
    x0, y0, x1, y1 = box
    area = (x1 - x0) * (y1 - y0)
    if area <= 0:
        return False
    for ox0, oy0, ox1, oy1 in others:
        ix0, iy0 = max(x0, ox0), max(y0, oy0)
        ix1, iy1 = min(x1, ox1), min(y1, oy1)
        if ix1 <= ix0 or iy1 <= iy0:
            continue
        if (ix1 - ix0) * (iy1 - iy0) / area > OVERLAP_THRESHOLD:
            return True
    return False


def scrub(reader, in_path, out_path):
    image = Image.open(in_path).convert("RGB")
    width, height = image.size

    regions = read_regions(reader, image)
    # Every cover carries text. Zero detections means detection failed rather than
    # that the cover is clean, and treating that as success is how a leaky image
    # reaches a player.
    if not regions:
        return False, "no text detected", 0.0

    scrubbed = image.copy()
    draw = ImageDraw.Draw(scrubbed)
    boxed_area = 0
    skipped_boxes = []
    for x0, y0, x1, y1, text in regions:
        if classify(text) == "japanese":
            skipped_boxes.append((x0, y0, x1, y1))
            continue
        x0 = max(0, x0 - PADDING)
        y0 = max(0, y0 - PADDING)
        x1 = min(width, x1 + PADDING)
        y1 = min(height, y1 + PADDING)
        draw.rectangle([x0, y0, x1, y1], fill=(0, 0, 0))
        boxed_area += (x1 - x0) * (y1 - y0)

    boxed_fraction = boxed_area / float(width * height)
    if boxed_fraction > MAX_BOXED_FRACTION:
        return False, "boxed area too large (%.0f%%)" % (boxed_fraction * 100), boxed_fraction

    # Verify by re-detecting: a surviving Japanese box is expected -- it's the one we
    # deliberately left -- as long as it lines up with a box we chose to skip above.
    # Anything else surviving (new text, or anything not confidently Japanese-only)
    # means a leak.
    survivors = read_regions(reader, scrubbed)
    leaks = [
        region for region in survivors
        if not (classify(region[4]) == "japanese" and _overlaps(region[:4], skipped_boxes))
    ]
    if leaks:
        return False, "%d text region(s) survived" % len(leaks), boxed_fraction

    scrubbed.save(out_path, "JPEG", quality=JPEG_QUALITY)
    return True, None, boxed_fraction


def main():
    import easyocr
    # Japanese as well as English: classify() needs to tell the two apart, and a
    # reader without "ja" loaded would just fail to recognise Japanese text at all.
    reader = easyocr.Reader(["en", "ja"], gpu=False, verbose=False)
    reply(ready=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            break
        try:
            request = json.loads(line)
            accepted, reason, fraction = scrub(reader, request["in"], request["out"])
            reply(accepted=accepted, reason=reason, boxedPct=round(fraction, 4))
        except Exception as e:
            # One bad cover must not kill the service.
            reply(accepted=False, reason="%s: %s" % (type(e).__name__, e), boxedPct=0.0)


if __name__ == "__main__":
    main()
