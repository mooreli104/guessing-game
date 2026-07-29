package org.aniguessr;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the JDBC URL and hands out connections. No connection pool: ingest is a
 * single-threaded batch job and a round does a couple of small reads, so
 * DriverManager is enough.
 */
public class Db {

    private final String url;

    public Db() {
        this(System.getenv("DATABASE_URL"));
    }

    public Db(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is not set");
        }
        this.url = url;
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS anime (
              id          INTEGER     PRIMARY KEY,
              titles      TEXT[]      NOT NULL,
              image       BYTEA       NOT NULL,
              source_url  TEXT        NOT NULL,
              ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Could not create the anime table", e);
        }
    }
}
