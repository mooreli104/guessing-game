package org.aniguessr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Room {
    private final String id;
    private List<Player> connectedPlayers;
    private String host;
    private GameState state;
    private int round;
    private Anime anime;

    public Room(String host) {
        this.id = UUID.randomUUID().toString();
        this.connectedPlayers = new ArrayList<>();
        this.host = host;
        this.state = GameState.LOBBY;
        this.round = 1;
        this.anime = new Anime();
    }

    public String getId() {
        return this.id;
    }

    public List<Player> getPlayers() {
        return this.connectedPlayers;
    }

    public void broadcast(Map<String, Object> message) {
        for (var player : connectedPlayers) {
            player.getCtx().send(message);
        }
    }

    @Override
    public String toString() {
        return this.connectedPlayers.toString();
    }
}
