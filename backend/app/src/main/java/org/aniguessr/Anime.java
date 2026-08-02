package org.aniguessr;

import java.util.List;

public class Anime {
    private final int id;
    private final String url;
    private final List<String> titles;
    /**
     * Position in MyAnimeList's by-popularity ranking, 1 being the most watched. This is
     * the difficulty axis: a low rank is something players have plausibly heard of.
     *
     * Zero means "unranked", which is what the constructors without a rank produce. Every
     * row a real ingest writes has a rank of 1 or more, so zero only shows up in tests --
     * where it conveniently passes any {@code rank <= n} filter.
     */
    private final int rank;

    public Anime() {
        this(0, "", List.of());
    }

    public Anime(String url, List<String> titles) {
        this(0, url, titles);
    }

    public Anime(int id, String url, List<String> titles) {
        this(id, url, titles, 0);
    }

    public Anime(int id, String url, List<String> titles, int rank) {
        this.id = id;
        this.url = url;
        this.titles = List.copyOf(titles);
        this.rank = rank;
    }

    public int getId() { return id; }
    public String getUrl() { return url; }
    public int getRank() { return rank; }

    /** Immutable: the answer must not be editable by whoever holds a reference to it. */
    public List<String> getTitles() { return titles; }

    // Standard Levenshtein edit distance between two strings.
    private int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    // How many typos to forgive for a string of this length.
    private int toleranceFor(String s) {
        return s.length() <= 4 ? 0 : (s.length() <= 8 ? 1 : 2);
    }

    // The base name of a title: the part before a subtitle. So the base of
    // "Demon Slayer: Mugen Train" is "Demon Slayer".
    private String baseName(String title) {
        return title.split("\\s*:\\s*|\\s+-\\s+")[0].trim();
    }

    // True when the guess is close enough to one title. As well as a fuzzy match on the
    // whole title, a guess of at least 5 characters may match the base name (or a word of
    // it), so "demon slayer" is accepted for "Demon Slayer: Mugen Train" but the arc name
    // "mugen train" is not.
    private boolean matchesTitle(String answer, String guess) {
        String a = answer.toLowerCase().trim();
        String g = guess.toLowerCase().trim();
        if (a.isEmpty() || g.isEmpty()) return false;

        // Fuzzy match against the full title.
        if (distance(a, g) <= toleranceFor(a)) return true;

        // Partial matches are only allowed against the base name, never a subtitle.
        String base = baseName(a);
        if (g.length() >= 5) {
            // Exact chunk of the base name (handles multi-word bases like "demon slayer").
            if (base.contains(g)) return true;

            // Fuzzy match against a single significant word of the base name, so a
            // keyword guess like "frieren" can have a typo too.
            for (String word : base.split("[^a-z0-9]+")) {
                if (word.length() >= 5 && distance(word, g) <= toleranceFor(word)) return true;
            }
        }
        return false;
    }

    public boolean isCorrect(String guess) {
        if (guess == null) return false;
        for (String title : titles) {
            if (matchesTitle(title, guess)) return true;
        }
        return false;
    }
}
