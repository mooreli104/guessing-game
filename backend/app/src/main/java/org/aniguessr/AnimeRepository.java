package org.aniguessr;

import java.util.Set;

/**
 * Where the game and the ingest job both meet the anime pool. An interface so tests
 * can substitute a fake, in the same spirit as {@link SessionSender} / RecordingSender.
 */
public interface AnimeRepository {

    void save(Anime anime, byte[] scrubbedJpeg);

    /** The answer for a round. Null when every anime in the pool is excluded. */
    Anime randomExcluding(Set<Integer> usedIds);

    /** Scrubbed JPEG bytes for GET /image/{id}. Null when the id is unknown. */
    byte[] imageBytes(int id);

    /** Ids already stored, so a re-run of ingest can skip them. */
    Set<Integer> existingIds();

    int count();
}
