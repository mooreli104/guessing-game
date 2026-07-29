package org.aniguessr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MalClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static HttpResponse.BodyHandler<List<Anime>> responseBodyHandler(){
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
            HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
            body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    List<Anime> page = new ArrayList<>();

                    for (JsonNode entry : root.get("data")) {
                        JsonNode node = entry.get("node");

                        // Ask for the large picture: Tesseract reads bigger text far more
                        // accurately, and it displays better too.
                        JsonNode picture = node.get("main_picture");
                        if (picture == null || picture.get("large") == null) continue;
                        String url = picture.get("large").asText();

                        int id = node.get("id").asInt();

                        List<String> titles = new ArrayList<>();
                        titles.add(node.get("title").asText());

                        JsonNode altTitles = node.get("alternative_titles");
                        if (altTitles != null) {
                            JsonNode en = altTitles.get("en");
                            if (en != null && !en.asText().isEmpty()) titles.add(en.asText());

                            JsonNode ja = altTitles.get("ja");
                            if (ja != null && !ja.asText().isEmpty()) titles.add(ja.asText());

                            JsonNode synonyms = altTitles.get("synonyms");
                            if (synonyms != null) {
                                for (JsonNode synonym : synonyms) {
                                    titles.add(synonym.asText());
                                }
                            }
                        }

                        page.add(new Anime(id, url, titles));
                    }
                    return page;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        );
    }

    // Synchronous: ingest is a batch job, and there is nothing to overlap it with.
    public static List<Anime> fetchPage(int offset, int limit) throws Exception {
        HttpClient client = HttpClient.newBuilder().build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.myanimelist.net/v2/anime/ranking?ranking_type=all"
                + "&limit=" + limit + "&offset=" + offset
                + "&fields=alternative_titles,main_picture"))
            .header("X-MAL-CLIENT-ID", "56c16cf022ffb0fe939e03a8a7c40f5b")
            .build();

        return client.send(request, responseBodyHandler()).body();
    }
}
