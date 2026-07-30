package org.aniguessr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

/**
 * Finds the title text printed into a cover image and paints over it.
 *
 * Pure image-in, image-out: no database and no network, which is what makes it
 * unit-testable. Not thread-safe — the underlying Tesseract handle isn't — but ingest
 * is single-threaded so that costs nothing.
 */
public class TitleScrubber {

    private static final int MIN_CONFIDENCE = 60;
    private static final int PADDING = 4;               // catch the anti-aliasing halo
    private static final double MAX_BOXED_FRACTION = 0.35;

    private final Tesseract engine;

    public record ScrubResult(BufferedImage image, String rejectReason) {
        public boolean accepted() { return rejectReason == null; }
    }

    public TitleScrubber() {
        this.engine = new Tesseract();
        String tessdata = System.getenv("TESSDATA_PREFIX");
        if (tessdata != null && !tessdata.isBlank()) {
            engine.setDatapath(tessdata);
        }
        // Many covers carry the Japanese title alongside the romanised one; missing
        // those would defeat the purpose.
        engine.setLanguage("eng+jpn");
    }

    // Text lines Tesseract is reasonably confident about.
    private List<Rectangle> detect(BufferedImage image) {
        List<Rectangle> boxes = new ArrayList<>();
        for (Word word : engine.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE)) {
            if (word.getConfidence() >= MIN_CONFIDENCE) boxes.add(word.getBoundingBox());
        }
        return boxes;
    }

    public ScrubResult scrub(BufferedImage cover, Anime anime) {
        List<Rectangle> boxes = detect(cover);

        // Every cover carries text. Zero detections means OCR failed rather than that the
        // cover is clean, and treating an empty result as success is how a leaky image
        // reaches a player.
        if (boxes.isEmpty()) {
            return new ScrubResult(null, "no text detected");
        }

        BufferedImage scrubbed = new BufferedImage(
            cover.getWidth(), cover.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scrubbed.createGraphics();
        g.drawImage(cover, 0, 0, null);
        g.setColor(Color.BLACK);

        long boxedArea = 0;
        for (Rectangle box : boxes) {
            int x = Math.max(0, box.x - PADDING);
            int y = Math.max(0, box.y - PADDING);
            int w = Math.min(cover.getWidth() - x, box.width + PADDING * 2);
            int h = Math.min(cover.getHeight() - y, box.height + PADDING * 2);
            g.fillRect(x, y, w, h);
            boxedArea += (long) w * h;
        }
        g.dispose();

        // The scrub may have worked while leaving too little picture to guess from.
        long total = (long) cover.getWidth() * cover.getHeight();
        if ((double) boxedArea / total > MAX_BOXED_FRACTION) {
            return new ScrubResult(null, "boxed area too large");
        }

        // Verify: re-read the scrubbed image, and reject if anything still looks like the
        // answer. Reuses Anime.isCorrect, which already answers "is this string close to
        // one of my titles". Slightly over-eager, since OCR garbage can trip the
        // base.contains branch, but that costs a rejected anime rather than a leaked one.
        for (Word word : engine.getWords(scrubbed, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE)) {
            String line = word.getText().trim();
            if (!line.isEmpty() && anime.isCorrect(line)) {
                return new ScrubResult(null, "title survived the scrub");
            }
        }

        return new ScrubResult(scrubbed, null);
    }
}
