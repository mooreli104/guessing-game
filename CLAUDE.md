# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Aniguessr: a multiplayer anime-guessing game. Players join a room via a 4-character
code, are shown an anime cover image with the title scrubbed out of the artwork, and
race to type the correct title before the round timer runs out. Two independent
projects:

- `backend/` — Java 21 + Javalin WebSocket/HTTP server (package `org.aniguessr`)
- `frontend/` — React 19 + TypeScript SPA (Vite)

They communicate over a JSON WebSocket protocol at `ws://localhost:7070/websocket/game`.

Covers are **not** fetched from MyAnimeList during a round. An offline ingest job
pre-scrubs them into Postgres, and the game serves them from its own
`GET /image/{id}`, so the round path makes no third-party call and the URL leaks
nothing about the answer.

## Commands

### Backend (run from `backend/`)

```
./gradlew build          # compile + run tests
./gradlew test           # run all tests
./gradlew test --tests "org.aniguessr.AnimeTest"                # run one test class
./gradlew test --tests "org.aniguessr.AnimeTest.isCorrect_exactMatch"  # run one test method
./gradlew run            # start the server on port 7070
./gradlew ingest         # offline: download, scrub and store the anime pool (~20 min)
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Prerequisites

The game needs a populated database, and ingest additionally needs Python:

| Variable | Needed by | Value |
|---|---|---|
| `DATABASE_URL` | server, ingest | `jdbc:postgresql://localhost:5432/aniguessr?user=…&password=…` |
| `TEST_DATABASE_URL` | `AnimeRepositoryTest` | a **different** database — these tests truncate it |
| `SCRUB_PYTHON` | ingest, `TitleScrubberTest` | path to a Python with `easyocr` installed (defaults to `python`) |
| `MAL_CLIENT_ID` | ingest | optional; falls back to the value in `MalClient` |

Set up once:

```
createdb -U postgres aniguessr
createdb -U postgres aniguessr_test   # AnimeRepositoryTest truncates this one
pip install easyocr                 # pulls in torch; ~100MB of model weights on first run
./gradlew ingest                    # then ./gradlew run as usual
```

`App.main` throws on startup if `DATABASE_URL` is unset, and `startGame` rejects with
`ERROR "No anime loaded — run ingest"` if the pool is empty. Tests that need Postgres
or Python are guarded with `@EnabledIfEnvironmentVariable`, so `./gradlew test` stays
green without either.

**`TEST_DATABASE_URL` is deliberately a separate variable from `DATABASE_URL`.**
`AnimeRepositoryTest` truncates the anime table; when it was guarded on `DATABASE_URL`,
running the suite wiped the ingested pool, and against a deployed database it would wipe
production. The test refuses to run if the two point at the same database.

### Frontend (run from `frontend/`)

```
npm install
npm run dev        # start Vite dev server
npm run build      # tsc --noEmit type check, then production build
npm run preview    # preview the production build
```

There is no frontend test runner configured.

## Deployment

The `Dockerfile` at the repo root builds **one image containing both halves**: it builds
the frontend, copies the bundle onto the backend's classpath at `/public`, and ships a JRE
plus the installed distribution. `App` serves that bundle as static files, so the game is a
single process on a single origin.

That single origin is load-bearing, not a convenience:

- There is no CORS to configure.
- The client derives `API_URL` from `window.location.origin` and the WebSocket URL from it
  (`http`→`ws`), so a page served over HTTPS automatically gets `wss://`. Browsers block a
  plain `ws://` from an HTTPS page, so a hardcoded `ws://localhost` cannot work deployed.
- `import.meta.env.DEV` keeps the `localhost:7070` branch for `npm run dev` only; it is
  tree-shaken out of the production bundle.

**Ingest is deliberately not in the image.** It needs Python, easyocr and ~2GB of torch
weights, and only ever runs offline. Run it locally and move the rows with `pg_dump` /
`psql`; the deployed image is just a JRE and a jar (~480MB).

`Db.toJdbcUrl` accepts either a JDBC URL or the `postgres://user:pass@host/db` form that
hosting platforms inject, so `DATABASE_URL` can be wired straight from the platform's
Postgres. TLS is required for remote hosts and disabled for `localhost` and
`*.railway.internal`, which serve no certificate.

`PORT` is read from the environment (7070 when unset) and the server binds `0.0.0.0` —
binding loopback would make it unreachable from outside the container.

### One instance only

`GameManager` holds rooms, players and session mappings in memory. Two instances means two
players can land on different JVMs and never share a room, and sticky sessions do not help
because the room itself only exists in one heap. **Pin the deployment to a single
instance.** Horizontal scaling would require moving room state to something shared.

Related: avoid a free tier that sleeps on idle. Spin-down drops every open WebSocket and
kills in-flight games.

## Git workflow

Commit on every branch, and commit incrementally per feature rather than batching
unrelated changes into one commit — each commit should represent one coherent
feature or fix.

## Architecture

### Backend: session/player/room model

The server is single-process and holds all *game* state in memory; the anime pool lives
in Postgres. `App.java` wires up Javalin with one WebSocket endpoint (`/websocket/game`)
and two HTTP endpoints (`/health`, `/image/{id}`), plus the static frontend. All game
logic flows through `WsRouter` → `GameManager`.

**There is deliberately no `/rooms` route.** It serialised `Room` objects straight to
JSON, so it returned 500 during any round (a `Room` holds a `ScheduledFuture`) and, had
it serialised, would have published `Room.getAnime()` — the current answer — to anyone
who polled it. `getAllRoomsSnapshot()` remains as a test accessor.

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
- **`AnimeRepository`** is the interface both the game and ingest talk to
  (`save` / `randomExcluding` / `imageBytes` / `existingIds` / `count`).
  `PostgresAnimeRepository` implements it with plain JDBC; `FakeAnimeRepository` in the
  test source set is the in-memory double, mirroring the `SessionSender` /
  `RecordingSender` split. `Db` owns the JDBC URL and creates the table.
  `randomExcluding` deliberately does not select the image column — starting a round
  should move a few hundred bytes, with the picture travelling separately through
  `GET /image/{id}`.
- **`MalClient`** is used **only by ingest**, never in the round path. `fetchPage(offset,
  limit)` reads a page of the MAL ranking synchronously. The client ID comes from
  `MAL_CLIENT_ID`, falling back to a hardcoded value.
- Round timing (`ROUND_ACTIVE` duration, the post-answer reveal pause) is driven by
  a shared `ScheduledExecutorService` in `GameManager`, not per-room threads.
- `Room` tracks `usedAnimeIds` so an anime never repeats within a game. `startGame`
  clears it; if the pool runs dry mid-game `startRound` clears it and retries once,
  on the grounds that repeats beat a dead round.

### Title scrubbing (ingest only)

Essentially every MAL cover has its title printed into the artwork, which gives the
answer away. `IngestMain` downloads each cover, blanks the title, and stores the result;
covers that cannot be scrubbed cleanly are rejected before a player ever sees them.

Detection runs in a **Python sidecar**, `backend/scripts/scrub_service.py`, driven by
`TitleScrubber` over stdin/stdout with images passed as file paths. The sidecar is
long-lived because loading the model takes seconds and ingest scrubs hundreds of covers.

Two things are worth knowing before changing this:

1. **Tesseract does not work here and was removed.** Anime titles are stylised display
   logotypes, often light-on-light over busy artwork. Tesseract found *no* text on real
   covers, so it blanked random artwork and passed the leak through. The current code
   uses EasyOCR's CRAFT scene-text detector instead, which takes the accept rate from
   ~21% to ~87% (436 of 500). Note that is the *accept* rate — it says nothing about
   whether the accepted covers are clean; see the defect section below.
2. **Only detection is used, never recognition.** We need to know where text is, not what
   it says. So the verify step re-runs *detection* on the scrubbed image and rejects if
   any region survives — a check that does not depend on the title being legible.

Rejection rules: zero detections (means detection failed, not that the cover is clean),
boxed area over 35% (too little picture left to guess from), or any text surviving the
verify pass.

### The scrubber leaks on roughly a third of covers

**This is a known, unfixed defect. Do not describe the pool as clean.** Of 9 covers
sampled from the deployed pool, 3 still showed a readable title:

- *The First Slam Dunk* — the giant background letters spelling SLAM DUNK are untouched;
  only the jersey numbers were boxed.
- *Major S2* — the メジャー/MAJOR logo is fully visible; only a scoreboard was boxed.
- *Kingdom 2nd Season* — キングダム is legible.

A further group (*Yuru Camp△*, *Kino no Tabi*) keeps single-character fragments, which is
probably not enough to guess from.

**Why the verify step cannot catch it.** CRAFT is blind to stylised display logotypes —
running detection on those three *scrubbed* covers returns **zero** boxes at every scale
from 1.0 down to 0.25, even with SLAM DUNK spanning the whole frame. The verify pass
re-runs the same detector, so it can only ever see what the detector already sees; a blind
spot is invisible to it. This is structurally the same mistake as the Tesseract version —
the detector was replaced, but the verify built on top of it was not.

The "zero detections ⇒ reject" rule was meant to cover this and does not: it catches
*total* blindness only. On these covers CRAFT found the jersey numbers and the copyright
line, so the image cleared the check while the title was never touched.

Ruled out by measurement, so do not retry them:

- **Multi-scale detection.** Tested at 1.0/0.75/0.5/0.35/0.25 — still zero detections. The
  logotypes are not missed because they are too large.
- **Rotating 90° for vertical CJK.** Tried; did not find it either. (Vertical *taglines*
  do survive on some covers, but a tagline is not the answer.)

Anything that actually fixes this needs a verifier that does not depend on CRAFT's
recall — a different detector, or a vision model that can read logotypes.

Python also decodes the cover, which is why WebP works — about a quarter of MAL's covers
are WebP and Java's `ImageIO` cannot read them at all.

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

- `GameContext.tsx` defines `API_URL` beside `WS_URL` and prefixes the server-sent
  `imageUrl` (a bare `/image/{id}` path) with it, so `<img src>` resolves against the
  backend rather than the Vite dev server.
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
