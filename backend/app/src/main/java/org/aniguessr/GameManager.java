package org.aniguessr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import io.javalin.websocket.WsContext;


public class GameManager {
    
    record ConnectedPlayer(Player player, WsContext ctx) {}

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map <String, ConnectedPlayer> connections = new ConcurrentHashMap<>();

    public String createRoom(JsonNode node, WsContext ctx) {
        String username = node.get("username").asText();
        Player newPlayer = new Player(username, ctx.sessionId);
        Room newRoom = new Room(newPlayer.getId());
        newRoom.addPlayer(newPlayer.getId(), newPlayer);
        newPlayer.joinRoom(newRoom.getId());
        rooms.put(newRoom.getId(), newRoom);
        connections.put(newPlayer.getId(), new ConnectedPlayer(newPlayer, ctx));
        
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
            connections.put(newPlayer.getId(), new ConnectedPlayer(newPlayer, ctx));
        }
    }

    public void leaveRoom(WsContext ctx) {
        ConnectedPlayer existingPlayer = connections.get(ctx.sessionId);
        if(existingPlayer == null) return;
        connections.remove(existingPlayer.player().getId());
        Room room = rooms.get(existingPlayer.player().getRoomCode());
        if(room == null) return;
        room.removePlayer(existingPlayer.player().getId());   
        if(room.isEmpty()){
            rooms.remove(room.getId());
        }    
    }

    public Room getRoom(WsContext ctx){
        Player player = connections.get(ctx.sessionId).player();
        String roomId = player.getRoomCode();
        return rooms.get(roomId);
    }

    public void broadcastPlayers(WsContext ctx) {
        Room room = getRoom(ctx);
        List<String> playersSnapshot = new ArrayList<>(room.getRoomSnapshot().keySet());
        for(String sessionId: playersSnapshot){
            WsContext context = connections.get(sessionId).ctx();
            context.send(getRoom(context).getRoomSnapshot().toString());
        }
    }

    public Map<String, Room> getAllRoomsSnapshot() {
        return Map.copyOf(rooms); 
    }
}