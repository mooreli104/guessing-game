package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PlayerTest {

    @Test
    void newPlayer_hasNameSessionAndZeroScore() {
        Player p = new Player("Alice", "session-1");
        assertEquals("Alice", p.getName());
        assertEquals("session-1", p.getSessionId());
        assertEquals(0, p.getScore());
        assertNull(p.getRoomCode());
        assertNotNull(p.getId());
    }

    @Test
    void eachPlayer_getsAUniqueId() {
        assertNotEquals(new Player("A", "s1").getId(), new Player("B", "s2").getId());
    }

    @Test
    void addScore_accumulates() {
        Player p = new Player("Alice", "s1");
        p.addScore(500);
        p.addScore(250);
        assertEquals(750, p.getScore());
    }

    @Test
    void resetScore_zeroesTheScore() {
        Player p = new Player("Alice", "s1");
        p.addScore(900);
        p.resetScore();
        assertEquals(0, p.getScore());
    }

    @Test
    void setSessionId_rebindsTheSession() {
        Player p = new Player("Alice", "old");
        p.setSessionId("new");
        assertEquals("new", p.getSessionId());
    }

    @Test
    void joinRoom_setsRoomCode() {
        Player p = new Player("Alice", "s1");
        p.joinRoom("KX7Q");
        assertEquals("KX7Q", p.getRoomCode());
    }
}
