package org.aniguessr;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    private final String id;
    private Map<String, Player> connectedPlayers;
    private String host;
    private GameState state;
    private int round;
    private Anime anime;

    public Room(String host) {
        this.id = UUID.randomUUID().toString();
        this.connectedPlayers = new ConcurrentHashMap<>();
        this.host = host;
        this.state = GameState.LOBBY;
        this.round = 1;
        this.anime = new Anime();
    }

    public String getId() {
        return this.id;
    }

     public void addPlayer(String id, Player player) {
        this.connectedPlayers.put(id, player);
    }

    public void removePlayer(String id) {
        this.connectedPlayers.remove(id);
    }

    public Map<String, Player> getRoomSnapshot() {
        return Map.copyOf(this.connectedPlayers);
    }

    public boolean isEmpty(){
        return this.connectedPlayers.isEmpty();
    }

    @Override
    public String toString() {
        return this.connectedPlayers.toString();
    }
}
