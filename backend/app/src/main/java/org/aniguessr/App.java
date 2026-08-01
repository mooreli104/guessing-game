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
        GameManager games = router.getGameManager();

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
            config.routes.get("/image/{token}", ctx -> {
                // The token is an opaque per-round handle, never the anime id: the anime id
                // is MyAnimeList's own, so /image/5114 told anyone who looked at the URL
                // exactly what the answer was. Unknown or spent tokens are simply not found,
                // so no input needs parsing or validating here.
                Integer id = games.animeIdForToken(ctx.pathParam("token"));
                byte[] bytes = id == null ? null : repository.imageBytes(id);
                if (bytes == null) {
                    ctx.status(404);
                    return;
                }
                // A token names one fixed image for the life of a round, so the browser can
                // keep it rather than re-fetching the cover on every render.
                ctx.header("Cache-Control", "private, max-age=3600, immutable");
                ctx.contentType("image/jpeg").result(bytes);
            });
            config.routes.ws("/websocket/game", ws -> {
                ws.onConnect(router::onConnect);
                ws.onMessage(router::onMessage);
                ws.onClose(router::onClose);
            });
        });

        // Round timers run on a scheduler thread that would otherwise keep the JVM alive.
        Runtime.getRuntime().addShutdownHook(new Thread(games::shutdown));

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
