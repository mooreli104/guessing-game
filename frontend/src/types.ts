// Wire protocol shared with the Java backend (see WsRouter.onMessage and
// GameManager broadcast payloads). Keep these in sync with the server.

export type Player = { name: string; score: number };
export type Players = Record<string, Player>;

// Messages this client sends to the server.
export type ClientMsg =
  | { type: "CREATE_ROOM"; username: string }
  | { type: "JOIN_ROOM"; username: string; code: string }
  | { type: "RESUME"; playerId: string; code: string }
  | { type: "START_GAME"; rounds: number; roundSeconds: number }
  | { type: "GUESS"; guess: string }
  | { type: "LEAVE_ROOM" };

// Messages the server sends to this client.
export type ServerMsg =
  | { type: "ROOM_CREATED"; playerId: string; roomId: string; code: string }
  | { type: "ROOM_JOINED"; playerId: string; roomId: string; code: string }
  | { type: "ROOM_UPDATE"; roomId: string; code: string; host: string; state: string; players: Players }
  | { type: "ROUND_START"; round: number; totalRounds: number; imageUrl: string; roundSeconds: number; secondsLeft: number }
  | { type: "GUESS_RESULT"; playerId: string; isCorrect: boolean; points: number; totalScore: number }
  | { type: "ROUND_END"; round: number; answer: string; scores: Players }
  | { type: "GAME_OVER"; scores: Players; winner: string }
  // `code` is the machine-readable half: the client used to test the human-readable
  // message with .includes("expired"), so rewording it silently broke resume recovery.
  | { type: "ERROR"; code: ErrorCode; message: string };

export type ErrorCode =
  | "BAD_MESSAGE"
  | "ROOM_NOT_FOUND"
  | "SESSION_EXPIRED"
  | "NOT_IN_ROOM"
  | "NOT_HOST"
  | "ALREADY_STARTED"
  | "NO_ANIME";
