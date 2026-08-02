import { useState } from "react";
import { useGame } from "../GameContext";

// The settings fields hold strings, not numbers, and are only clamped once the user
// leaves them. Two bugs came out of doing it the other way round:
//
//   - Clamping on every keystroke made the minimum unreachable from above. Typing "45"
//     into Seconds (min 5) hit Math.max(5, 4) on the first keystroke, so the box became
//     "5" and the next keystroke gave "55".
//   - React never normalises a type="number" box whose text already parses to the state
//     number -- it compares them with != , not !== (react-dom updateProperties). With a
//     number in state, "010" == 10 is true, so a stray leading zero was there for good.
//     Comparing string to string leaves that quirk with nothing to bite on.
function clamp(raw: string, min: number): string {
  const n = Math.floor(Number(raw));
  // Number("") is 0 and Number("abc") is NaN; both mean "nothing usable typed".
  return !Number.isFinite(n) || n < min ? String(min) : String(n);
}

export default function LobbyScreen() {
  const { state, send, leaveLobby } = useGame();
  const [rounds, setRounds] = useState("3");
  const [seconds, setSeconds] = useState("30");
  const [copied, setCopied] = useState(false);

  const isHost = state.host === state.playerId;
  const players = Object.entries(state.players);

  function startGame() {
    // Clamp again here: the host can hit Start without ever blurring a field.
    send({
      type: "START_GAME",
      rounds: Number(clamp(rounds, 1)),
      roundSeconds: Number(clamp(seconds, 5)),
    });
  }

  function copyCode() {
    if (!state.code) return;
    navigator.clipboard?.writeText(state.code).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      // Clipboard access can be refused; the code is on screen to read out anyway.
      () => setCopied(false)
    );
  }

  return (
    <div className="center">
      <div className="panel stack">
        <div className="code-block">
          <div className="field-label">Room code</div>
          <div className="code">{state.code}</div>
          <button className="quiet" onClick={copyCode}>
            {copied ? "Copied" : "Copy code"}
          </button>
        </div>

        <div>
          <div className="section-title">
            Players ({players.length})
          </div>
          <ul className="players" style={{ marginTop: 8 }}>
            {players.map(([id, p]) => (
              <li key={id} className={id === state.playerId ? "me" : ""}>
                {p.name}
                {id === state.playerId ? " (you)" : ""}
              </li>
            ))}
          </ul>
        </div>

        {isHost ? (
          <>
            <div className="settings">
              <div>
                <div className="field-label">Rounds</div>
                <input
                  type="number"
                  min={1}
                  value={rounds}
                  onChange={(e) => setRounds(e.target.value)}
                  onBlur={() => setRounds(clamp(rounds, 1))}
                />
              </div>
              <div>
                <div className="field-label">Seconds per round</div>
                <input
                  type="number"
                  min={5}
                  value={seconds}
                  onChange={(e) => setSeconds(e.target.value)}
                  onBlur={() => setSeconds(clamp(seconds, 5))}
                />
              </div>
            </div>

            <button className="go wide" onClick={startGame}>
              Start game
            </button>
          </>
        ) : (
          <div className="waiting">Waiting for the host to start…</div>
        )}
      </div>

      <div style={{ textAlign: "center", marginTop: 10 }}>
        <button className="quiet" onClick={leaveLobby}>
          Leave lobby
        </button>
      </div>
    </div>
  );
}
