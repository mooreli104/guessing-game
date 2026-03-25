package org.aniguessr;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Room {
    private final UUID roomId;
    private List<Player> connectedPlayers;

    public Room() {
        this.roomId = UUID.randomUUID();
    }

    public UUID getId() {
        return this.roomId;
    }

    public List<Player> getPlayers() {
        return this.connectedPlayers;
    }

    public void broadcast(Map<String, Object> message) {
        for (var player : connectedPlayers) {
            player.getCtx().send(message);
        }
    }
}
