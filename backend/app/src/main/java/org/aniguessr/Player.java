package org.aniguessr;

import java.util.UUID;

public class Player {
    private final String id;
    private final String name;
    private final String sessionId;
    private String connectedRoom;
    private int score;

    public Player(String name, String sessionId) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.sessionId = sessionId;
        this.connectedRoom = null;
        this.score = 0;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSessionId() { return sessionId; }
    public String getRoomCode() { return connectedRoom; }
    public int getScore() { return score; }

    public void joinRoom(String code) { this.connectedRoom = code; }
}
