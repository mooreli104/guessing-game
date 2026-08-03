package org.aniguessr;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PostgresAnimeRepository implements AnimeRepository {

    private final Db db;

    public PostgresAnimeRepository(Db db) {
        this.db = db;
        db.createTable();
    }

    @Override
    public void save(Anime anime, byte[] scrubbedJpeg) {
        String sql = """
            INSERT INTO anime (id, titles, image, source_url, rank)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
              SET titles = EXCLUDED.titles,
                  image = EXCLUDED.image,
                  source_url = EXCLUDED.source_url,
                  rank = EXCLUDED.rank,
                  ingested_at = now()
            """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array titles = conn.createArrayOf("text", anime.getTitles().toArray(new String[0]));
            ps.setInt(1, anime.getId());
            ps.setArray(2, titles);
            ps.setBytes(3, scrubbedJpeg);
            ps.setString(4, anime.getUrl());
            ps.setInt(5, anime.getRank());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save anime " + anime.getId(), e);
        }
    }

    // Deliberately does not select the image column: starting a round should move a few
    // hundred bytes, with the picture travelling separately through GET /image/{id}.
    @Override
    public Anime randomExcluding(Set<Integer> usedIds, int maxRank) {
        String sql = """
            SELECT id, titles, rank FROM anime
            WHERE id <> ALL(?) AND rank <= ?
            ORDER BY random() LIMIT 1
            """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("integer", usedIds.toArray(new Integer[0])));
            ps.setInt(2, maxRank);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String[] titles = (String[]) rs.getArray("titles").getArray();
                return new Anime(rs.getInt("id"), "", List.of(titles), rs.getInt("rank"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not pick an anime", e);
        }
    }

    @Override
    public byte[] imageBytes(int id) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT image FROM anime WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("image") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read image " + id, e);
        }
    }

    @Override
    public Set<Integer> existingIds() {
        Set<Integer> ids = new HashSet<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM anime");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt("id"));
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Could not read existing ids", e);
        }
    }

    @Override
    public int count() {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM anime");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Could not count anime", e);
        }
    }
}
