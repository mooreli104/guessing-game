package org.aniguessr;

import java.util.UUID;

import io.javalin.websocket.WsContext;

public class Player {
    private final UUID id;
    private UUID connectedRoom;
    private final String name;
    private final WsContext ctx;

    public Player(String name, WsContext ctx) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.ctx = ctx;
        this.connectedRoom = null;
    }

    // getters
    public UUID getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public WsContext getCtx() {
        return this.ctx;
    }

    public UUID getRoomCode() {
        return this.connectedRoom;
    }

    // setter only for roomCode since it changes
    public void joinRoom(UUID code) {
        this.connectedRoom = code;
    }
}
