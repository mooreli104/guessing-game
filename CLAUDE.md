# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

OtakuGuessr: a multiplayer anime-guessing game. Players join a room via a 4-character
code, are shown an anime cover image with the title scrubbed out of the artwork, and
race to type the correct title before the round timer runs out. Two independent
projects:

- `backend/` — Java 21 + Javalin WebSocket/HTTP server (package `org.aniguessr`)
- `frontend/` — React 19 + TypeScript SPA (Vite)

They communicate over a JSON WebSocket protocol at `ws://localhost:7070/websocket/game`.

Covers are **not** fetched from MyAnimeList during a round. An offline ingest job
pre-scrubs them into Postgres, and the game serves them from its own
`GET /image/{token}`, so the round path makes no third-party call.

`{token}` is an opaque per-round handle, **never the anime id** — the anime id is
MyAnimeList's own, so `/image/5114` used to hand the answer to anyone who opened the
network tab. See "Image tokens" below before changing that route.

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
| `MAL_CLIENT_ID` | ingest | **required** by ingest; register an app at myanimelist.net/apiconfig |

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

`Db.parse` accepts either a JDBC URL or the `postgres://user:pass@host/db` form that
hosting platforms inject, so `DATABASE_URL` can be wired straight from the platform's
Postgres. TLS is required for remote hosts and disabled for `localhost` and
`*.railway.internal`, which serve no certificate. Credentials from the `postgres://` form
are passed to the driver as properties rather than left in the URL.

`PORT` is read from the environment (7070 when unset) and the server binds `0.0.0.0` —
binding loopback would make it unreachable from outside the container.

### One instance only

`GameManager` holds rooms, players and session mappings in memory. Two instances means two
players can land on different JVMs and never share a room, and sticky sessions do not help
because the room itself only exists in one heap. **Pin the deployment to a single
instance.** Horizontal scaling would require moving room state to something shared.

Related: avoid a free tier that sleeps on idle. Spin-down drops every open WebSocket and
kills in-flight games.

## Security notes

Deliberate, and worth keeping that way:

- Every SQL statement is parameterized; no query is built by string concatenation.
- `Db.parse` splits the credentials out of the platform-injected
  `postgres://user:pass@host/db` URL and `connection()` presents them as driver
  properties, so the password is not in the URL the driver may echo back in an error.
  `Db.toJdbcUrl` still returns the combined form (percent-encoded, so a `&` or `=` in a
  password cannot split the query string) because that is what a developer pastes to
  connect by hand.
- `AnimeRepositoryTest` refuses to run when `TEST_DATABASE_URL` and `DATABASE_URL` name
  the same database — it truncates the anime table.
- Room codes come from `SecureRandom`, not `Math.random()`; four characters over a
  36-character alphabet is short enough to be worth guessing.
- `MAL_CLIENT_ID` is required, with no fallback. A hardcoded client ID used to live in
  `MalClient` as the default, which put a credential in the source and in git history.
  Ingest is an offline job run by hand, so demanding the variable costs nothing.
- Usernames are trimmed and capped at 16 characters **server-side** in
  `GameManager.displayName`. `maxLength` in `HomeScreen` is a convenience, not a control:
  the name is broadcast to every other player in the room.
- Nothing renders user text as HTML, so unvalidated names were never an XSS route; the
  cap is about what other players are made to look at.

Still open:

- **`JOIN_ROOM` is unthrottled**, so room codes remain brute-forceable given enough
  attempts even though they are now unpredictable. A rate limit per session is the fix if
  this ever matters.
- **`POST /feedback` is unthrottled too**, and unlike `JOIN_ROOM` it writes a row. Field
  lengths are bounded in `Feedback.from`, so a single request cannot be large, but nothing
  stops a script sending many. A per-IP cooldown is the fix. Nothing renders the stored
  text, so it is a spam problem rather than an injection one.
- **No connection pool.** Each round opens a couple of fresh `DriverManager` connections.
  Fine at party-game scale; see the concurrency section for why it is worth knowing about.
- **The static file handler sets no security headers** (CSP and friends).

## Recurring duplication

These were collapsed once; extend the shared version rather than adding a second copy:

- `GameManager.register` is what `createRoom` and `joinRoom` have in common — build the
  player, seat them, broadcast, and catch them up on any round in progress.
- `GameManager.roomFor(sessionId)` walks `sessionId → playerId → Player → Room` and sends
  the `NOT_IN_ROOM` error. `guess` and `startGame` both go through it.
- `WsRouter.joinedPayload` builds `ROOM_CREATED` and `ROOM_JOINED`, which differ only by
  their type string.

Still hand-rolled in four places: "read an env var, fall back to a default" —
`App.port`, `IngestMain.poolSize`, `MalClient.clientId`, and `SCRUB_PYTHON` in
`TitleScrubber`. Small enough that a shared helper has not earned its keep, but a fifth
would change that.

## Git workflow

Commit on every branch, and commit incrementally per feature rather than batching
unrelated changes into one commit — each commit should represent one coherent
feature or fix.

## Architecture

### Backend: session/player/room model

The server is single-process and holds all *game* state in memory; the anime pool lives
in Postgres. `App.java` wires up Javalin with one WebSocket endpoint (`/websocket/game`)
and two HTTP endpoints (`/health`, `/image/{token}`), plus the static frontend. All game
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
- **`GameState`** enumerates `LOBBY → ROUND_ACTIVE → ROUND_SCORING → GAME_OVER`, then
  back to `LOBBY` for replay. Plain constants: `GameManager` makes every transition
  explicitly with `setState(...)`.
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
  `randomExcluding(usedIds, maxRank)` deliberately does not select the image column —
  starting a round should move a few hundred bytes, with the picture travelling
  separately through `GET /image/{token}`. `maxRank` is the difficulty cap; see below.
- **`MalClient`** is used **only by ingest**, never in the round path. `fetchPage(offset,
  limit)` reads a page of the MAL ranking synchronously. The client ID comes from
  `MAL_CLIENT_ID` and there is no fallback — see Security notes.
- Round timing (`ROUND_ACTIVE` duration, the post-answer reveal pause) is driven by
  a shared `ScheduledExecutorService` in `GameManager`, not per-room threads.
- `Room` tracks `usedAnimeIds` so an anime never repeats **for the life of the room**,
  not just within one game. `startGame` deliberately does not clear it — it used to, so
  a lobby hitting "play again" could be handed the cover it had just guessed. The only
  thing that empties the set is `startRound`, when the pool runs dry: it clears and
  retries once, on the grounds that repeats beat a dead round. That is reachable now
  that difficulty can narrow the pool to a few hundred rows, and it wipes the whole
  history at once rather than ageing out the oldest entry.

### Feedback

`POST /feedback` takes `{kind, message, contact}` and writes a row to the `feedback`
table. `App.FeedbackForm` is the raw request shape and `Feedback` is the bounded value
that reaches the database; the only way between them is `Feedback.from`, which trims,
caps `message` at 2000 and `contact` at 200, coerces an unknown `kind` to `FEEDBACK`, and
returns null for an empty message (answered as 400). The `maxLength` attributes in
`FeedbackModal` are a convenience, exactly like `HomeScreen`'s username cap — the endpoint
is open and anything can call it directly.

Read it with `SELECT kind, message, contact, created_at FROM feedback ORDER BY id DESC;`.

Nothing renders the stored text back to anyone, so it is not an injection route. It is
also not rate limited — see Security notes.

### Difficulty: the popularity rank

Every anime carries `rank`, its position in MyAnimeList's **by-popularity** ranking (1 =
most watched). That column is the only difficulty axis.

**Ingest asks for `ranking_type=bypopularity`, not `ranking_type=all`.** The `all` ranking
sorts by weighted score, and past the first few hundred entries that is mostly high-rated
OVAs, specials and sequels-of-sequels — adored by few, recognised by fewer, and hopeless
to guess from a cover. It made the whole game feel obscure regardless of pool size. The
rank stored is the one MAL returns in `entry.ranking.rank`, not a locally counted index,
so entries skipped for having no large picture do not shift everything after them.

`GameManager.rankCapFor` turns a difficulty name into a cap: `EASY` 300, `NORMAL` 900,
`HARD` unbounded. **The client sends the name, never a number** — thresholds can then be
retuned without shipping a new bundle, and no client can request an arbitrary slice of the
pool. Anything unknown or absent falls back to normal, so a client that sends no
difficulty still gets a playable game.

Two things to know before touching this:

- **Rank 0 means unranked and passes every cap.** Real ingested rows are always 1 or more;
  0 is what the `Anime` constructors without a rank produce, which keeps tests that do not
  care about difficulty from having to name one.
- **`Db.createTable` needs both the `CREATE` and the `ALTER`.** `CREATE TABLE IF NOT
  EXISTS` does nothing whatsoever to an existing table, so on any database ingested before
  `rank` existed the column would silently never appear. The `ALTER TABLE ... ADD COLUMN
  IF NOT EXISTS` is what actually migrates those.

Changing the ranking type means the stored pool is the wrong pool — `TRUNCATE anime`
before re-running ingest rather than just re-running it, since `existingIds()` would
otherwise skip every row, and they would all sit at rank 0 and land in Easy.

### Concurrency: the room lock

Three thread pools touch a `Room`: Jetty's WebSocket threads (one per incoming message),
`GameManager`'s scheduler thread, and Javalin's HTTP handlers. `Room`'s fields are not
synchronized or `volatile` and `connectedPlayers` is a plain `HashMap`, so the invariant
is that **every read or write of room state happens inside `synchronized (room)` in
`GameManager`** — which is the only class that touches a `Room`. Accessors that hand out
collections (`getPlayers`, `getUsedAnimeIds`, `getGuessedCorrectly`) return copies, so a
caller cannot mutate room state from outside the lock by accident.

The one thing that must never happen inside that block is a repository call. It opens a
JDBC connection, and holding the room lock across it blocks every guess in that room.
`startRound` is written around this: it reads `usedAnimeIds` under the lock, runs both
`randomExcluding` attempts outside it, then takes the lock again to install the result.
`advanceAfterReveal` sets a `nextRound` flag inside the lock and calls `startRound` after
releasing it, for the same reason.

Two facts about the scheduler that are easy to trip over:

- It is `Executors.newSingleThreadScheduledExecutor()` — **one thread for every room in
  the process.** A slow task in one room's transition delays round ends everywhere. This
  is survivable only because nothing slow runs on it; keep it that way.
- `App` installs a shutdown hook calling `GameManager.shutdown()`, or the scheduler's
  non-daemon thread keeps the JVM alive after the server stops.

There is no connection pool (see `Db`'s class comment). Each round opens a couple of
fresh `DriverManager` connections. Fine at party-game scale, but it is why keeping the
queries out of the lock matters more than it looks.

### Input from the network is untrusted

`WsRouter.onMessage` reads every field through `text(node, field)` or
`intOr(node, field, fallback)`, never `node.get(field).asText()`. The direct form threw
`NullPointerException` out of the handler for any message that simply omitted a field, so
any client could produce server-side exceptions at will and got no reply explaining why
nothing happened. Malformed JSON is caught around `readTree` and answered with
`ERROR BAD_MESSAGE`.

Usernames are bounded in `GameManager.displayName`, not in the browser — see Security
notes.

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

### Image tokens

Round covers are served from `GET /image/{token}` where the token is a `UUID` minted by
`GameManager.issueImageToken` at the start of each round, held on the `Room`, and mapped
back to an anime id through `GameManager.imageTokens`.

**Do not go back to `/image/{animeId}`.** `MalClient` stores MyAnimeList's own id as the
primary key, so the URL used to be `/image/5114` — and `myanimelist.net/anime/5114` is
Fullmetal Alchemist: Brotherhood. Right-click, "Copy image address", and the whole
scrubbing pipeline was moot. The token also stops players enumerating the public MAL id
space to pre-download and fingerprint the pool.

Tokens are retired when the round they belong to is replaced (`issueImageToken` releases
the previous one), when the game ends, and when the room empties. A spent or unknown
token is a 404, which is why the route needs no parsing or validation — there is no
`parseId` any more and nothing to reject.

Because a token names one fixed image for the life of a round, the route sets
`Cache-Control: private, max-age=3600, immutable`; the cover was previously re-read from
Postgres and re-sent on every render.

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

### Round lifecycle invariants

These each fix a bug that was live once. Keep them when editing `GameManager`.

- **`startGame` only runs from `LOBBY`.** Starting mid-game left the running round's
  timer orphaned; it then fired and cut the new round short, and everyone's scores were
  reset on the way. The state is checked before the `repository.count()` call and again
  after, since the lock is released across it. `startRound` also cancels any surviving
  `roundTask` before installing its own.
- **`removePlayer` clears the leaver from `guessedCorrectly`.** The early-finish check
  compares that set's size against the player count, so a departed player left behind in
  it ended later rounds early. It then re-runs `endRoundIfEveryoneGuessed`, because the
  player who left may have been the last one everyone was waiting on — otherwise the
  round sat there running out its clock with nobody able to guess.
- **`register` catches new arrivals up on a round in progress**, via the same
  `sendRoundCatchUp` that `resume` uses. A mid-round joiner used to receive only
  `ROOM_UPDATE` and sat on the lobby screen until the next round began.
- **`resume` checks the room code**, not just the `playerId`. The code argument was
  accepted and ignored. It also drops the `sessionToPlayer` entry for the socket being
  replaced, which otherwise lingered when the old socket never closed.
- **`winnerName` breaks ties on name**, so the result does not depend on `HashMap`
  iteration order.

### Dead code

Removed, and worth knowing so nobody goes looking for them:

- `GameState.nextState()` and `GameStateTest` — the real machine branches, so a single
  successor per state could not describe it, and `GameManager` always called `setState`
  directly. The enum is now plain constants.
- `App.parseId` and `AppTest` — `/image/{token}` takes an opaque string, so there is
  nothing to parse and no malformed input to reject.
- `Anime.setId`, and Guava and Gson from `build.gradle` (Jackson does all the JSON).

`GameManager.GuessResult` is returned to `WsRouter`, which discards it; it stays because
`GameManagerTest` asserts on it and it is the honest return type for the operation.
`getAllRoomsSnapshot()` is likewise a test accessor with no production caller, which is
deliberate and documented under the missing `/rooms` route.

### WebSocket protocol

The wire protocol is defined in two places that must be kept in sync manually:
`WsRouter.onMessage` / `GameManager` broadcast payloads on the backend, and
`frontend/src/types.ts` (`ClientMsg` / `ServerMsg` union types) on the frontend.
Message `type` values: `CREATE_ROOM`, `JOIN_ROOM`, `RESUME`, `START_GAME`, `GUESS`,
`LEAVE_ROOM` (client→server); `ROOM_CREATED`, `ROOM_JOINED`, `ROOM_UPDATE`,
`ROUND_START`, `GUESS_RESULT`, `ROUND_END`, `GAME_OVER`, `ERROR` (server→client).

`START_GAME` carries `rounds`, `roundSeconds` and `difficulty` (`EASY` / `NORMAL` /
`HARD`, typed as `Difficulty` in `types.ts`). The difficulty is a name rather than a rank
number on purpose — see the difficulty section above.

**`ERROR` carries a machine-readable `code` as well as a human `message`**, typed as
`ErrorCode` in `types.ts`: `BAD_MESSAGE`, `ROOM_NOT_FOUND`, `SESSION_EXPIRED`,
`NOT_IN_ROOM`, `NOT_HOST`, `ALREADY_STARTED`, `NO_ANIME`. Branch on the code, never on
the message text — the client used to test `message.includes("expired")` to decide that
resume was impossible, so rewording a string silently broke recovery.

### Frontend: single reducer over the WebSocket stream

`GameContext.tsx` owns the entire client state machine as one `useReducer` whose
`reducer` is effectively a switch over every possible `ServerMsg.type` — the React
equivalent of a single `handleMessage` dispatcher. Messages sent before the socket is
open are queued in `outboxRef` and flushed on `onopen`.

- **The socket reconnects itself.** `ws.onclose` schedules `connect()` again with a
  linear backoff from 500ms to 5s, and an `unmounted` flag distinguishes a deliberate
  teardown from a dropped connection. Without this the backend's grace-period/RESUME
  machinery only ever ran on a manual page refresh, and a server redeploy or a network
  blip left the page silently dead. Keep the first retries well inside the server's
  10s `DISCONNECT_GRACE_SECONDS` or RESUME will be too late to help.
- `state.connected` drives the `.offline` banner in `App.tsx`; it is fixed-position so
  the game underneath does not jump when the connection flaps.
- `GameContext.tsx` defines `API_URL` beside `WS_URL` and prefixes the server-sent
  `imageUrl` (a bare `/image/{token}` path) with it, so `<img src>` resolves against the
  backend rather than the Vite dev server.
- `playerId` and room `code` are persisted to `sessionStorage` so a page refresh can
  RESUME instead of dropping the player from their room (see backend RESUME above).
  `ERROR` with code `SESSION_EXPIRED` clears them and resets to the home screen. Use
  `forgetIdentity()` rather than `sessionStorage.clear()`, which took anything else the
  origin was storing with it.
- The countdown timer is derived client-side from a server-provided deadline
  (`roundEndsAt = Date.now() + secondsLeft * 1000`) rather than trusting a
  server-pushed tick, so it stays correct across re-renders and resumes.
- Screens (`HomeScreen`, `LobbyScreen`, `GameScreen`, `OverScreen`) are chosen by
  `state.screen` in `App.tsx` and read/write exclusively through `useGame()`
  (`state`, `send`, `backToLobby`, `leaveLobby`) — no other prop drilling or global
  state.

### Visual design

A party-game look on a deep violet canvas, built around one idea: **anything pressable
looks pressable.** Buttons and pills carry a darker bottom lip (`--lip`) and translate
down onto it on `:active`. That is the whole signature — everything else stays quiet, so
resist adding more decoration.

All colour and spacing comes from custom properties at the top of `styles.css`; use those
rather than new literals. Button variants are `.go` (mint, confirm) and `.quiet` (text
only) — note `.quiet` is tuned for the dark canvas and is overridden inside `.panel`,
where the background is cream.

Fonts are **bundled via `@fontsource`, latin subsets only**, not loaded from a CDN. That
keeps the deployed game a single origin with no third-party requests, matching the
backend. Importing the non-subset entrypoints pulls in Cyrillic, Greek and Vietnamese too
and roughly triples the font payload.

`GameScreen` remembers the round length in a ref because state only carries the deadline,
not the duration; the draining timer bar is measured against it.
