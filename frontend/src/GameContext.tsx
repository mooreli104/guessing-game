import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useReducer,
  useRef,
  type ReactNode,
} from "react";
import type { ClientMsg, Players, ServerMsg } from "./types";

const WS_URL = "ws://localhost:7070/websocket/game";

export type Screen = "home" | "lobby" | "game" | "over";

export type State = {
  screen: Screen;
  resuming: boolean; // reconnecting after a refresh, before the server catches us up
  playerId: string | null;
  code: string | null;
  host: string | null;
  players: Players;
  round: number;
  totalRounds: number;
  imageUrl: string;
  roundEndsAt: number | null; // client-clock deadline for the countdown
  guessed: boolean; // has this player already guessed correctly this round
  feedback: string;
  answer: string;
  winner: string;
  finalScores: Players;
  error: string;
};

type Action = { kind: "server"; msg: ServerMsg } | { kind: "backToLobby" } | { kind: "leaveLobby" };

function initState(): State {
  const playerId = sessionStorage.getItem("playerId");
  const code = sessionStorage.getItem("code");
  return {
    screen: "home",
    resuming: !!(playerId && code),
    playerId,
    code,
    host: null,
    players: {},
    round: 0,
    totalRounds: 0,
    imageUrl: "",
    roundEndsAt: null,
    guessed: false,
    feedback: "",
    answer: "",
    winner: "",
    finalScores: {},
    error: "",
  };
}

// One switch over every server message — the React equivalent of the old app.js handleMessage.
function reducer(state: State, action: Action): State {
  if (action.kind === "backToLobby") {
    return { ...state, screen: "lobby", answer: "", feedback: "", winner: "", finalScores: {} };
  }

  // Left the room entirely — back to a clean home screen (sessionStorage already cleared).
  if (action.kind === "leaveLobby") {
    return { ...initState(), resuming: false };
  }

  const msg = action.msg;
  switch (msg.type) {
    case "ROOM_CREATED":
    case "ROOM_JOINED":
      return { ...state, playerId: msg.playerId, code: msg.code, screen: "lobby", resuming: false, error: "" };

    case "ROOM_UPDATE": {
      // A resumed player lands here first; move them out of the home screen.
      const screen: Screen = state.screen === "home" && state.playerId ? "lobby" : state.screen;
      return { ...state, code: msg.code, host: msg.host, players: msg.players, screen, resuming: false };
    }

    case "ROUND_START":
      return {
        ...state,
        screen: "game",
        resuming: false,
        round: msg.round,
        totalRounds: msg.totalRounds,
        imageUrl: msg.imageUrl,
        roundEndsAt: Date.now() + msg.secondsLeft * 1000,
        guessed: false,
        feedback: "",
        answer: "",
      };

    case "GUESS_RESULT": {
      const players = { ...state.players };
      if (players[msg.playerId]) {
        players[msg.playerId] = { ...players[msg.playerId], score: msg.totalScore };
      }
      let feedback = state.feedback;
      let guessed = state.guessed;
      if (msg.playerId === state.playerId) {
        if (msg.isCorrect) {
          feedback = "Correct! +" + msg.points;
          guessed = true;
        } else {
          feedback = "Not quite, keep guessing…";
        }
      }
      return { ...state, players, feedback, guessed };
    }

    case "ROUND_END":
      return { ...state, answer: msg.answer, roundEndsAt: null, players: msg.scores };

    case "GAME_OVER":
      return { ...state, screen: "over", winner: msg.winner, finalScores: msg.scores, roundEndsAt: null };

    case "ERROR":
      if (msg.message.includes("expired")) {
        return { ...initState(), resuming: false };
      }
      return { ...state, error: msg.message };
  }
}

type GameContextValue = {
  state: State;
  send: (msg: ClientMsg) => void;
  backToLobby: () => void;
  leaveLobby: () => void;
};

const GameContext = createContext<GameContextValue | null>(null);

export function GameProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, undefined, initState);
  const wsRef = useRef<WebSocket | null>(null);
  const outboxRef = useRef<string[]>([]);

  const send = useCallback((msg: ClientMsg) => {
    const text = JSON.stringify(msg);
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(text);
    else outboxRef.current.push(text); // flushed on open
  }, []);

  useEffect(() => {
    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    ws.onopen = () => {
      outboxRef.current.forEach((m) => ws.send(m));
      outboxRef.current = [];
      // Recover identity after a full-page refresh / reconnect.
      const playerId = sessionStorage.getItem("playerId");
      const code = sessionStorage.getItem("code");
      if (playerId && code) ws.send(JSON.stringify({ type: "RESUME", playerId, code }));
    };

    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data) as ServerMsg;
      if (msg.type === "ROOM_CREATED" || msg.type === "ROOM_JOINED") {
        sessionStorage.setItem("playerId", msg.playerId);
        sessionStorage.setItem("code", msg.code);
      } else if (msg.type === "ERROR" && msg.message.includes("expired")) {
        sessionStorage.clear();
      }
      dispatch({ kind: "server", msg });
    };

    // StrictMode dev double-mount closes this first socket and opens a second one; the backend's
    // disconnect grace period + RESUME make that transient churn harmless.
    return () => ws.close();
  }, []);

  const backToLobby = useCallback(() => dispatch({ kind: "backToLobby" }), []);

  const leaveLobby = useCallback(() => {
    send({ type: "LEAVE_ROOM" });
    sessionStorage.clear(); // don't RESUME back into the room we just left
    dispatch({ kind: "leaveLobby" });
  }, [send]);

  return (
    <GameContext.Provider value={{ state, send, backToLobby, leaveLobby }}>{children}</GameContext.Provider>
  );
}

export function useGame(): GameContextValue {
  const ctx = useContext(GameContext);
  if (!ctx) throw new Error("useGame must be used inside a GameProvider");
  return ctx;
}
