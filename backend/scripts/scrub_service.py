"""
Scene-text detection sidecar for the ingest job.

Tesseract cannot read anime title logotypes -- heavily stylised display type,
often light-on-light over busy artwork -- so this uses EasyOCR's CRAFT scene-text
*detector* instead. We only need to know WHERE text is, never what it says, so no
recognition model is involved.

Runs as a long-lived process because loading the model takes several seconds and
the ingest job scrubs hundreds of images:

    stdin   one JSON request per line:  {"in": "cover.jpg", "out": "scrubbed.jpg"}
    stdout  one JSON reply per line:    {"accepted": true, "reason": null, "boxedPct": 0.24}

It prints {"ready": true} once the model is loaded. Send a blank line to stop.

Pillow decodes the input, which is why WebP covers work here -- Java's ImageIO has
no WebP reader, and about a quarter of MAL's covers are WebP.
"""

import json
import sys

import numpy as np
from PIL import Image, ImageDraw

# Pad each detected box outwards to swallow the anti-aliasing halo around glyphs.
PADDING = 6
# A scrub that covers more than this much of the frame leaves too little to guess from.
MAX_BOXED_FRACTION = 0.35
JPEG_QUALITY = 88


def reply(**kwargs):
    sys.stdout.write(json.dumps(kwargs) + "\n")
    sys.stdout.flush()


def detect_boxes(reader, image):
    """Axis-aligned [x0, y0, x1, y1] boxes for every text region found."""
    horizontal, free = reader.detect(np.array(image))
    boxes = []
    # EasyOCR returns horizontal boxes as [x_min, x_max, y_min, y_max].
    for b in (horizontal[0] if horizontal and len(horizontal[0]) else []):
        boxes.append([int(b[0]), int(b[2]), int(b[1]), int(b[3])])
    # Rotated text comes back as a quad; take its bounding box.
    for quad in (free[0] if free and len(free[0]) else []):
        a = np.array(quad)
        boxes.append([int(a[:, 0].min()), int(a[:, 1].min()),
                      int(a[:, 0].max()), int(a[:, 1].max())])
    return boxes


def scrub(reader, in_path, out_path):
    image = Image.open(in_path).convert("RGB")
    width, height = image.size

    boxes = detect_boxes(reader, image)
    # Every cover carries text. Zero detections means detection failed rather than
    # that the cover is clean, and treating that as success is how a leaky image
    # reaches a player.
    if not boxes:
        return False, "no text detected", 0.0

    scrubbed = image.copy()
    draw = ImageDraw.Draw(scrubbed)
    boxed_area = 0
    for x0, y0, x1, y1 in boxes:
        x0 = max(0, x0 - PADDING)
        y0 = max(0, y0 - PADDING)
        x1 = min(width, x1 + PADDING)
        y1 = min(height, y1 + PADDING)
        draw.rectangle([x0, y0, x1, y1], fill=(0, 0, 0))
        boxed_area += (x1 - x0) * (y1 - y0)

    boxed_fraction = boxed_area / float(width * height)
    if boxed_fraction > MAX_BOXED_FRACTION:
        return False, "boxed area too large (%.0f%%)" % (boxed_fraction * 100), boxed_fraction

    # Verify by re-detecting: if any text region still stands, reject. This is a
    # stronger check than re-reading the text would be, because it does not depend
    # on the title being legible to a recogniser.
    survivors = detect_boxes(reader, scrubbed)
    if survivors:
        return False, "%d text region(s) survived" % len(survivors), boxed_fraction

    scrubbed.save(out_path, "JPEG", quality=JPEG_QUALITY)
    return True, None, boxed_fraction


def main():
    import easyocr
    # Japanese as well as English: many covers carry the Japanese title alongside
    # the romanised one, and missing those would defeat the purpose.
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
