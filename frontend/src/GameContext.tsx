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

// In production the backend serves this bundle, so it is simply wherever the page came
// from. In dev the Vite server is a different origin, so point at the backend's port.
// Deriving from location rather than hardcoding is what makes the deployed game work:
// a page served over https must use wss, and browsers block a plain ws:// from it.
const API_URL = import.meta.env.DEV ? "http://localhost:7070" : window.location.origin;

// Round images are served by the backend, not the Vite dev server, so the
// server-sent "/image/{token}" path has to resolve against this origin.
const WS_URL = API_URL.replace(/^http/, "ws") + "/websocket/game";

// The server holds a disconnected player for 10s before dropping them, so the first
// retries need to land well inside that window for RESUME to still work.
const RETRY_MIN_MS = 500;
const RETRY_MAX_MS = 5000;

// Only these two keys are ours; clearing all of sessionStorage would take anything else
// the origin happens to be storing with it.
function forgetIdentity() {
  sessionStorage.removeItem("playerId");
  sessionStorage.removeItem("code");
}

export type Screen = "home" | "lobby" | "game" | "over";

export type State = {
  screen: Screen;
  connected: boolean; // is the socket up right now
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

type Action =
  | { kind: "server"; msg: ServerMsg }
  | { kind: "socket"; connected: boolean }
  | { kind: "backToLobby" }
  | { kind: "leaveLobby" };

function initState(): State {
  const playerId = sessionStorage.getItem("playerId");
  const code = sessionStorage.getItem("code");
  return {
    screen: "home",
    connected: false,
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
  if (action.kind === "socket") {
    return { ...state, connected: action.connected };
  }

  if (action.kind === "backToLobby") {
    return { ...state, screen: "lobby", answer: "", feedback: "", winner: "", finalScores: {} };
  }

  // Left the room entirely — back to a clean home screen (sessionStorage already cleared).
  if (action.kind === "leaveLobby") {
    return { ...initState(), connected: state.connected, resuming: false };
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
        error: "",
        round: msg.round,
        totalRounds: msg.totalRounds,
        imageUrl: API_URL + msg.imageUrl,
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
      // The server has forgotten us; there is nothing left to resume into.
      if (msg.code === "SESSION_EXPIRED") {
        return { ...initState(), connected: state.connected, resuming: false };
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
    // `unmounted` separates a deliberate teardown from a dropped connection: only the
    // latter should reconnect.
    let unmounted = false;
    let retryTimer: number | undefined;
    let attempt = 0;

    function connect() {
      const ws = new WebSocket(WS_URL);
      wsRef.current = ws;

      ws.onopen = () => {
        attempt = 0;
        dispatch({ kind: "socket", connected: true });
        outboxRef.current.forEach((m) => ws.send(m));
        outboxRef.current = [];
        // Recover identity after a full-page refresh / reconnect.
        const playerId = sessionStorage.getItem("playerId");
        const code = sessionStorage.getItem("code");
        if (playerId && code) ws.send(JSON.stringify({ type: "RESUME", playerId, code }));
      };

      ws.onmessage = (e) => {
        let msg: ServerMsg;
        try {
          msg = JSON.parse(e.data) as ServerMsg;
        } catch {
          return; // not something we sent for; ignore rather than kill the handler
        }
        if (msg.type === "ROOM_CREATED" || msg.type === "ROOM_JOINED") {
          sessionStorage.setItem("playerId", msg.playerId);
          sessionStorage.setItem("code", msg.code);
        } else if (msg.type === "ERROR" && msg.code === "SESSION_EXPIRED") {
          forgetIdentity();
        }
        dispatch({ kind: "server", msg });
      };

      // A dropped socket used to leave the page silently dead: the server's whole
      // grace-period/RESUME machinery only ever ran on a manual refresh. Back off
      // gently, but start fast enough to reconnect inside the grace window.
      ws.onclose = () => {
        if (unmounted) return;
        dispatch({ kind: "socket", connected: false });
        attempt += 1;
        retryTimer = window.setTimeout(connect, Math.min(RETRY_MIN_MS * attempt, RETRY_MAX_MS));
      };
    }

    connect();

    // StrictMode dev double-mount closes this first socket and opens a second one; the backend's
    // disconnect grace period + RESUME make that transient churn harmless.
    return () => {
      unmounted = true;
      window.clearTimeout(retryTimer);
      wsRef.current?.close();
    };
  }, []);

  const backToLobby = useCallback(() => dispatch({ kind: "backToLobby" }), []);

  const leaveLobby = useCallback(() => {
    send({ type: "LEAVE_ROOM" });
    forgetIdentity(); // don't RESUME back into the room we just left
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
