package org.aniguessr;

import java.util.UUID;

import io.javalin.websocket.WsContext;

public class Player {
    private final String id;
    private String connectedRoom;
    private final String name;
    private final WsContext ctx;
    private int score;


    public Player(String name, WsContext ctx) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.ctx = ctx;
        this.connectedRoom = null;
        this.score = 0;
    }

    // getters
    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public WsContext getCtx() {
        return this.ctx;
    }

    public String getRoomCode() {
        return this.connectedRoom;
    }

    public int getScore(){
        return this.score;
    }

    // setter only for roomCode since it changes
    public void joinRoom(String code) {
        this.connectedRoom = code;
    }
}
