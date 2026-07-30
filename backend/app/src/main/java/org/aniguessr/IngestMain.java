package org.aniguessr;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Offline batch job: downloads the top POOL_SIZE covers, scrubs the titles out of them,
 * and stores whatever passes. Never runs at the same time as the game.
 *
 * Run with `gradlew ingest`. Re-running is cheap -- anime already stored are skipped.
 * To rebuild from scratch, TRUNCATE the anime table first.
 *
 * The cover is written to a temp file and handed to the scrub service by path, so Python
 * does the decoding. That is also why WebP covers work -- roughly a quarter of MAL's
 * covers are WebP, which Java's ImageIO cannot read at all.
 */
public class IngestMain {

    private static final int POOL_SIZE = 500;
    private static final int PAGE_SIZE = 100;

    public static void main(String[] args) throws Exception {
        AnimeRepository repository = new PostgresAnimeRepository(new Db());
        HttpClient http = HttpClient.newHttpClient();
        Path work = Files.createTempDirectory("aniguessr-ingest");
        File script = new File("../scripts/scrub_service.py").getAbsoluteFile();

        // Read up front so a re-run resumes cheaply.
        Set<Integer> existing = repository.existingIds();
        int accepted = 0;
        int rejected = 0;
        int skipped = 0;

        System.out.println("starting the scrub service (loading the detection model)...");
        try (TitleScrubber scrubber = new TitleScrubber(script)) {
            for (int offset = 0; offset < POOL_SIZE; offset += PAGE_SIZE) {
                List<Anime> page = MalClient.fetchPage(offset, PAGE_SIZE);

                for (Anime anime : page) {
                    if (existing.contains(anime.getId())) {
                        skipped++;
                        continue;
                    }
                    try {
                        String url = anime.getUrl();
                        String ext = url.endsWith(".webp") ? ".webp" : ".jpg";
                        File cover = work.resolve(anime.getId() + ext).toFile();
                        File scrubbed = work.resolve(anime.getId() + "-scrubbed.jpg").toFile();

                        byte[] original = http.send(
                            HttpRequest.newBuilder().uri(URI.create(url)).build(),
                            HttpResponse.BodyHandlers.ofByteArray()).body();
                        Files.write(cover.toPath(), original);

                        TitleScrubber.ScrubResult result = scrubber.scrub(cover, scrubbed);
                        if (!result.accepted()) {
                            System.out.printf("SKIP  %-7d %-34s %s%n",
                                anime.getId(), result.rejectReason(), anime.getTitles().get(0));
                            rejected++;
                            continue;
                        }

                        repository.save(anime, Files.readAllBytes(scrubbed.toPath()));
                        accepted++;
                        System.out.printf("OK    %-7d boxed=%4.0f%%  %s%n",
                            anime.getId(), result.boxedFraction() * 100, anime.getTitles().get(0));

                        Files.deleteIfExists(cover.toPath());
                        Files.deleteIfExists(scrubbed.toPath());
                    } catch (Exception e) {
                        // One bad anime must not end the run.
                        System.out.printf("SKIP  %-7d %s%n", anime.getId(), e);
                        rejected++;
                    }
                }
            }
        }

        System.out.println();
        System.out.println("accepted " + accepted + ", rejected " + rejected
            + ", already had " + skipped);
        System.out.println("pool is now " + repository.count());
    }
}
