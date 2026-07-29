package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises the real Postgres implementation. Skipped when DATABASE_URL is unset so
 * `gradlew test` stays green on a machine without a database.
 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class AnimeRepositoryTest {

    private PostgresAnimeRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Db db = new Db();
        db.createTable();
        try (var conn = db.connection(); var st = conn.createStatement()) {
            st.execute("TRUNCATE anime");
        }
        repo = new PostgresAnimeRepository(db);
    }

    @Test
    void save_thenReadBackTitlesAndImage() {
        repo.save(new Anime(5114, "http://x/y.jpg", List.of("Fullmetal Alchemist", "FMA")), new byte[]{1, 2, 3});

        assertEquals(1, repo.count());
        assertArrayEquals(new byte[]{1, 2, 3}, repo.imageBytes(5114));

        Anime got = repo.randomExcluding(Set.of());
        assertEquals(5114, got.getId());
        assertEquals(List.of("Fullmetal Alchemist", "FMA"), got.getTitles());
        assertTrue(got.isCorrect("fullmetal alchemist"));
    }

    @Test
    void randomExcluding_honoursExclusionSet() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});
        repo.save(new Anime(2, "u", List.of("Two")), new byte[]{2});

        assertEquals(2, repo.randomExcluding(Set.of(1)).getId());
        assertNull(repo.randomExcluding(Set.of(1, 2)));
    }

    @Test
    void imageBytes_unknownId_returnsNull() {
        assertNull(repo.imageBytes(999999));
    }

    @Test
    void existingIds_reflectsWhatWasSaved() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});
        repo.save(new Anime(2, "u", List.of("Two")), new byte[]{2});
        assertEquals(Set.of(1, 2), repo.existingIds());
    }

    @Test
    void save_isIdempotentOnReIngest() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});
        repo.save(new Anime(1, "u", List.of("One Revised")), new byte[]{9});
        assertEquals(1, repo.count());
        assertArrayEquals(new byte[]{9}, repo.imageBytes(1));
    }
}
