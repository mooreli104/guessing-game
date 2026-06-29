package org.aniguessr;

import java.util.Map;

import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {
        WsRouter router = new WsRouter();

        var app = Javalin.create(config -> {
            config.routes.get("/health", ctx -> ctx.status(200).json(Map.of("status", "running")));
            config.routes.get("/rooms", ctx -> ctx.json(router.getGameManager().getAllRoomsSnapshot()));
            config.routes.ws("/websocket/game", ws -> {
                ws.onConnect(router::onConnect);
                ws.onMessage(router::onMessage);
                ws.onClose(router::onClose);
            });
        });

        app.start(7070);
    }
}
