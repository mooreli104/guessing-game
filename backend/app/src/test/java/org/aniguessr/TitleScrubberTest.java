package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real sidecar. Guarded on SCRUB_PYTHON so `gradlew test` stays green on a
 * machine without easyocr installed.
 *
 * Note these are integration tests by necessity: the detector is the thing under test, and
 * a fake would only prove the protocol works. Synthetic covers were what made the previous
 * Tesseract implementation look correct while it leaked on every real cover, so the
 * accept-path assertions here deliberately check that text is actually gone from the
 * output, not merely that a result came back.
 */
@EnabledIfEnvironmentVariable(named = "SCRUB_PYTHON", matches = ".+")
class TitleScrubberTest {

    private static File script() {
        // Tests run with the `app` directory as the working directory.
        return new File("../scripts/scrub_service.py").getAbsoluteFile();
    }

    private File coverWithText(Path dir, String text, int fontSize, int baselineY) throws Exception {
        BufferedImage img = new BufferedImage(450, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 60, 90));
        g.fillRect(0, 0, 450, 640);
        if (text != null) {
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, fontSize));
            g.drawString(text, 25, baselineY);
        }
        g.dispose();
        File f = dir.resolve("cover-" + System.nanoTime() + ".png").toFile();
        ImageIO.write(img, "png", f);
        return f;
    }

    @Test
    void textIsDetectedBlankedAndTheResultIsWritten(@TempDir Path dir) throws Exception {
        File cover = coverWithText(dir, "Naruto", 52, 70);
        File out = dir.resolve("scrubbed.jpg").toFile();

        try (TitleScrubber scrubber = new TitleScrubber(script())) {
            TitleScrubber.ScrubResult result = scrubber.scrub(cover, out);

            assertTrue(result.accepted(), "expected accept, got: " + result.rejectReason());
            assertTrue(out.exists(), "scrubbed file should have been written");
            assertTrue(result.boxedFraction() > 0.0, "some area should have been boxed");

            BufferedImage scrubbed = ImageIO.read(out);
            assertNotNull(scrubbed);
            assertEquals(450, scrubbed.getWidth());
            // The band the text sat in should now be black.
            assertTrue(isNearBlack(scrubbed.getRGB(60, 55)),
                "expected the title band to be blacked out");
        }
    }

    @Test
    void blankCover_isRejectedForZeroDetections(@TempDir Path dir) throws Exception {
        File cover = coverWithText(dir, null, 0, 0);
        File out = dir.resolve("scrubbed.jpg").toFile();

        try (TitleScrubber scrubber = new TitleScrubber(script())) {
            TitleScrubber.ScrubResult result = scrubber.scrub(cover, out);

            assertFalse(result.accepted());
            assertTrue(result.rejectReason().contains("no text"),
                "expected a no-text rejection, got: " + result.rejectReason());
            assertFalse(out.exists(), "nothing should be written for a rejected cover");
        }
    }

    @Test
    void missingFile_isRejectedWithoutKillingTheService(@TempDir Path dir) throws Exception {
        File out = dir.resolve("scrubbed.jpg").toFile();

        try (TitleScrubber scrubber = new TitleScrubber(script())) {
            TitleScrubber.ScrubResult bad =
                scrubber.scrub(dir.resolve("nope.jpg").toFile(), out);
            assertFalse(bad.accepted());
            assertNotNull(bad.rejectReason());

            // The service must still answer the next request -- one bad cover out of 500
            // must not end the ingest run.
            File cover = coverWithText(dir, "Bleach", 52, 70);
            TitleScrubber.ScrubResult good = scrubber.scrub(cover, out);
            assertTrue(good.accepted(), "service should survive a bad request, got: "
                + good.rejectReason());
        }
    }

    private static boolean isNearBlack(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return r < 40 && g < 40 && b < 40;   // JPEG is lossy, so allow a little drift
    }
}
