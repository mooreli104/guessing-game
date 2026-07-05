package org.aniguessr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

public class Anime {
    
    public static void call(){


        HttpClient client = HttpClient.newBuilder()
        .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.myanimelist.net/v2/anime/ranking?ranking_type=all&limit=1&offset=37"))
            .header("X-MAL-CLIENT-ID", "56c16cf022ffb0fe939e03a8a7c40f5b")
            .build();

        System.out.println(request.toString());

        client.sendAsync(request, BodyHandlers.ofString())
        .thenApply(HttpResponse::body)
        .thenAccept(System.out::println)
        .join();
    }
}
