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

    // True when the guess is close enough to the answer. Short titles must be
    // near-exact; longer titles tolerate a typo or two.
    public boolean levanshtein(String answer, String guess){
        String a = answer.toLowerCase().trim();
        String g = guess.toLowerCase().trim();
        if (a.isEmpty() || g.isEmpty()) return false;

        int tolerance = a.length() <= 4 ? 0 : (a.length() <= 8 ? 1 : 2);
        return distance(a, g) <= tolerance;
    }

    public boolean isCorrect(String guess){
        for(String str: this.titles){
            if(levanshtein(str, guess)) return true;
        }
        return false;
    }

}
