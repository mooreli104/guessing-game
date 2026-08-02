package org.aniguessr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

/**
 * The WebSocket entry point: deserialises an incoming JSON message, dispatches to the
 * matching {@link GameManager} method, and implements {@link SessionSender} to push JSON
 * back down to a session.
 *
 * Everything arriving here is untrusted. Fields are read through {@link #text} and
 * {@link #intOr} rather than {@code node.get(x).asText()}, which threw a
 * NullPointerException out of the handler for any message that simply omitted a field --
 * so any client could produce server-side exceptions at will and got no reply explaining
 * why nothing happened.
 */
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

    public void onMessage(WsMessageContext ctx) {
        JsonNode node;
        try {
            node = objectMapper.readTree(ctx.message());
        } catch (Exception e) {
            sendError(ctx.sessionId, "BAD_MESSAGE", "Could not read that message");
            return;
        }

        String type = text(node, "type");
        if (type == null) {
            sendError(ctx.sessionId, "BAD_MESSAGE", "Missing type");
            return;
        }

        switch (type) {
            case "CREATE_ROOM" -> {
                String username = text(node, "username");
                if (username == null) {
                    sendError(ctx.sessionId, "BAD_MESSAGE", "Missing username");
                    return;
                }
                GameManager.JoinResult result = gameManager.createRoom(username, ctx.sessionId);
                send(ctx.sessionId, joinedPayload("ROOM_CREATED", result));
            }
            case "JOIN_ROOM" -> {
                String username = text(node, "username");
                String code = text(node, "code");
                if (username == null || code == null) {
                    sendError(ctx.sessionId, "BAD_MESSAGE", "Missing username or code");
                    return;
                }
                GameManager.JoinResult result = gameManager.joinRoom(username, code, ctx.sessionId);
                if (result != null) {
                    send(ctx.sessionId, joinedPayload("ROOM_JOINED", result));
                }
            }
            case "RESUME" -> {
                String playerId = text(node, "playerId");
                String code = text(node, "code");
                if (playerId == null || code == null) {
                    sendError(ctx.sessionId, "SESSION_EXPIRED", "Session expired, please rejoin");
                    return;
                }
                gameManager.resume(playerId, code, ctx.sessionId);
            }
            // A missing or unknown difficulty is not an error: rankCapFor falls back to
            // normal, which is what an older client that sends no difficulty at all wants.
            case "START_GAME" -> gameManager.startGame(
                ctx.sessionId, intOr(node, "rounds", 3), intOr(node, "roundSeconds", 30),
                text(node, "difficulty"));
            case "GUESS" -> {
                String guess = text(node, "guess");
                if (guess == null) {
                    sendError(ctx.sessionId, "BAD_MESSAGE", "Missing guess");
                    return;
                }
                gameManager.guess(guess, ctx.sessionId);
            }
            case "LEAVE_ROOM" -> gameManager.leaveRoom(ctx.sessionId);
            default -> sendError(ctx.sessionId, "BAD_MESSAGE", "Unknown type: " + type);
        }
    }

    /** ROOM_CREATED and ROOM_JOINED differ only by their type. */
    private static Map<String, Object> joinedPayload(String type, GameManager.JoinResult result) {
        return Map.of(
            "type", type,
            "playerId", result.playerId(),
            "roomId", result.roomId(),
            "code", result.code()
        );
    }

    /** A string field, or null when absent, null, or not a string-ish value. */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        return value.asText();
    }

    /** An int field, falling back when absent or not representable as one. */
    static int intOr(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return (value == null || !value.canConvertToInt()) ? fallback : value.asInt();
    }

    private void sendError(String sessionId, String code, String message) {
        send(sessionId, Map.of("type", "ERROR", "code", code, "message", message));
    }

    @Override
    public void send(String sessionId, Object payload) {
        WsContext ctx = sessions.get(sessionId);
        if (ctx != null) ctx.send(payload);
    }
}
