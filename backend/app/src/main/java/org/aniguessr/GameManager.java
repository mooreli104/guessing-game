package org.aniguessr;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GameManager {

    private static final int BASE_POINTS = 1000;
    private static final int MIN_POINTS = 100;
    private static final int DISCONNECT_GRACE_SECONDS = 10;
    private static final int ANSWER_REVEAL_SECONDS = 4; // pause on the answer before the next round

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    // Players whose WebSocket dropped, awaiting a RESUME before being removed.
    private final Map<String, ScheduledFuture<?>> pendingRemoval = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final SessionSender sender;
    private final AnimeRepository repository;

    public GameManager(SessionSender sender, AnimeRepository repository) {
        this.sender = sender;
        this.repository = repository;
    }

    public record JoinResult(String playerId, String roomId, String code) {}
    public record GuessResult(JoinResult joinResult, boolean isCorrect, int points) {}

    public JoinResult createRoom(String username, String sessionId) {
        Player player = new Player(username, sessionId);
        Room room = new Room();
        String code = generateUniqueCode();
        room.setCode(code);
        room.addPlayer(player);
        player.joinRoom(code);
        rooms.put(code, room);
        players.put(player.getId(), player);
        sessionToPlayer.put(sessionId, player.getId());
        broadcastRoom(room);
        return new JoinResult(player.getId(), room.getId(), code);
    }

    public JoinResult joinRoom(String username, String code, String sessionId) {
        Room room = rooms.get(code);
        if (room == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Room not found"));
            return null;
        }
        Player player = new Player(username, sessionId);
        player.joinRoom(code);
        room.addPlayer(player);
        players.put(player.getId(), player);
        sessionToPlayer.put(sessionId, player.getId());
        broadcastRoom(room);
        return new JoinResult(player.getId(), room.getId(), code);
    }

    // Re-bind an existing player to a new WebSocket session after page navigation.
    public void resume(String playerId, String code, String sessionId) {
        Player player = players.get(playerId);
        if (player == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Session expired, please rejoin"));
            return;
        }
        ScheduledFuture<?> pending = pendingRemoval.remove(playerId);
        if (pending != null) pending.cancel(false);

        player.setSessionId(sessionId);
        sessionToPlayer.put(sessionId, playerId);

        Room room = rooms.get(player.getRoomCode());
        if (room == null) return;

        broadcastRoom(room);
        // If a round is in progress, catch this session up to the current image and clock.
        if (room.getState() == GameState.ROUND_ACTIVE) {
            long elapsed = (System.currentTimeMillis() - room.getRoundStartMillis()) / 1000;
            int secondsLeft = (int) Math.max(0, room.getRoundSeconds() - elapsed);
            sender.send(sessionId, roundStartPayload(room, secondsLeft));
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
            if (room.isEmpty()) {
                ScheduledFuture<?> roundTask = room.getRoundTask();
                if (roundTask != null) roundTask.cancel(false);
                rooms.remove(room.getCode());
            } else {
                broadcastRoom(room);
            }
        }
    }

    public GuessResult guess(String guess, String sessionId) {
        String playerId = sessionToPlayer.get(sessionId);
        if (playerId == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Player ID not found"));
            return null;
        }
        Player player = players.get(playerId);
        if (player == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Player not found"));
            return null;
        }
        Room room = rooms.get(player.getRoomCode());
        if (room == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Room not found"));
            return null;
        }

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

            // Everyone guessed correctly — no need to wait for the timer, end the round now.
            if (correct && room.getGuessedCorrectly().size() >= room.getPlayers().size()) {
                ScheduledFuture<?> task = room.getRoundTask();
                if (task != null) task.cancel(false);
                endRound(room);
            }
            return new GuessResult(new JoinResult(playerId, room.getId(), room.getCode()), correct, points);
        }
    }

    private int pointsFor(Room room) {
        long elapsedMillis = System.currentTimeMillis() - room.getRoundStartMillis();
        double fraction = 1.0 - (elapsedMillis / 1000.0) / room.getRoundSeconds();
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return Math.max(MIN_POINTS, (int) Math.round(BASE_POINTS * fraction));
    }

    public void startGame(String sessionId, int rounds, int roundSeconds) {
        String playerId = sessionToPlayer.get(sessionId);
        if (playerId == null) return;
        Player player = players.get(playerId);
        if (player == null) return;
        Room room = rooms.get(player.getRoomCode());
        if (room == null) return;
        if (!playerId.equals(room.getHost())) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Only host can start"));
            return;
        }
        // Surface an empty pool at the click, rather than as a dead round.
        if (repository.count() == 0) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "No anime loaded — run ingest"));
            return;
        }
        synchronized (room) {
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
        Anime anime;
        try {
            anime = repository.randomExcluding(room.getUsedAnimeIds());
            if (anime == null) {
                // More rounds were asked for than there are anime. Repeats beat a dead round.
                room.clearUsedAnime();
                anime = repository.randomExcluding(room.getUsedAnimeIds());
            }
        } catch (RuntimeException ex) {
            broadcast(room, Map.of("type", "ERROR", "message", "Could not load anime, try again"));
            return;
        }
        if (anime == null) {
            broadcast(room, Map.of("type", "ERROR", "message", "Could not load anime, try again"));
            return;
        }

        synchronized (room) {
            room.setAnime(anime);
            room.markAnimeUsed(anime.getId());
            room.setState(GameState.ROUND_ACTIVE);
            room.setRoundStartMillis(System.currentTimeMillis());
            room.clearGuessed();
        }
        broadcast(room, roundStartPayload(room, room.getRoundSeconds()));
        ScheduledFuture<?> task = scheduler.schedule(
            () -> endRound(room), room.getRoundSeconds(), TimeUnit.SECONDS);
        room.setRoundTask(task);
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
        synchronized (room) {
            if (room.getState() != GameState.ROUND_SCORING) return;
            if (room.getRound() < room.getTotalRounds()) {
                room.setRound(room.getRound() + 1);
                startRound(room);
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
            }
        }
    }

    public Map<String, Room> getAllRoomsSnapshot() {
        return Map.copyOf(rooms);
    }

    private String winnerName(Room room) {
        String name = "";
        int best = Integer.MIN_VALUE;
        for (Player p : room.getPlayers().values()) {
            if (p.getScore() > best) {
                best = p.getScore();
                name = p.getName();
            }
        }
        return name;
    }

    private String generateUniqueCode() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(alphabet.charAt((int) (Math.random() * alphabet.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private Map<String, Object> roundStartPayload(Room room, int secondsLeft) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ROUND_START");
        payload.put("round", room.getRound());
        payload.put("totalRounds", room.getTotalRounds());
        payload.put("imageUrl", "/image/" + room.getAnime().getId());
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
