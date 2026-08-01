package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameManagerTest {

    private RecordingSender sender;
    private FakeAnimeRepository repo;
    private GameManager gm;

    @BeforeEach
    void setUp() {
        sender = new RecordingSender();
        repo = new FakeAnimeRepository();
        gm = new GameManager(sender, repo);
    }

    // Put a room into an active round with a known answer, bypassing the MAL network call.
    private Room startActiveRound(String code, List<String> titles, int roundSeconds, int totalRounds) {
        Room room = gm.getAllRoomsSnapshot().get(code);
        room.setAnime(new Anime("img", titles));
        room.setTotalRounds(totalRounds);
        room.setRound(1);
        room.setRoundSeconds(roundSeconds);
        room.setRoundStartMillis(System.currentTimeMillis());
        room.clearGuessed();
        room.setState(GameState.ROUND_ACTIVE);
        return room;
    }

    private static void await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !cond.getAsBoolean()) {
            Thread.sleep(50);
        }
    }

    // ---- create / join ----

    @Test
    void createRoom_registersRoomWithHostAndBroadcasts() {
        GameManager.JoinResult jr = gm.createRoom("Alice", "s1");
        assertNotNull(jr);
        assertEquals(4, jr.code().length());

        Room room = gm.getAllRoomsSnapshot().get(jr.code());
        assertNotNull(room);
        assertEquals(jr.playerId(), room.getHost());
        assertEquals(1, room.getPlayers().size());

        assertTrue(sender.has("ROOM_UPDATE"));
        @SuppressWarnings("unchecked")
        var players = (java.util.Map<String, Object>) sender.last("ROOM_UPDATE").get("players");
        assertEquals(1, players.size());
    }

    @Test
    void createRoom_generatesDistinctCodes() {
        String c1 = gm.createRoom("Alice", "s1").code();
        String c2 = gm.createRoom("Bob", "s2").code();
        assertNotEquals(c1, c2);
    }

    @Test
    void joinRoom_addsSecondPlayer() {
        String code = gm.createRoom("Alice", "s1").code();
        GameManager.JoinResult jr = gm.joinRoom("Bob", code, "s2");
        assertNotNull(jr);
        assertEquals(code, jr.code());
        assertEquals(2, gm.getAllRoomsSnapshot().get(code).getPlayers().size());
    }

    @Test
    void joinRoom_unknownCode_returnsNullAndSendsError() {
        GameManager.JoinResult jr = gm.joinRoom("Bob", "ZZZZ", "s2");
        assertNull(jr);
        assertEquals("ERROR", sender.lastTo("s2").get("type"));
        assertEquals("Room not found", sender.lastTo("s2").get("message"));
    }

    // ---- leave ----

    @Test
    void leaveRoom_removesPlayerAndReassignsHost() {
        String code = gm.createRoom("Alice", "s1").code();
        String bobId = gm.joinRoom("Bob", code, "s2").playerId();

        gm.leaveRoom("s1"); // host leaves

        Room room = gm.getAllRoomsSnapshot().get(code);
        assertEquals(1, room.getPlayers().size());
        assertEquals(bobId, room.getHost());
    }

    @Test
    void leaveRoom_lastPlayer_removesRoom() {
        String code = gm.createRoom("Alice", "s1").code();
        gm.leaveRoom("s1");
        assertFalse(gm.getAllRoomsSnapshot().containsKey(code));
    }

    @Test
    void leaveRoom_freesSessionToCreateAgain() {
        String code = gm.createRoom("Alice", "s1").code();
        gm.joinRoom("Bob", code, "s2");
        gm.leaveRoom("s2");

        GameManager.JoinResult again = gm.createRoom("Bob", "s2");
        assertNotNull(again);
        assertNotEquals(code, again.code());
    }

    @Test
    void leaveRoom_unknownSession_isNoOp() {
        assertDoesNotThrow(() -> gm.leaveRoom("nobody"));
    }

    // ---- resume / disconnect ----

    @Test
    void resume_rebindsSessionSoNewSessionCanAct() {
        GameManager.JoinResult jr = gm.createRoom("Alice", "s1");
        gm.resume(jr.playerId(), jr.code(), "s2");
        assertTrue(sender.has("ROOM_UPDATE"));

        // The new session is now bound to the player: a guess from it is accepted.
        startActiveRound(jr.code(), List.of("Naruto"), 30, 2);
        sender.clear();
        gm.guess("naruto", "s2");
        assertEquals(Boolean.TRUE, sender.last("GUESS_RESULT").get("isCorrect"));
    }

    @Test
    void resume_unknownPlayer_sendsExpiredError() {
        String code = gm.createRoom("Alice", "s1").code();
        gm.resume("does-not-exist", code, "s9");
        assertEquals("ERROR", sender.lastTo("s9").get("type"));
        assertTrue(((String) sender.lastTo("s9").get("message")).contains("expired"));
    }

    @Test
    void handleDisconnect_thenResume_keepsPlayerInRoom() {
        GameManager.JoinResult jr = gm.createRoom("Alice", "s1");
        gm.handleDisconnect("s1");            // schedules removal after the grace period
        gm.resume(jr.playerId(), jr.code(), "s2"); // cancels it

        Room room = gm.getAllRoomsSnapshot().get(jr.code());
        assertNotNull(room);
        assertEquals(1, room.getPlayers().size());
    }

    // ---- guessing / scoring ----

    @Test
    void guess_unknownSession_sendsError() {
        gm.guess("naruto", "ghost");
        assertEquals("ERROR", sender.lastTo("ghost").get("type"));
    }

    @Test
    void guess_correct_awardsPointsAndBroadcastsResult() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        gm.joinRoom("Bob", a.code(), "s2"); // 2 players so one correct guess doesn't end the round
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 2);
        sender.clear();

        GameManager.GuessResult res = gm.guess("naruto", "s1");

        assertNotNull(res);
        assertTrue(res.isCorrect());
        assertTrue(res.points() > 0);
        assertTrue(room.getPlayers().get(a.playerId()).getScore() > 0);
        assertEquals(Boolean.TRUE, sender.last("GUESS_RESULT").get("isCorrect"));
        assertEquals(GameState.ROUND_ACTIVE, room.getState()); // round still going
    }

    @Test
    void guess_wrong_scoresNothing() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 2);
        sender.clear();

        GameManager.GuessResult res = gm.guess("bleach", "s1");

        assertFalse(res.isCorrect());
        assertEquals(0, res.points());
        assertEquals(0, room.getPlayers().get(a.playerId()).getScore());
        assertEquals(Boolean.FALSE, sender.last("GUESS_RESULT").get("isCorrect"));
    }

    @Test
    void guess_afterAlreadyCorrect_isIgnored() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        gm.joinRoom("Bob", a.code(), "s2");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 2);

        gm.guess("naruto", "s1");
        int scoreAfterFirst = room.getPlayers().get(a.playerId()).getScore();
        GameManager.GuessResult second = gm.guess("naruto", "s1");

        assertNull(second);
        assertEquals(scoreAfterFirst, room.getPlayers().get(a.playerId()).getScore());
    }

    @Test
    void guess_whenRoundNotActive_returnsNull() {
        gm.createRoom("Alice", "s1");
        // room is still in LOBBY
        assertNull(gm.guess("naruto", "s1"));
    }

    @Test
    void guess_earlierInRound_scoresMoreThanLater() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        String bobId = gm.joinRoom("Bob", a.code(), "s2").playerId();
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        // Alice guesses at the very start of the round.
        gm.guess("naruto", "s1");
        int alicePoints = room.getPlayers().get(a.playerId()).getScore();

        // Rewind the round clock so Bob's guess lands near the end.
        room.setRoundStartMillis(System.currentTimeMillis() - 29_000);
        gm.guess("naruto", "s2");
        int bobPoints = room.getPlayers().get(bobId).getScore();

        assertTrue(alicePoints > bobPoints, "earlier guess should score more");
        assertTrue(bobPoints >= 100, "correct guess always earns at least the minimum");
    }

    @Test
    void everyoneGuessesCorrectly_endsRoundImmediately() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        gm.joinRoom("Bob", a.code(), "s2");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        gm.guess("naruto", "s1");
        assertEquals(GameState.ROUND_ACTIVE, room.getState()); // not everyone yet
        gm.guess("naruto", "s2");

        assertEquals(GameState.ROUND_SCORING, room.getState());
        assertTrue(sender.has("ROUND_END"));
    }

    // ---- start game validation ----

    @Test
    void startGame_byNonHost_isRejected() {
        String code = gm.createRoom("Alice", "s1").code();
        gm.joinRoom("Bob", code, "s2");

        gm.startGame("s2", 3, 20); // Bob is not the host

        assertEquals("ERROR", sender.lastTo("s2").get("type"));
        assertEquals(GameState.LOBBY, gm.getAllRoomsSnapshot().get(code).getState());
    }

    @Test
    void startGame_unknownSession_isNoOp() {
        assertDoesNotThrow(() -> gm.startGame("nobody", 1, 5));
    }

    @Test
    void startGame_whileARoundIsRunning_isRejected() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});
        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        Room room = startActiveRound(host.code(), List.of("Naruto"), 30, 3);
        room.setRound(2);
        sender.clear();

        // Restarting mid-game used to orphan the running round's timer, which then fired
        // and cut the new round short -- and it reset everyone's scores on the way.
        gm.startGame("s1", 3, 30);

        assertEquals("ERROR", sender.lastTo("s1").get("type"));
        assertEquals("ALREADY_STARTED", sender.lastTo("s1").get("code"));
        assertEquals(GameState.ROUND_ACTIVE, room.getState());
        assertEquals(2, room.getRound(), "the running game must be left alone");
    }

    // ---- players arriving and leaving mid-round ----

    @Test
    void joinRoom_duringARound_catchesTheNewPlayerUp() {
        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        Room room = startActiveRound(host.code(), List.of("Naruto"), 30, 1);
        room.setImageToken("tok");
        sender.clear();

        gm.joinRoom("Bob", host.code(), "s2");

        // Bob used to get only ROOM_UPDATE and sat on the lobby screen until the next round.
        Map<String, Object> caughtUp = sender.lastTo("s2");
        assertEquals("ROUND_START", caughtUp.get("type"));
        assertEquals("/image/tok", caughtUp.get("imageUrl"));
        assertTrue((int) caughtUp.get("secondsLeft") <= 30);
    }

    @Test
    void leavingAfterGuessing_doesNotEndLaterRoundsEarly() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        gm.joinRoom("Bob", a.code(), "s2");
        gm.joinRoom("Cara", a.code(), "s3");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        gm.guess("naruto", "s1"); // Alice is correct, then leaves
        gm.leaveRoom("s1");

        // Two players remain and only one of them has guessed, so the round continues.
        gm.guess("naruto", "s2");
        assertEquals(GameState.ROUND_ACTIVE, room.getState(),
            "Alice's stale id must not count towards the everyone-guessed check");

        gm.guess("naruto", "s3");
        assertEquals(GameState.ROUND_SCORING, room.getState());
    }

    @Test
    void lastUnguessedPlayerLeaving_endsTheRound() {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        gm.joinRoom("Bob", a.code(), "s2");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        gm.guess("naruto", "s1");
        assertEquals(GameState.ROUND_ACTIVE, room.getState());

        gm.leaveRoom("s2"); // the only player still guessing gives up and leaves

        assertEquals(GameState.ROUND_SCORING, room.getState(),
            "nobody is left to guess, so the round should not run out its clock");
    }

    // ---- resume checks the room, and names are bounded ----

    @Test
    void resume_withTheWrongRoomCode_isRejected() {
        GameManager.JoinResult jr = gm.createRoom("Alice", "s1");
        gm.resume(jr.playerId(), "ZZZZ", "s2");

        assertEquals("SESSION_EXPIRED", sender.lastTo("s2").get("code"));
        // The rejected session must not have been bound to Alice's identity.
        startActiveRound(jr.code(), List.of("Naruto"), 30, 1);
        assertNull(gm.guess("naruto", "s2"));
    }

    @Test
    void username_isTrimmedAndCappedServerSide() {
        // maxLength in the browser is a suggestion; the server is what everyone else sees.
        GameManager.JoinResult jr = gm.createRoom("  " + "x".repeat(200) + "  ", "s1");
        Room room = gm.getAllRoomsSnapshot().get(jr.code());
        assertEquals(16, room.getPlayers().get(jr.playerId()).getName().length());
    }

    @Test
    void username_blankFallsBackToAPlaceholder() {
        GameManager.JoinResult jr = gm.createRoom("   ", "s1");
        Room room = gm.getAllRoomsSnapshot().get(jr.code());
        assertEquals("Player", room.getPlayers().get(jr.playerId()).getName());
    }

    @Test
    void winner_tieIsBrokenDeterministically() throws InterruptedException {
        GameManager.JoinResult a = gm.createRoom("Zoe", "s1");
        String adamId = gm.joinRoom("Adam", a.code(), "s2").playerId();
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        // Both correct in the same instant of a 30s round, so both score the maximum.
        gm.guess("naruto", "s1");
        gm.guess("naruto", "s2");
        await(() -> sender.has("GAME_OVER"), 8000);

        assertEquals(room.getPlayers().get(a.playerId()).getScore(),
            room.getPlayers().get(adamId).getScore(), "test needs a genuine tie");
        assertEquals("Adam", sender.last("GAME_OVER").get("winner"),
            "a tie should not depend on hash-map iteration order");
    }

    // ---- full round -> game over (exercises the reveal delay + winner + reset) ----

    @Test
    void lastRound_revealsThenGameOversAndResetsToLobby() throws InterruptedException {
        GameManager.JoinResult a = gm.createRoom("Alice", "s1");
        Room room = startActiveRound(a.code(), List.of("Naruto"), 30, 1);

        gm.guess("naruto", "s1"); // sole player correct -> round ends -> reveal -> game over

        await(() -> sender.has("GAME_OVER"), 8000);

        assertTrue(sender.has("GAME_OVER"));
        assertEquals("Alice", sender.last("GAME_OVER").get("winner"));
        assertEquals(GameState.LOBBY, room.getState());
        assertEquals(1, room.getRound());
    }

    // ---- snapshot ----

    @Test
    void getAllRoomsSnapshot_isImmutable() {
        gm.createRoom("Alice", "s1");
        assertThrows(UnsupportedOperationException.class,
            () -> gm.getAllRoomsSnapshot().put("x", new Room()));
    }

    // ---- rounds come from the repository ----

    @Test
    void startGame_withEmptyPool_sendsErrorAndStaysInLobby() {
        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        gm.startGame("s1", 3, 30);

        Map<String, Object> last = sender.lastTo("s1");
        assertEquals("ERROR", last.get("type"));
        assertTrue(String.valueOf(last.get("message")).contains("No anime loaded"));
        assertEquals(GameState.LOBBY, gm.getAllRoomsSnapshot().get(host.code()).getState());
    }

    @Test
    void startGame_withPool_startsRoundAndServesTokenisedImageUrl() {
        repo.save(new Anime(5114, "https://cdn.myanimelist.net/x.jpg",
            List.of("Fullmetal Alchemist")), new byte[]{1, 2, 3});

        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        gm.startGame("s1", 1, 30);

        Room room = gm.getAllRoomsSnapshot().get(host.code());
        assertEquals(GameState.ROUND_ACTIVE, room.getState());
        assertEquals(5114, room.getAnime().getId());
        assertTrue(room.getUsedAnimeIds().contains(5114));

        Map<String, Object> start = sender.last("ROUND_START");
        assertNotNull(start);

        // The URL must not carry the anime id: it is MyAnimeList's own id, so /image/5114
        // told anyone who opened the network tab exactly what the answer was.
        String imageUrl = (String) start.get("imageUrl");
        assertFalse(imageUrl.contains("5114"), imageUrl);
        assertEquals("/image/" + room.getImageToken(), imageUrl);
        assertEquals(5114, gm.animeIdForToken(room.getImageToken()));
    }

    // ---- image tokens ----

    @Test
    void animeIdForToken_unknownOrMissingToken_isNull() {
        assertNull(gm.animeIdForToken(null));
        assertNull(gm.animeIdForToken(""));
        assertNull(gm.animeIdForToken("not-a-token"));
        assertNull(gm.animeIdForToken("../../etc/passwd"));
    }

    @Test
    void imageToken_isFreshEachRoundAndTheOldOneStopsResolving() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});
        repo.save(new Anime(2, "u", List.of("Two")), new byte[]{2});

        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        Room room = gm.getAllRoomsSnapshot().get(host.code());
        gm.startGame("s1", 2, 30);
        String first = room.getImageToken();

        gm.startRound(room); // the next round mints a new handle
        String second = room.getImageToken();

        assertNotEquals(first, second);
        assertNull(gm.animeIdForToken(first), "a spent token must stop resolving");
        assertNotNull(gm.animeIdForToken(second));
    }

    @Test
    void imageToken_isReleasedWhenTheRoomEmpties() {
        repo.save(new Anime(1, "u", List.of("One")), new byte[]{1});

        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        Room room = gm.getAllRoomsSnapshot().get(host.code());
        gm.startGame("s1", 5, 30);
        String token = room.getImageToken();
        assertNotNull(gm.animeIdForToken(token));

        gm.leaveRoom("s1"); // last player out, room is discarded

        assertNull(gm.animeIdForToken(token));
    }

    // Driven through startRound rather than startGame, because startGame deliberately
    // clears the used set — a fresh game is entitled to the whole pool again.
    @Test
    void startRound_whenPoolExhausted_clearsUsedAndRetries() {
        repo.save(new Anime(1, "u", List.of("Only Anime")), new byte[]{1});

        GameManager.JoinResult host = gm.createRoom("Alice", "s1");
        Room room = gm.getAllRoomsSnapshot().get(host.code());
        room.setTotalRounds(1);
        room.setRoundSeconds(30);
        room.markAnimeUsed(1); // the whole pool is excluded

        gm.startRound(room);

        assertEquals(GameState.ROUND_ACTIVE, room.getState());
        assertEquals(1, room.getAnime().getId());
    }
}
