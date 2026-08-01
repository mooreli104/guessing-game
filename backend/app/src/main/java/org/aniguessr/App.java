package org.aniguessr;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class App {

    /** Hosting platforms tell the process which port to bind; 7070 is the local default. */
    private static final int PORT = port();

    public static void main(String[] args) {
        AnimeRepository repository = new PostgresAnimeRepository(new Db());
        WsRouter router = new WsRouter(repository);

        var app = Javalin.create(config -> {
            // The built frontend ships inside the jar, so the whole game is one process on
            // one origin. That removes any need for CORS and lets the browser derive the
            // WebSocket URL from location -- which is what makes wss:// work automatically
            // once the platform terminates TLS.
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.routes.get("/health", ctx -> ctx.status(200).json(Map.of("status", "running")));
            // There is deliberately no /rooms route. It serialised Room objects directly,
            // which meant it returned 500 during a round (a Room holds a ScheduledFuture)
            // and, had it serialised, would have published Room.getAnime() -- the current
            // answer -- to anyone who polled it. getAllRoomsSnapshot stays for the tests.
            config.routes.get("/image/{id}", ctx -> {
                byte[] bytes = repository.imageBytes(Integer.parseInt(ctx.pathParam("id")));
                if (bytes == null) {
                    ctx.status(404);
                    return;
                }
                ctx.contentType("image/jpeg").result(bytes);
            });
            config.routes.ws("/websocket/game", ws -> {
                ws.onConnect(router::onConnect);
                ws.onMessage(router::onMessage);
                ws.onClose(router::onClose);
            });
        });

        // Bind every interface: inside a container, binding loopback would make the server
        // unreachable from outside it.
        app.start("0.0.0.0", PORT);
    }

    private static int port() {
        String fromEnv = System.getenv("PORT");
        if (fromEnv == null || fromEnv.isBlank()) {
            return 7070;
        }
        return Integer.parseInt(fromEnv.trim());
    }
}
