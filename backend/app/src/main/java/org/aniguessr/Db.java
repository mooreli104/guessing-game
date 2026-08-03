package org.aniguessr;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Owns the JDBC URL and hands out connections. No connection pool: ingest is a
 * single-threaded batch job and a round does a couple of small reads, so
 * DriverManager is enough.
 */
public class Db {

    /**
     * A JDBC URL and, separately, the credentials to present with it. Keeping them apart
     * means the password is handed to the driver as a connection property rather than
     * embedded in the URL, where it would ride along into any exception or log line that
     * echoes the URL back.
     */
    record Target(String url, String user, String password) {}

    private final Target target;

    public Db() {
        this(System.getenv("DATABASE_URL"));
    }

    public Db(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is not set");
        }
        this.target = parse(url);
    }

    /**
     * Accepts either a JDBC URL or the libqp-style {@code postgres://user:pass@host/db}
     * URL that hosting providers inject, and always returns something the JDBC driver
     * understands. A URL that is already {@code jdbc:} is returned untouched, so local
     * development is unaffected -- which also means its credentials stay in the URL,
     * because that is the form the developer wrote them in.
     */
    static Target parse(String raw) {
        if (raw.startsWith("jdbc:")) {
            return new Target(raw, "", "");
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return new Target(raw, "", "");   // unrecognised; let the driver complain
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

        // getUserInfo decodes percent-escapes for us.
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

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database
            + "?sslmode=" + (isLocal(host) ? "disable" : "require");
        return new Target(url, user, password);
    }

    /**
     * The full JDBC URL including credentials. Only {@link #connection()} needs the
     * credentials split out; this remains the plain translation, and is what a developer
     * would paste to connect by hand.
     *
     * Values are percent-encoded here, so a password containing {@code &} or {@code =}
     * cannot split the query string.
     */
    static String toJdbcUrl(String raw) {
        Target t = parse(raw);
        if (t.user().isEmpty() && t.password().isEmpty()) {
            return t.url();
        }
        return t.url() + "&user=" + encode(t.user()) + "&password=" + encode(t.password());
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
        if (target.user().isEmpty() && target.password().isEmpty()) {
            // A jdbc: URL carries its own credentials in the query string.
            return DriverManager.getConnection(target.url());
        }
        Properties props = new Properties();
        props.setProperty("user", target.user());
        props.setProperty("password", target.password());
        return DriverManager.getConnection(target.url(), props);
    }

    public void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS anime (
              id          INTEGER     PRIMARY KEY,
              titles      TEXT[]      NOT NULL,
              image       BYTEA       NOT NULL,
              source_url  TEXT        NOT NULL,
              rank        INTEGER     NOT NULL DEFAULT 0,
              ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;
        // CREATE TABLE IF NOT EXISTS does nothing at all to a table that already exists,
        // so the rank column would never appear on a database ingested before it was
        // added. Adding it separately is what actually migrates those.
        String addRank = "ALTER TABLE anime ADD COLUMN IF NOT EXISTS rank INTEGER NOT NULL DEFAULT 0";
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute(addRank);
        } catch (SQLException e) {
            throw new RuntimeException("Could not create the anime table", e);
        }
    }

    public void createFeedbackTable() {
        // Deliberately no foreign key to anything: feedback arrives from anyone who has
        // the page open, whether or not they were ever in a room.
        String sql = """
            CREATE TABLE IF NOT EXISTS feedback (
              id         BIGSERIAL   PRIMARY KEY,
              kind       TEXT        NOT NULL,
              message    TEXT        NOT NULL,
              contact    TEXT        NOT NULL DEFAULT '',
              created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Could not create the feedback table", e);
        }
    }
}
