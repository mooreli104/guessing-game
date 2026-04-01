package org.aniguessr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import io.javalin.websocket.WsContext;


public class GameManager {
    
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, WsContext> sessionToCtx = new ConcurrentHashMap<>();

    public String createRoom(JsonNode node, WsContext ctx) {
        String username = node.get("username").asText();
        Player newPlayer = new Player(username, ctx.sessionId);
        Room newRoom = new Room(newPlayer.getId());
        newRoom.addPlayer(newPlayer.getId(), newPlayer);
        newPlayer.joinRoom(newRoom.getId());
        
        rooms.put(newRoom.getId(), newRoom);
        players.put(newPlayer.getId(), newPlayer);   
        sessionToCtx.put(newPlayer.getId(), ctx);
        
        return newRoom.getId();
    }

    public void joinRoom(JsonNode node, WsContext ctx) {
        String username = node.get("username").asText();
        String roomId = node.get("code").asText();
        Room existingRoom = rooms.get(roomId);
        if (existingRoom != null) {
            Player newPlayer = new Player(username, ctx.sessionId);
            newPlayer.joinRoom(roomId);
            existingRoom.addPlayer(newPlayer.getId(),newPlayer);
            players.put(newPlayer.getId(), newPlayer);
            sessionToCtx.put(newPlayer.getId(), ctx);
        }
    }

    public void leaveRoom(WsContext ctx) {
        Player existingPlayer = players.get(ctx.sessionId);
        Room room = rooms.get(existingPlayer.getRoomCode());
        room.removePlayer(existingPlayer.getId());        
    }

    public Room getRoom(WsContext ctx){
        Player player = players.get(ctx.sessionId);
        String roomId = player.getRoomCode();
        return rooms.get(roomId);
    }

    public void broadcastPlayers(WsContext ctx) {
        Room room = getRoom(ctx);
        List<String> playersSnapshot = new ArrayList<>(room.getRoomSnapshot().keySet());
        for(String x: playersSnapshot){
            sessionToCtx.get(x).send(getRoom(ctx).getRoomSnapshot().toString());
        }
    }

    public Map<String, Room> getAllRoomsSnapshot() {
        return Map.copyOf(rooms); 
    }
}