package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Test covers are generated in-process, so there are no fixture files and no network.
 * Skipped when TESSDATA_PREFIX is unset so `gradlew test` stays green without Tesseract.
 */
@EnabledIfEnvironmentVariable(named = "TESSDATA_PREFIX", matches = ".+")
class TitleScrubberTest {

    private static final int WIDTH = 450;
    private static final int HEIGHT = 640;

    // A plain coloured cover, optionally with a line of white text drawn on it.
    private BufferedImage cover(String text, int fontSize, int baselineY) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(70, 110, 160));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        if (text != null) {
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, fontSize));
            g.drawString(text, 20, baselineY);
        }
        g.dispose();
        return img;
    }

    @Test
    void titleNearTop_isPaintedOut() {
        Anime anime = new Anime(1, "u", List.of("Naruto"));
        TitleScrubber.ScrubResult result = new TitleScrubber().scrub(cover("Naruto", 48, 60), anime);

        assertTrue(result.accepted(), "expected accept, got: " + result.rejectReason());
        assertNotNull(result.image());

        // The band the text sat in should now be solid black.
        assertEquals(Color.BLACK.getRGB(), result.image().getRGB(40, 40));
        // Artwork well away from the title must survive.
        assertEquals(new Color(70, 110, 160).getRGB(), result.image().getRGB(225, 500));
    }

    @Test
    void textCoveringMostOfTheFrame_isRejectedForArea() {
        Anime anime = new Anime(1, "u", List.of("Naruto"));
        BufferedImage img = cover(null, 0, 0);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 72));
        for (int y = 70; y < HEIGHT; y += 80) g.drawString("NARUTO", 10, y);
        g.dispose();

        TitleScrubber.ScrubResult result = new TitleScrubber().scrub(img, anime);
        assertFalse(result.accepted(), "expected rejection for area");
        assertTrue(result.rejectReason().contains("area"),
            "expected an area rejection, got: " + result.rejectReason());
    }

    @Test
    void blankImage_isRejectedForZeroDetections() {
        Anime anime = new Anime(1, "u", List.of("Naruto"));
        TitleScrubber.ScrubResult result = new TitleScrubber().scrub(cover(null, 0, 0), anime);

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("no text"),
            "expected a no-text rejection, got: " + result.rejectReason());
    }
}
