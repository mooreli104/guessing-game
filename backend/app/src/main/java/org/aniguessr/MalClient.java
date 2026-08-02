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

    /**
     * Read from the environment the way Db reads DATABASE_URL. There used to be a
     * hardcoded client ID here as a fallback, which meant a credential sat in the source
     * and in git history. Ingest is an offline batch job run by hand, so requiring the
     * variable costs nothing and failing loudly beats quietly using someone else's key.
     */
    private static String clientId() {
        String fromEnv = System.getenv("MAL_CLIENT_ID");
        if (fromEnv == null || fromEnv.isBlank()) {
            throw new IllegalStateException(
                "MAL_CLIENT_ID is not set. Register an application at "
                    + "https://myanimelist.net/apiconfig and export its client ID.");
        }
        return fromEnv;
    }

    public static HttpResponse.BodyHandler<List<Anime>> responseBodyHandler(){
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
            HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
            body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    List<Anime> page = new ArrayList<>();

                    for (JsonNode entry : root.get("data")) {
                        JsonNode node = entry.get("node");

                        // Position in the ranking we asked for, 1 being the most watched.
                        // Stored so a round can be restricted to titles players have
                        // plausibly heard of. Read from the response rather than counted
                        // locally, so entries skipped below do not shift everything after
                        // them. MAL always sends it; the fallback is only for safety.
                        JsonNode ranking = entry.get("ranking");
                        int rank = ranking == null || ranking.get("rank") == null
                            ? 0
                            : ranking.get("rank").asInt();

                        // Ask for the large picture: the scrubber's detector locates text
                        // far more reliably on it, and it displays better too.
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

                        page.add(new Anime(id, url, titles, rank));
                    }
                    return page;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        );
    }

    // Synchronous: ingest is a batch job, and there is nothing to overlap it with.
    //
    // ranking_type is bypopularity, not all. The "all" ranking sorts by weighted score,
    // which past the first few hundred entries is mostly high-rated OVAs, specials and
    // sequels-of-sequels -- adored by few, recognised by fewer, and unguessable from a
    // cover. Popularity sorts by member count, which is the "have people actually watched
    // this" axis the game needs.
    public static List<Anime> fetchPage(int offset, int limit) throws Exception {
        HttpClient client = HttpClient.newBuilder().build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.myanimelist.net/v2/anime/ranking?ranking_type=bypopularity"
                + "&limit=" + limit + "&offset=" + offset
                + "&fields=alternative_titles,main_picture"))
            .header("X-MAL-CLIENT-ID", clientId())
            .build();

        return client.send(request, responseBodyHandler()).body();
    }
}
