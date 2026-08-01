package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The id in /image/{id} is whatever the caller put in the URL, so it has to survive
 * anything. Everything that is not a plain integer is "not found" rather than an error --
 * previously Integer.parseInt threw straight out of the handler and answered a malformed
 * URL with a 500, which any URL scanner would trip constantly.
 */
class AppTest {

    @Test
    void plainNumber_isParsed() {
        assertEquals(5114, App.parseId("5114"));
    }

    @Test
    void nonNumeric_isNotFound() {
        assertNull(App.parseId("notanumber"));
        assertNull(App.parseId("5114.jpg"));
        assertNull(App.parseId("../../etc/passwd"));
    }

    @Test
    void emptyOrMissing_isNotFound() {
        assertNull(App.parseId(""));
        assertNull(App.parseId(null));
    }

    @Test
    void numberTooBigForAnInt_isNotFound() {
        // Would overflow rather than throw if parsed carelessly.
        assertNull(App.parseId("99999999999999999999"));
    }

    @Test
    void negative_parsesButMatchesNothing() {
        // Valid syntax, so it reaches the repository and comes back empty -- no anime has
        // a negative id. Recorded here so the behaviour is deliberate rather than assumed.
        assertEquals(-1, App.parseId("-1"));
    }
}
