package org.aniguessr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

public class WsRouter implements SessionSender {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private final GameManager gameManager;

    public WsRouter(AnimeRepository repository) {
        this.gameManager = new GameManager(this, repository);
    }

    public GameManager getGameManager() { return gameManager; }

    public void onConnect(WsConnectContext ctx) {
        sessions.put(ctx.sessionId, ctx);
        ctx.enableAutomaticPings();
    }

    public void onClose(WsCloseContext ctx) {
        sessions.remove(ctx.sessionId);
        gameManager.handleDisconnect(ctx.sessionId);
    }

    public void onMessage(WsMessageContext ctx) throws Exception {
        JsonNode node = objectMapper.readTree(ctx.message());
        JsonNode typeNode = node.get("type");
        if (typeNode == null) {
            send(ctx.sessionId, Map.of("type", "ERROR", "message", "Missing type"));
            return;
        }
        switch (typeNode.asText()) {
            case "CREATE_ROOM" -> {
                String username = node.get("username").asText();
                GameManager.JoinResult result = gameManager.createRoom(username, ctx.sessionId);
                send(ctx.sessionId, Map.of(
                    "type", "ROOM_CREATED",
                    "playerId", result.playerId(),
                    "roomId", result.roomId(),
                    "code", result.code()
                ));
            }
            case "JOIN_ROOM" -> {
                String username = node.get("username").asText();
                String code = node.get("code").asText();
                GameManager.JoinResult result = gameManager.joinRoom(username, code, ctx.sessionId);
                if (result != null) {
                    send(ctx.sessionId, Map.of(
                        "type", "ROOM_JOINED",
                        "playerId", result.playerId(),
                        "roomId", result.roomId(),
                        "code", result.code()
                    ));
                }
            }
            case "RESUME" -> {
                String playerId = node.get("playerId").asText();
                String code = node.get("code").asText();
                gameManager.resume(playerId, code, ctx.sessionId);
            }
            case "START_GAME" -> {
                int rounds = node.get("rounds").asInt();
                int roundSeconds = node.get("roundSeconds").asInt();
                gameManager.startGame(ctx.sessionId, rounds, roundSeconds);
            }
            case "GUESS" -> {
                String guess = node.get("guess").asText();
                gameManager.guess(guess, ctx.sessionId);
            }
            case "LEAVE_ROOM" -> gameManager.leaveRoom(ctx.sessionId);
            default -> send(ctx.sessionId, Map.of(
                "type", "ERROR",
                "message", "Unknown type: " + typeNode.asText()
            ));
        }
    }

    @Override
    public void send(String sessionId, Object payload) {
        WsContext ctx = sessions.get(sessionId);
        if (ctx != null) ctx.send(payload);
    }
}
