package org.aniguessr;

import java.util.Set;

/**
 * Where the game and the ingest job both meet the anime pool. An interface so tests
 * can substitute a fake, in the same spirit as {@link SessionSender} / RecordingSender.
 */
public interface AnimeRepository {

    void save(Anime anime, byte[] scrubbedJpeg);

    /**
     * The answer for a round, drawn from anime ranked no worse than {@code maxRank}.
     * That cap is how difficulty is applied — see {@code GameManager.rankCapFor}. Pass
     * {@link Integer#MAX_VALUE} for the whole pool.
     *
     * Null when nothing is left: every candidate excluded, or none inside the cap.
     */
    Anime randomExcluding(Set<Integer> usedIds, int maxRank);

    /** Scrubbed JPEG bytes for GET /image/{id}. Null when the id is unknown. */
    byte[] imageBytes(int id);

    /** Ids already stored, so a re-run of ingest can skip them. */
    Set<Integer> existingIds();

    int count();
}
