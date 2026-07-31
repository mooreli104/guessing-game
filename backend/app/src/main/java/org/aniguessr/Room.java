package org.aniguessr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

public class Room {
    private final String id;
    private String code;
    private final Map<String, Player> connectedPlayers;
    private String host;
    private GameState state;
    private int round;
    private int totalRounds;
    private int roundSeconds;
    private long roundStartMillis;
    private Anime anime;
    private final Set<String> guessedCorrectly;
    // Anime already shown this game, so a round never repeats one.
    private final Set<Integer> usedAnimeIds;
    private ScheduledFuture<?> roundTask;

    public Room() {
        this.id = UUID.randomUUID().toString();
        this.connectedPlayers = new HashMap<>();
        this.host = null;
        this.state = GameState.LOBBY;
        this.round = 1;
        this.totalRounds = 0;
        this.roundSeconds = 0;
        this.roundStartMillis = 0;
        this.anime = new Anime();
        this.guessedCorrectly = new HashSet<>();
        this.usedAnimeIds = new HashSet<>();
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getHost() { return host; }
    public GameState getState() { return state; }
    public void setState(GameState state) { this.state = state; }
    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
    public int getRoundSeconds() { return roundSeconds; }
    public void setRoundSeconds(int roundSeconds) { this.roundSeconds = roundSeconds; }
    public long getRoundStartMillis() { return roundStartMillis; }
    public void setRoundStartMillis(long roundStartMillis) { this.roundStartMillis = roundStartMillis; }
    public Anime getAnime() { return this.anime; }
    public void setAnime(Anime anime) { this.anime = anime; }

    public Set<String> getGuessedCorrectly() { return guessedCorrectly; }
    public boolean hasGuessed(String playerId) { return guessedCorrectly.contains(playerId); }
    public void markGuessed(String playerId) { guessedCorrectly.add(playerId); }
    public void clearGuessed() { guessedCorrectly.clear(); }

    public Set<Integer> getUsedAnimeIds() { return Set.copyOf(usedAnimeIds); }
    public void markAnimeUsed(int id) { usedAnimeIds.add(id); }
    public void clearUsedAnime() { usedAnimeIds.clear(); }

    public ScheduledFuture<?> getRoundTask() { return roundTask; }
    public void setRoundTask(ScheduledFuture<?> roundTask) { this.roundTask = roundTask; }

    public void addPlayer(Player player) {
        if (connectedPlayers.isEmpty()) {
            this.host = player.getId();
        }
        connectedPlayers.put(player.getId(), player);
    }

    public void removePlayer(String playerId) {
        connectedPlayers.remove(playerId);
        if (connectedPlayers.isEmpty()) {
            host = null;
        } else if (playerId.equals(host)) {
            host = connectedPlayers.keySet().iterator().next();
        }
    }

    public Map<String, Player> getPlayers() {
        return Map.copyOf(connectedPlayers);
    }

    public boolean isEmpty() {
        return connectedPlayers.isEmpty();
    }
}
