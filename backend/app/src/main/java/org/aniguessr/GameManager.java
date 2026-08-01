package org.aniguessr;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single source of truth for game state.
 *
 * Threading: WebSocket messages arrive on Jetty threads, round timers fire on the
 * scheduler thread, and both can touch the same Room. {@link Room}'s fields are plain, so
 * <b>every</b> read or write of room state here happens inside {@code synchronized
 * (room)}. The one thing that must never happen inside that block is a repository call --
 * it opens a JDBC connection, and holding the room lock across it would block every guess
 * in that room. {@link #startRound} is written to keep the query outside the lock.
 */
public class GameManager {

    private static final int BASE_POINTS = 1000;
    private static final int MIN_POINTS = 100;
    private static final int DISCONNECT_GRACE_SECONDS = 10;
    private static final int ANSWER_REVEAL_SECONDS = 4; // pause on the answer before the next round
    private static final int MAX_NAME_LENGTH = 16;
    private static final int CODE_LENGTH = 4;
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // Room codes are short enough to be worth guessing, so they come from a CSPRNG
    // rather than Math.random().
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    // Players whose WebSocket dropped, awaiting a RESUME before being removed.
    private final Map<String, ScheduledFuture<?>> pendingRemoval = new ConcurrentHashMap<>();
    // Live image tokens -> the anime they stand for. See issueImageToken.
    private final Map<String, Integer> imageTokens = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final SessionSender sender;
    private final AnimeRepository repository;

    public GameManager(SessionSender sender, AnimeRepository repository) {
        this.sender = sender;
        this.repository = repository;
    }

    public record JoinResult(String playerId, String roomId, String code) {}
    public record GuessResult(JoinResult joinResult, boolean isCorrect, int points) {}

    // ---- joining and leaving ----------------------------------------------------------

    public JoinResult createRoom(String username, String sessionId) {
        Room room = new Room();
        reserveCode(room);
        Player player = register(username, sessionId, room);
        return new JoinResult(player.getId(), room.getId(), room.getCode());
    }

    public JoinResult joinRoom(String username, String code, String sessionId) {
        Room room = code == null ? null : rooms.get(code);
        if (room == null) {
            sender.send(sessionId, error("ROOM_NOT_FOUND", "Room not found"));
            return null;
        }
        Player player = register(username, sessionId, room);
        return new JoinResult(player.getId(), room.getId(), room.getCode());
    }

    /** Everything create and join have in common: build the player and seat them. */
    private Player register(String username, String sessionId, Room room) {
        Player player = new Player(displayName(username), sessionId);
        player.joinRoom(room.getCode());
        players.put(player.getId(), player);
        sessionToPlayer.put(sessionId, player.getId());
        synchronized (room) {
            room.addPlayer(player);
            broadcastRoom(room);
            // Joining mid-round used to leave the player stuck on the lobby screen until
            // the next round began.
            sendRoundCatchUp(room, sessionId);
        }
        return player;
    }

    // Re-bind an existing player to a new WebSocket session after page navigation.
    public void resume(String playerId, String code, String sessionId) {
        Player player = playerId == null ? null : players.get(playerId);
        // The client is claiming an identity, so make it name the right room too rather
        // than accepting any playerId on its own.
        if (player == null || !Objects.equals(player.getRoomCode(), code)) {
            sender.send(sessionId, error("SESSION_EXPIRED", "Session expired, please rejoin"));
            return;
        }
        ScheduledFuture<?> pending = pendingRemoval.remove(playerId);
        if (pending != null) pending.cancel(false);

        // Drop the mapping for the socket being replaced, or it lingers forever when the
        // old one never closed.
        sessionToPlayer.remove(player.getSessionId());
        player.setSessionId(sessionId);
        sessionToPlayer.put(sessionId, playerId);

        Room room = rooms.get(player.getRoomCode());
        if (room == null) {
            sender.send(sessionId, error("SESSION_EXPIRED", "Session expired, please rejoin"));
            return;
        }
        synchronized (room) {
            broadcastRoom(room);
            sendRoundCatchUp(room, sessionId);
        }
    }

    // Player explicitly left the lobby (e.g. to go join a different room). Remove them right away,
    // freeing the session to create or join again on the same connection.
    public void leaveRoom(String sessionId) {
        String playerId = sessionToPlayer.remove(sessionId);
        if (playerId == null) return;
        ScheduledFuture<?> pending = pendingRemoval.remove(playerId);
        if (pending != null) pending.cancel(false);
        removePlayer(playerId);
    }

    // WebSocket closed: don't drop the player immediately (page navigation looks like a
    // disconnect). Schedule removal; a RESUME within the grace window cancels it.
    public void handleDisconnect(String sessionId) {
        String playerId = sessionToPlayer.remove(sessionId);
        if (playerId == null) return;
        ScheduledFuture<?> task = scheduler.schedule(
            () -> removePlayer(playerId), DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        pendingRemoval.put(playerId, task);
    }

    private void removePlayer(String playerId) {
        pendingRemoval.remove(playerId);
        Player player = players.remove(playerId);
        if (player == null) return;
        Room room = rooms.get(player.getRoomCode());
        if (room == null) return;
        synchronized (room) {
            room.removePlayer(playerId);
            room.clearGuessedFor(playerId);
            if (room.isEmpty()) {
                ScheduledFuture<?> roundTask = room.getRoundTask();
                if (roundTask != null) roundTask.cancel(false);
                releaseImageToken(room);
                rooms.remove(room.getCode());
            } else {
                broadcastRoom(room);
                // The player who just left may have been the last one everyone was
                // waiting on.
                endRoundIfEveryoneGuessed(room);
            }
        }
    }

    // ---- guessing ---------------------------------------------------------------------

    public GuessResult guess(String guess, String sessionId) {
        Room room = roomFor(sessionId);
        if (room == null) return null;
        String playerId = sessionToPlayer.get(sessionId);
        Player player = players.get(playerId);
        if (player == null) return null;

        synchronized (room) {
            if (room.getState() != GameState.ROUND_ACTIVE) return null;
            if (room.hasGuessed(playerId)) return null;

            boolean correct = room.getAnime().isCorrect(guess);
            int points = 0;
            if (correct) {
                points = pointsFor(room);
                player.addScore(points);
                room.markGuessed(playerId);
            }
            broadcast(room, Map.of(
                "type", "GUESS_RESULT",
                "playerId", playerId,
                "isCorrect", correct,
                "points", points,
                "totalScore", player.getScore()
            ));

            if (correct) endRoundIfEveryoneGuessed(room);
            return new GuessResult(new JoinResult(playerId, room.getId(), room.getCode()), correct, points);
        }
    }

    /**
     * End the round now if nobody is left to guess, rather than waiting out the timer.
     * Caller must hold the room lock.
     */
    private void endRoundIfEveryoneGuessed(Room room) {
        if (room.getState() != GameState.ROUND_ACTIVE) return;
        if (room.getPlayers().isEmpty()) return;
        if (room.getGuessedCorrectly().size() < room.getPlayers().size()) return;
        ScheduledFuture<?> task = room.getRoundTask();
        if (task != null) task.cancel(false);
        endRound(room);
    }

    private int pointsFor(Room room) {
        long elapsedMillis = System.currentTimeMillis() - room.getRoundStartMillis();
        double fraction = 1.0 - (elapsedMillis / 1000.0) / room.getRoundSeconds();
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return Math.max(MIN_POINTS, (int) Math.round(BASE_POINTS * fraction));
    }

    // ---- the round lifecycle ----------------------------------------------------------

    public void startGame(String sessionId, int rounds, int roundSeconds) {
        Room room = roomFor(sessionId);
        if (room == null) return;
        String playerId = sessionToPlayer.get(sessionId);
        if (!Objects.equals(playerId, room.getHost())) {
            sender.send(sessionId, error("NOT_HOST", "Only host can start"));
            return;
        }
        // Starting again mid-game would leave the running round's timer orphaned; it would
        // then fire and cut the new round short.
        synchronized (room) {
            if (room.getState() != GameState.LOBBY) {
                sender.send(sessionId, error("ALREADY_STARTED", "The game is already running"));
                return;
            }
        }
        // Surface an empty pool at the click, rather than as a dead round. Outside the
        // lock: this is a database call.
        if (repository.count() == 0) {
            sender.send(sessionId, error("NO_ANIME", "No anime loaded — run ingest"));
            return;
        }
        synchronized (room) {
            if (room.getState() != GameState.LOBBY) return; // lost the race to another start
            room.setTotalRounds(Math.max(1, rounds));
            room.setRoundSeconds(Math.max(5, roundSeconds));
            room.setRound(1);
            room.clearUsedAnime();
            for (Player p : room.getPlayers().values()) p.resetScore();
        }
        startRound(room);
    }

    // Package-private so tests can drive a single round without going through startGame.
    void startRound(Room room) {
        Set<Integer> used;
        synchronized (room) {
            used = room.getUsedAnimeIds();
        }

        // Both picks happen outside the room lock -- they hit the database.
        Anime anime = pick(used);
        if (anime == null && !used.isEmpty()) {
            // More rounds were asked for than there are anime. Repeats beat a dead round.
            synchronized (room) {
                room.clearUsedAnime();
            }
            anime = pick(Set.of());
        }
        if (anime == null) {
            broadcast(room, error("NO_ANIME", "Could not load anime, try again"));
            return;
        }

        synchronized (room) {
            // Any timer still running belongs to a round we are replacing.
            ScheduledFuture<?> previous = room.getRoundTask();
            if (previous != null) previous.cancel(false);

            room.setAnime(anime);
            room.markAnimeUsed(anime.getId());
            room.setState(GameState.ROUND_ACTIVE);
            room.setRoundStartMillis(System.currentTimeMillis());
            room.clearGuessed();
            issueImageToken(room, anime.getId());

            broadcast(room, roundStartPayload(room, room.getRoundSeconds()));
            room.setRoundTask(scheduler.schedule(
                () -> endRound(room), room.getRoundSeconds(), TimeUnit.SECONDS));
        }
    }

    private Anime pick(Set<Integer> used) {
        try {
            return repository.randomExcluding(used);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // Reveal the answer, then advance after a pause so players can read it.
    private void endRound(Room room) {
        synchronized (room) {
            if (room.getState() != GameState.ROUND_ACTIVE) return; // already ended (early-finish vs timer race)
            room.setState(GameState.ROUND_SCORING);
            broadcast(room, Map.of(
                "type", "ROUND_END",
                "round", room.getRound(),
                "answer", room.getAnime().getTitles().isEmpty() ? "" : room.getAnime().getTitles().get(0),
                "scores", buildPlayersPayload(room)
            ));
        }
        scheduler.schedule(() -> advanceAfterReveal(room), ANSWER_REVEAL_SECONDS, TimeUnit.SECONDS);
    }

    private void advanceAfterReveal(Room room) {
        boolean nextRound = false;
        synchronized (room) {
            if (room.getState() != GameState.ROUND_SCORING) return;
            if (room.getRound() < room.getTotalRounds()) {
                room.setRound(room.getRound() + 1);
                nextRound = true;
            } else {
                room.setState(GameState.GAME_OVER);
                broadcast(room, Map.of(
                    "type", "GAME_OVER",
                    "scores", buildPlayersPayload(room),
                    "winner", winnerName(room)
                ));
                // Return the room to the lobby so players can replay.
                room.setState(GameState.LOBBY);
                room.setRound(1);
                releaseImageToken(room);
            }
        }
        // Outside the lock deliberately: startRound queries the database.
        if (nextRound) startRound(room);
    }

    // ---- image tokens -----------------------------------------------------------------

    /**
     * Mint the public handle for this round's cover.
     *
     * The anime id is MyAnimeList's own id, so serving the cover from /image/{id} handed
     * players the answer -- one look at the network tab, then myanimelist.net/anime/{id}.
     * The token is unguessable and only valid while the round it belongs to is on screen.
     * Caller must hold the room lock.
     */
    private void issueImageToken(Room room, int animeId) {
        releaseImageToken(room);
        String token = UUID.randomUUID().toString();
        room.setImageToken(token);
        imageTokens.put(token, animeId);
    }

    /** Retire a room's current token, so it stops resolving. Caller holds the room lock. */
    private void releaseImageToken(Room room) {
        String previous = room.getImageToken();
        if (previous != null) imageTokens.remove(previous);
        room.setImageToken(null);
    }

    /** The anime behind a GET /image/{token}, or null when the token is unknown or spent. */
    public Integer animeIdForToken(String token) {
        return token == null ? null : imageTokens.get(token);
    }

    // ---- lookups and payloads ---------------------------------------------------------

    /**
     * The room the session's player is in. Sends an ERROR and returns null when the
     * session is not seated in one -- every caller wants that same behaviour.
     */
    private Room roomFor(String sessionId) {
        String playerId = sessionToPlayer.get(sessionId);
        Player player = playerId == null ? null : players.get(playerId);
        Room room = player == null ? null : rooms.get(player.getRoomCode());
        if (room == null) {
            sender.send(sessionId, error("NOT_IN_ROOM", "You are not in a room"));
        }
        return room;
    }

    public Map<String, Room> getAllRoomsSnapshot() {
        return Map.copyOf(rooms);
    }

    /** Stop the round timers. The process is going away; nothing else uses the scheduler. */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private String winnerName(Room room) {
        String name = "";
        int best = Integer.MIN_VALUE;
        for (Player p : room.getPlayers().values()) {
            // Ties break on name so the winner does not depend on map iteration order.
            boolean better = p.getScore() > best
                || (p.getScore() == best && p.getName().compareTo(name) < 0);
            if (better) {
                best = p.getScore();
                name = p.getName();
            }
        }
        return name;
    }

    /** Names are shown to every other player, so bound them here rather than in the client. */
    private static String displayName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return "Player";
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("type", "ERROR", "code", code, "message", message);
    }

    /**
     * Claim a free code for this room and register it under that code. The code is set on
     * the room before the room becomes reachable through the map, and putIfAbsent makes
     * the claim atomic, so two concurrent creates cannot end up sharing a code.
     */
    private void reserveCode(Room room) {
        while (true) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            room.setCode(code);
            if (rooms.putIfAbsent(code, room) == null) return;
        }
    }

    /** Bring one session up to date with a round already in progress. Caller holds the lock. */
    private void sendRoundCatchUp(Room room, String sessionId) {
        if (room.getState() != GameState.ROUND_ACTIVE) return;
        long elapsed = (System.currentTimeMillis() - room.getRoundStartMillis()) / 1000;
        int secondsLeft = (int) Math.max(0, room.getRoundSeconds() - elapsed);
        sender.send(sessionId, roundStartPayload(room, secondsLeft));
    }

    private Map<String, Object> roundStartPayload(Room room, int secondsLeft) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ROUND_START");
        payload.put("round", room.getRound());
        payload.put("totalRounds", room.getTotalRounds());
        payload.put("imageUrl", "/image/" + room.getImageToken());
        payload.put("roundSeconds", room.getRoundSeconds());
        payload.put("secondsLeft", secondsLeft);
        return payload;
    }

    private Map<String, Map<String, Object>> buildPlayersPayload(Room room) {
        Map<String, Map<String, Object>> playersPayload = new HashMap<>();
        for (Player p : room.getPlayers().values()) {
            Map<String, Object> pp = new HashMap<>();
            pp.put("name", p.getName());
            pp.put("score", p.getScore());
            playersPayload.put(p.getId(), pp);
        }
        return playersPayload;
    }

    private void broadcastRoom(Room room) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ROOM_UPDATE");
        payload.put("roomId", room.getId());
        payload.put("code", room.getCode());
        payload.put("host", room.getHost());
        payload.put("state", room.getState().toString());
        payload.put("players", buildPlayersPayload(room));
        broadcast(room, payload);
    }

    private void broadcast(Room room, Object payload) {
        for (Player p : room.getPlayers().values()) {
            sender.send(p.getSessionId(), payload);
        }
    }
}
