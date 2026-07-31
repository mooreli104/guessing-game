package org.aniguessr;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        this.url = toJdbcUrl(url);
    }

    /**
     * Accepts either a JDBC URL or the libqp-style {@code postgres://user:pass@host/db}
     * URL that hosting providers inject, and always returns something the JDBC driver
     * understands. A URL that is already {@code jdbc:} is returned untouched, so local
     * development is unaffected.
     */
    static String toJdbcUrl(String raw) {
        if (raw.startsWith("jdbc:")) {
            return raw;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return raw;   // something we do not recognise; let the driver complain
        }

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "DATABASE_URL is not a valid URL. Special characters in the password must "
                    + "be percent-encoded.", e);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException("DATABASE_URL has no host: " + raw);
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        // getUserInfo decodes percent-escapes for us; we re-encode below for the query
        // string, so a password containing & or = cannot split it.
        String user = "";
        String password = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                user = userInfo;
            } else {
                user = userInfo.substring(0, colon);
                password = userInfo.substring(colon + 1);
            }
        }

        return "jdbc:postgresql://" + host + ":" + port + "/" + database
            + "?user=" + encode(user)
            + "&password=" + encode(password)
            + "&sslmode=" + (isLocal(host) ? "disable" : "require");
    }

    /**
     * A database reached over the open internet must use TLS; one on the same machine or
     * inside the platform's private network has no certificate to present, so demanding
     * TLS there would simply fail to connect.
     */
    private static boolean isLocal(String host) {
        return host.equals("localhost")
            || host.equals("127.0.0.1")
            || host.equals("::1")
            || host.endsWith(".railway.internal")
            || host.endsWith(".internal");
    }

    private static String encode(String value) {
        // URLEncoder is form-encoding, which writes a space as '+'; the driver reads the
        // query string as a URI, where '+' is a literal plus.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
