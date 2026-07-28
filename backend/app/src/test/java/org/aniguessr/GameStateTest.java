package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameStateTest {

    @Test
    void lobbyAdvancesToRoundActive() {
        assertEquals(GameState.ROUND_ACTIVE, GameState.LOBBY.nextState());
    }

    @Test
    void roundActiveAdvancesToScoring() {
        assertEquals(GameState.ROUND_SCORING, GameState.ROUND_ACTIVE.nextState());
    }

    @Test
    void scoringAdvancesToGameOver() {
        assertEquals(GameState.GAME_OVER, GameState.ROUND_SCORING.nextState());
    }

    @Test
    void gameOverStaysAtGameOver() {
        assertEquals(GameState.GAME_OVER, GameState.GAME_OVER.nextState());
    }
}
