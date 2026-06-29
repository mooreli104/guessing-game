package org.aniguessr;

import java.util.HashMap;
import java.util.Map;

public class GameManager {

    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, Player> players = new HashMap<>();
    private final Map<String, String> sessionToPlayer = new HashMap<>();

    private final SessionSender sender;

    public GameManager(SessionSender sender) {
        this.sender = sender;
    }

    public record JoinResult(String playerId, String roomId) {}

    public JoinResult createRoom(String username, String sessionId) {
        Player player = new Player(username, sessionId);
        Room room = new Room();
        room.addPlayer(player);
        player.joinRoom(room.getId());
        rooms.put(room.getId(), room);
        players.put(player.getId(), player);
        sessionToPlayer.put(sessionId, player.getId());
        broadcastRoom(room);
        return new JoinResult(player.getId(), room.getId());
    }

    public JoinResult joinRoom(String username, String roomId, String sessionId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            sender.send(sessionId, Map.of("type", "ERROR", "message", "Room not found"));
            return null;
        }
        Player player = new Player(username, sessionId);
        player.joinRoom(roomId);
        room.addPlayer(player);
        players.put(player.getId(), player);
        sessionToPlayer.put(sessionId, player.getId());
        broadcastRoom(room);
        return new JoinResult(player.getId(), roomId);
    }

    public void leaveRoom(String sessionId) {
        String playerId = sessionToPlayer.remove(sessionId);
        if (playerId == null) return;
        Player player = players.remove(playerId);
        if (player == null) return;
        Room room = rooms.get(player.getRoomCode());
        if (room == null) return;
        room.removePlayer(playerId);
        if (room.isEmpty()) {
            rooms.remove(room.getId());
        } else {
            broadcastRoom(room);
        }
    }

    public void startGame(String sessionId) {
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
        // TODO: round/anime setup
    }

    public Map<String, Room> getAllRoomsSnapshot() {
        return Map.copyOf(rooms);
    }

    private void broadcastRoom(Room room) {
        Map<String, Map<String, Object>> playersPayload = new HashMap<>();
        for (Player p : room.getPlayers().values()) {
            Map<String, Object> pp = new HashMap<>();
            pp.put("name", p.getName());
            pp.put("score", p.getScore());
            playersPayload.put(p.getId(), pp);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ROOM_UPDATE");
        payload.put("roomId", room.getId());
        payload.put("host", room.getHost());
        payload.put("state", room.getState().toString());
        payload.put("players", playersPayload);

        for (Player p : room.getPlayers().values()) {
            sender.send(p.getSessionId(), payload);
        }
    }
}
