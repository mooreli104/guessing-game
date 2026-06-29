package org.aniguessr;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Room {
    private final String id;
    private final Map<String, Player> connectedPlayers;
    private String host;
    private GameState state;
    private int round;
    private Anime anime;

    public Room() {
        this.id = UUID.randomUUID().toString();
        this.connectedPlayers = new HashMap<>();
        this.host = null;
        this.state = GameState.LOBBY;
        this.round = 1;
        this.anime = new Anime();
    }

    public String getId() { return id; }
    public String getHost() { return host; }
    public GameState getState() { return state; }
    public int getRound() { return round; }

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
