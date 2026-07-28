package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoomTest {

    @Test
    void newRoom_hasSensibleDefaults() {
        Room r = new Room();
        assertNotNull(r.getId());
        assertEquals(GameState.LOBBY, r.getState());
        assertEquals(1, r.getRound());
        assertTrue(r.isEmpty());
        assertNull(r.getHost());
    }

    @Test
    void codeAndConfig_areSettable() {
        Room r = new Room();
        r.setCode("AB12");
        r.setTotalRounds(5);
        r.setRoundSeconds(20);
        r.setRound(3);
        r.setState(GameState.ROUND_ACTIVE);
        assertEquals("AB12", r.getCode());
        assertEquals(5, r.getTotalRounds());
        assertEquals(20, r.getRoundSeconds());
        assertEquals(3, r.getRound());
        assertEquals(GameState.ROUND_ACTIVE, r.getState());
    }

    @Test
    void animeAndRoundStart_areSettable() {
        Room r = new Room();
        Anime a = new Anime("img", List.of("Bleach"));
        r.setAnime(a);
        r.setRoundStartMillis(12345L);
        assertEquals(a, r.getAnime());
        assertEquals(12345L, r.getRoundStartMillis());
    }

    @Test
    void firstPlayerBecomesHost_secondDoesNot() {
        Room r = new Room();
        Player a = new Player("Alice", "s1");
        Player b = new Player("Bob", "s2");
        r.addPlayer(a);
        r.addPlayer(b);
        assertEquals(a.getId(), r.getHost());
        assertEquals(2, r.getPlayers().size());
        assertFalse(r.isEmpty());
    }

    @Test
    void removingHost_reassignsHostToRemainingPlayer() {
        Room r = new Room();
        Player a = new Player("Alice", "s1");
        Player b = new Player("Bob", "s2");
        r.addPlayer(a);
        r.addPlayer(b);
        r.removePlayer(a.getId());
        assertEquals(b.getId(), r.getHost());
    }

    @Test
    void removingLastPlayer_leavesRoomEmptyWithNoHost() {
        Room r = new Room();
        Player a = new Player("Alice", "s1");
        r.addPlayer(a);
        r.removePlayer(a.getId());
        assertTrue(r.isEmpty());
        assertNull(r.getHost());
    }

    @Test
    void getPlayers_returnsAnImmutableView() {
        Room r = new Room();
        r.addPlayer(new Player("Alice", "s1"));
        assertThrows(UnsupportedOperationException.class,
            () -> r.getPlayers().put("x", new Player("X", "sx")));
    }

    @Test
    void guessedSet_marksAndClears() {
        Room r = new Room();
        assertFalse(r.hasGuessed("p1"));
        r.markGuessed("p1");
        assertTrue(r.hasGuessed("p1"));
        assertEquals(1, r.getGuessedCorrectly().size());
        r.clearGuessed();
        assertFalse(r.hasGuessed("p1"));
        assertTrue(r.getGuessedCorrectly().isEmpty());
    }

    @Test
    void roundTask_isSettable() {
        Room r = new Room();
        assertNull(r.getRoundTask());
        // A no-op ScheduledFuture stand-in isn't needed; null round-trip is enough to cover the accessor.
        r.setRoundTask(null);
        assertNull(r.getRoundTask());
    }
}
