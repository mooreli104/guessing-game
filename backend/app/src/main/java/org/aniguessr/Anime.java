package org.aniguessr;

import java.util.ArrayList;
import java.util.List;

public class Anime {
    private String url;
    private List<String> titles;

    public Anime(){
        this.url = "";
        this.titles = new ArrayList<>();
    }

    public Anime(String url, List<String> titles) {
        this.url = url;
        this.titles = titles;
    }

    public String getUrl() { return url; }
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

    // True when the guess is close enough to the answer. As well as a fuzzy match
    // on the whole title, a guess of at least 5 characters may match a chunk of a
    // longer title, so "frieren" is accepted for "Sousou no Frieren".
    public boolean levanshtein(String answer, String guess){
        String a = answer.toLowerCase().trim();
        String g = guess.toLowerCase().trim();
        if (a.isEmpty() || g.isEmpty()) return false;

        // Fuzzy match against the full title.
        if (distance(a, g) <= toleranceFor(a)) return true;

        if (g.length() >= 5) {
            // Exact chunk of the title (handles multi-word partials like "mugen train").
            if (a.contains(g)) return true;

            // Fuzzy match against a single significant word of the title, so a
            // keyword guess can have a typo too.
            for (String word : a.split("[^a-z0-9]+")) {
                if (word.length() >= 5 && distance(word, g) <= toleranceFor(word)) return true;
            }
        }
        return false;
    }

    public boolean isCorrect(String guess){
        for(String str: this.titles){
            if(levanshtein(str, guess)) return true;
        }
        return false;
    }

}
