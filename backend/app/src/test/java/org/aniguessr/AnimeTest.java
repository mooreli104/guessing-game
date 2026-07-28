package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnimeTest {

    @Test
    void defaultConstructor_isEmpty() {
        Anime a = new Anime();
        assertEquals("", a.getUrl());
        assertTrue(a.getTitles().isEmpty());
    }

    @Test
    void constructor_storesUrlAndTitles() {
        Anime a = new Anime("http://img", List.of("Naruto", "ナルト"));
        assertEquals("http://img", a.getUrl());
        assertEquals(List.of("Naruto", "ナルト"), a.getTitles());
    }

    @Test
    void isCorrect_exactMatch() {
        assertTrue(new Anime("u", List.of("Naruto")).isCorrect("Naruto"));
    }

    @Test
    void isCorrect_isCaseInsensitiveAndTrimmed() {
        Anime a = new Anime("u", List.of("Naruto"));
        assertTrue(a.isCorrect("naruto"));
        assertTrue(a.isCorrect("  NARUTO  "));
    }

    @Test
    void isCorrect_toleratesOneTypoOnMediumTitle() {
        assertTrue(new Anime("u", List.of("Naruto")).isCorrect("narutoo"));
    }

    @Test
    void isCorrect_rejectsWrongAnswer() {
        assertFalse(new Anime("u", List.of("Naruto")).isCorrect("bleach"));
    }

    @Test
    void isCorrect_matchesAnyOfTheAlternateTitles() {
        Anime a = new Anime("u", List.of("Cowboy Bebop", "Kaubōi Bibappu"));
        assertTrue(a.isCorrect("cowboy bebop"));
        assertTrue(a.isCorrect("kauboi bibappu")); // one typo tolerated on a long title
    }

    @Test
    void isCorrect_matchesKeywordFromLongTitle() {
        Anime a = new Anime("u", List.of("Sousou no Frieren", "Frieren: Beyond Journey's End"));
        assertTrue(a.isCorrect("frieren"));
        assertTrue(a.isCorrect("Frieren"));
        assertTrue(a.isCorrect("frieran")); // keyword with a typo
    }

    @Test
    void isCorrect_matchesKeywordWithApostropheSInLongTitle() {
        // The keyword leads the title and has "'s" glued on, e.g. "Frieren's journey ...".
        Anime a = new Anime("u", List.of("Frieren's Journey to the Land of Souls"));
        assertTrue(a.isCorrect("frieren"));
        assertTrue(a.isCorrect("Frieren"));
        assertTrue(a.isCorrect("frieren's")); // typing the possessive form works too
    }

    @Test
    void isCorrect_matchesBaseNameButNotSubtitle() {
        Anime a = new Anime("u", List.of("Demon Slayer: Mugen Train"));
        assertTrue(a.isCorrect("demon slayer"), "base name is correct");
        assertFalse(a.isCorrect("mugen train"), "subtitle / arc name is not correct");
        assertTrue(a.isCorrect("demon slayer: mugen train"), "exact full title still works");
    }

    @Test
    void isCorrect_rejectsSubtitleWithDashSeparator() {
        Anime a = new Anime("u", List.of("Sword Art Online - Alicization"));
        assertTrue(a.isCorrect("sword art online"), "base name is correct");
        assertFalse(a.isCorrect("alicization"), "subtitle is not correct");
    }

    @Test
    void isCorrect_ignoresShortCommonWords() {
        assertFalse(new Anime("u", List.of("Attack on Titan")).isCorrect("on"));
    }

    @Test
    void isCorrect_matchesDistinctiveWord() {
        assertTrue(new Anime("u", List.of("Attack on Titan")).isCorrect("titan"));
    }

    @Test
    void isCorrect_emptyGuessIsNeverCorrect() {
        Anime a = new Anime("u", List.of("Naruto"));
        assertFalse(a.isCorrect(""));
        assertFalse(a.isCorrect("   "));
    }

    @Test
    void isCorrect_withNoTitles_isFalse() {
        assertFalse(new Anime().isCorrect("anything"));
    }
}
