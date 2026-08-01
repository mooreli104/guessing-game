package org.aniguessr;

/**
 * Where a room is in its lifecycle: LOBBY -> ROUND_ACTIVE -> ROUND_SCORING, then either
 * back to ROUND_ACTIVE for the next round or on to GAME_OVER, which returns to LOBBY so
 * the same room can replay.
 *
 * Transitions are made explicitly by {@link GameManager} via {@code room.setState(...)}.
 * There used to be a {@code nextState()} method here modelling the happy path, but
 * nothing ever called it -- the real machine branches, so a single successor per state
 * could not describe it.
 */
public enum GameState {
    LOBBY,
    ROUND_ACTIVE,
    ROUND_SCORING,
    GAME_OVER
}
