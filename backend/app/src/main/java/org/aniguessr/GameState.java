package org.aniguessr;


public enum GameState {
    LOBBY{
        @Override
        public GameState nextState() {
            return ROUND_ACTIVE;
        }
    },
    ROUND_ACTIVE{
         @Override
        public GameState nextState() {
            return ROUND_SCORING;
        }

    },
    ROUND_SCORING{
        @Override
        public GameState nextState() {
            return GAME_OVER;
        }

    },
    GAME_OVER{
        @Override
        public GameState nextState() {
            return this;
        }
    };

    public abstract GameState nextState(); 

}
