package org.aniguessr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link AnimeRepository} for tests, so no test needs a database.
 */
class FakeAnimeRepository implements AnimeRepository {

    private final Map<Integer, Anime> anime = new HashMap<>();
    private final Map<Integer, byte[]> images = new HashMap<>();

    @Override
    public void save(Anime a, byte[] scrubbedJpeg) {
        anime.put(a.getId(), a);
        images.put(a.getId(), scrubbedJpeg);
    }

    @Override
    public Anime randomExcluding(Set<Integer> usedIds, int maxRank) {
        List<Anime> available = new ArrayList<>();
        for (Anime a : anime.values()) {
            // Anime built without a rank are rank 0, so they pass every cap. That keeps
            // tests that do not care about difficulty from having to name one.
            if (!usedIds.contains(a.getId()) && a.getRank() <= maxRank) available.add(a);
        }
        if (available.isEmpty()) return null;
        return available.get((int) (Math.random() * available.size()));
    }

    @Override
    public byte[] imageBytes(int id) { return images.get(id); }

    @Override
    public Set<Integer> existingIds() { return new HashSet<>(anime.keySet()); }

    @Override
    public int count() { return anime.size(); }
}
