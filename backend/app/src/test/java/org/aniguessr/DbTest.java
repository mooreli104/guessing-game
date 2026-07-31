package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the URL handling only -- nothing here opens a connection, so these run
 * everywhere. The database-backed behaviour lives in AnimeRepositoryTest.
 *
 * Hosting providers inject a libpq-style "postgres://user:pass@host/db" URL, but the
 * JDBC driver only understands "jdbc:postgresql://...". Db translates between them so a
 * deployment can use the platform's variable untouched.
 */
class DbTest {

    @Test
    void jdbcUrl_isLeftAlone() {
        String url = "jdbc:postgresql://localhost:5432/aniguessr?user=postgres&password=secret";
        assertEquals(url, Db.toJdbcUrl(url));
    }

    @Test
    void postgresScheme_becomesAJdbcUrl() {
        String jdbc = Db.toJdbcUrl("postgres://bob:hunter2@db.example.com:5432/railway");

        assertTrue(jdbc.startsWith("jdbc:postgresql://db.example.com:5432/railway?"), jdbc);
        assertTrue(jdbc.contains("user=bob"), jdbc);
        assertTrue(jdbc.contains("password=hunter2"), jdbc);
    }

    @Test
    void postgresqlScheme_isAlsoAccepted() {
        String jdbc = Db.toJdbcUrl("postgresql://bob:hunter2@db.example.com:5432/railway");
        assertTrue(jdbc.startsWith("jdbc:postgresql://db.example.com:5432/railway?"), jdbc);
    }

    @Test
    void remoteHost_requiresSsl() {
        String jdbc = Db.toJdbcUrl("postgres://bob:hunter2@db.example.com:5432/railway");
        assertTrue(jdbc.contains("sslmode=require"), jdbc);
    }

    @Test
    void localhost_doesNotRequireSsl() {
        // A local Postgres generally has no certificate, so demanding SSL would break
        // running the deployed configuration on a laptop.
        String jdbc = Db.toJdbcUrl("postgres://bob:hunter2@localhost:5432/aniguessr");
        assertTrue(jdbc.contains("sslmode=disable"), jdbc);
    }

    @Test
    void railwayInternalHost_doesNotRequireSsl() {
        // Railway's private network terminates inside the project; its Postgres does not
        // serve TLS on the internal hostname.
        String jdbc = Db.toJdbcUrl("postgres://bob:pw@postgres.railway.internal:5432/railway");
        assertTrue(jdbc.contains("sslmode=disable"), jdbc);
    }

    @Test
    void specialCharactersInThePassword_areEncoded() {
        // The password used in development is literally "Sachisroom1952$"; a raw & or =
        // would otherwise split the JDBC query string.
        String jdbc = Db.toJdbcUrl("postgres://bob:p%40ss%26word@db.example.com:5432/railway");

        assertTrue(jdbc.contains("password=p%40ss%26word"), jdbc);
    }

    @Test
    void missingPort_defaultsTo5432() {
        String jdbc = Db.toJdbcUrl("postgres://bob:pw@db.example.com/railway");
        assertTrue(jdbc.startsWith("jdbc:postgresql://db.example.com:5432/railway?"), jdbc);
    }

    @Test
    void blankUrl_isRejected() {
        assertThrows(IllegalStateException.class, () -> new Db(""));
        assertThrows(IllegalStateException.class, () -> new Db((String) null));
    }
}
