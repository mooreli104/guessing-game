package org.aniguessr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Anime {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public record AnimeDTO(String title, String link){}

    public static HttpResponse.BodyHandler<AnimeDTO> responseBodyHandler(){
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
            HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
            body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    JsonNode node = root.get("data").get(0).get("node");
                    String title = node.get("title").asText();
                    String link = node.get("main_picture").get("medium").asText();
                    return new AnimeDTO(title, link);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        );
    }

    public static CompletableFuture<HttpResponse<AnimeDTO>>call(double offset){
        HttpClient client = HttpClient.newBuilder()
        .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.myanimelist.net/v2/anime/ranking?ranking_type=all&limit=1&offset=" + offset))
            .header("X-MAL-CLIENT-ID", "56c16cf022ffb0fe939e03a8a7c40f5b")
            .build();

        return client.sendAsync(request, responseBodyHandler());
    }
}
