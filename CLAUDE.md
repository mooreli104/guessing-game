# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Aniguessr: a multiplayer anime-guessing game. Players join a room via a 4-character
code, are shown an anime cover image fetched from the MyAnimeList API, and race to
type the correct title before the round timer runs out. Two independent projects:

- `backend/` — Java 21 + Javalin WebSocket/HTTP server (package `org.aniguessr`)
- `frontend/` — React 19 + TypeScript SPA (Vite)

They communicate over a JSON WebSocket protocol at `ws://localhost:7070/websocket/game`.

## Commands

### Backend (run from `backend/`)

```
./gradlew build          # compile + run tests
./gradlew test           # run all tests
./gradlew test --tests "org.aniguessr.AnimeTest"                # run one test class
./gradlew test --tests "org.aniguessr.AnimeTest.isCorrect_exactMatch"  # run one test method
./gradlew run            # start the server on port 7070
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Frontend (run from `frontend/`)

```
npm install
npm run dev        # start Vite dev server
npm run build      # tsc --noEmit type check, then production build
npm run preview    # preview the production build
```

There is no frontend test runner configured.

## Git workflow

Commit on every branch, and commit incrementally per feature rather than batching
unrelated changes into one commit — each commit should represent one coherent
feature or fix.

## Architecture

### Backend: session/player/room model

The server is single-process, in-memory, no database. `App.java` wires up Javalin
with one WebSocket endpoint (`/websocket/game`) and two HTTP endpoints (`/health`,
`/rooms`). All game logic flows through `WsRouter` → `GameManager`.

- **`WsRouter`** is the WebSocket entry point. It deserializes incoming JSON into a
  `type`-tagged message, dispatches to the matching `GameManager` method, and
  implements `SessionSender` to push JSON back down to a given `sessionId`.
- **`GameManager`** is the single source of truth. It owns three maps keyed
  differently: `rooms` (code → `Room`), `players` (playerId → `Player`), and
  `sessionToPlayer` (WebSocket sessionId → playerId). This indirection exists
  because a player's WebSocket session can change (reconnect) while their game
  identity (`playerId`) must persist — see the RESUME flow below.
- **`Room`** holds per-room game state: connected players, host, `GameState`,
  round number/timer, and the current `Anime` answer. Mutations to a `Room` are
  guarded by `synchronized (room)` blocks in `GameManager` since multiple
  WebSocket threads can touch the same room concurrently.
- **`GameState`** is a state machine enum (`LOBBY → ROUND_ACTIVE → ROUND_SCORING →
  GAME_OVER`, then back to `LOBBY` for replay) via `nextState()`.
- **`Anime`** wraps the answer title(s) and implements the guess-matching logic:
  Levenshtein distance with a length-scaled typo tolerance, plus base-name/keyword
  matching for long subtitled titles (e.g. "demon slayer" matches "Demon Slayer:
  Mugen Train" but "mugen train" alone does not). This is the most business-logic-
  heavy class and has the most thorough test coverage (`AnimeTest`).
- **`MalClient`** fetches a random anime from the MyAnimeList API
  (`api.myanimelist.net/v2/anime/ranking`) using a random ranking offset, returning
  a `CompletableFuture<HttpResponse<Anime>>`. The MAL client ID is currently a
  hardcoded header value in this file.
- Round timing (`ROUND_ACTIVE` duration, the post-answer reveal pause) is driven by
  a shared `ScheduledExecutorService` in `GameManager`, not per-room threads.

### Reconnect handling (RESUME)

A page refresh looks identical to a disconnect from the server's perspective. To
avoid punishing refreshes:

1. On WebSocket close, `GameManager.handleDisconnect` does **not** remove the
   player immediately — it schedules removal after `DISCONNECT_GRACE_SECONDS` (10s).
2. If the client reconnects and sends `RESUME` with its `playerId`/room `code`
   within that window, `GameManager.resume` cancels the pending removal, rebinds
   the player to the new `sessionId`, and — if a round is in progress — replays the
   current image and remaining time so the client catches up mid-round.
3. Explicitly leaving (`LEAVE_ROOM`) skips the grace period and removes the player
   immediately.

### Scoring

Points awarded on a correct guess scale down linearly with elapsed time in the
round, from `BASE_POINTS` (1000) down to a floor of `MIN_POINTS` (100) — see
`GameManager.pointsFor`. If every connected player has guessed correctly, the round
ends immediately rather than waiting out the timer.

### WebSocket protocol

The wire protocol is defined in two places that must be kept in sync manually:
`WsRouter.onMessage` / `GameManager` broadcast payloads on the backend, and
`frontend/src/types.ts` (`ClientMsg` / `ServerMsg` union types) on the frontend.
Message `type` values: `CREATE_ROOM`, `JOIN_ROOM`, `RESUME`, `START_GAME`, `GUESS`,
`LEAVE_ROOM` (client→server); `ROOM_CREATED`, `ROOM_JOINED`, `ROOM_UPDATE`,
`ROUND_START`, `GUESS_RESULT`, `ROUND_END`, `GAME_OVER`, `ERROR` (server→client).

### Frontend: single reducer over the WebSocket stream

`GameContext.tsx` owns the entire client state machine as one `useReducer` whose
`reducer` is effectively a switch over every possible `ServerMsg.type` — the React
equivalent of a single `handleMessage` dispatcher. There is one WebSocket connection
per app lifetime, opened in a `useEffect` in `GameProvider`; messages sent before the
socket is open are queued in `outboxRef` and flushed on `onopen`.

- `playerId` and room `code` are persisted to `sessionStorage` so a page refresh can
  RESUME instead of dropping the player from their room (see backend RESUME above).
  An `ERROR` message containing "expired" clears `sessionStorage` and resets to the
  home screen — the server's signal that resume is no longer possible.
- The countdown timer is derived client-side from a server-provided deadline
  (`roundEndsAt = Date.now() + secondsLeft * 1000`) rather than trusting a
  server-pushed tick, so it stays correct across re-renders and resumes.
- Screens (`HomeScreen`, `LobbyScreen`, `GameScreen`, `OverScreen`) are chosen by
  `state.screen` in `App.tsx` and read/write exclusively through `useGame()`
  (`state`, `send`, `backToLobby`, `leaveLobby`) — no other prop drilling or global
  state.
