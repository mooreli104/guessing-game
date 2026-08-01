import { useEffect, useRef, useState } from "react";
import { useGame } from "../GameContext";

export default function GameScreen() {
  const { state, send } = useGame();
  const [guess, setGuess] = useState("");
  const [remaining, setRemaining] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  // The round length is not in state, only the deadline, so remember how long the
  // round had left when it began -- that is what the draining bar is measured against.
  // After a resume this is the remaining time rather than the full round, so the bar
  // starts full and drains correctly even though it is not the true round length.
  const roundTotal = useRef(1);

  // Derive the countdown from the server-provided deadline so it stays in sync
  // across re-renders and survives a resume mid-round.
  useEffect(() => {
    if (!state.roundEndsAt) {
      setRemaining(0);
      return;
    }
    roundTotal.current = Math.max(1, Math.ceil((state.roundEndsAt - Date.now()) / 1000));
    const tick = () =>
      setRemaining(Math.max(0, Math.ceil((state.roundEndsAt! - Date.now()) / 1000)));
    tick();
    const id = setInterval(tick, 250);
    return () => clearInterval(id);
  }, [state.roundEndsAt]);

  // Put the cursor back in the box whenever the player is able to guess again. A
  // disabled input drops focus, and re-enabling it at the start of the next round does
  // not restore it -- without this the player has to click the box before every guess.
  useEffect(() => {
    if (!state.guessed) inputRef.current?.focus();
  }, [state.round, state.guessed]);

  function submitGuess() {
    const value = guess.trim();
    if (!value) return;
    send({ type: "GUESS", guess: value });
    setGuess("");
  }

  const scoreboard = Object.entries(state.players)
    .map(([id, p]) => ({ id, name: p.name, score: p.score }))
    .sort((a, b) => b.score - a.score);

  const pct = Math.min(100, (remaining / roundTotal.current) * 100);
  const low = remaining <= 5 && remaining > 0;

  return (
    <div className="game">
      <div className="top">
        <div className="round-label">
          Round {state.round} of {state.totalRounds}
        </div>
        <div className={"timer" + (low ? " low" : "")}>{remaining}s</div>
      </div>

      <div className="timer-track">
        <div
          className={"timer-fill" + (low ? " low" : "")}
          style={{ width: pct + "%" }}
        />
      </div>

      <div className="card-frame">
        <img className="animeImg" src={state.imageUrl} alt="Guess this anime" />
      </div>

      <div className="answer">{state.answer && state.answer}</div>

      <input
        ref={inputRef}
        autoFocus
        className="guess-input"
        placeholder={state.guessed ? "Nice - waiting for the others" : "Type the title, press Enter"}
        value={guess}
        disabled={state.guessed}
        onChange={(e) => setGuess(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && submitGuess()}
      />

      <div className={"feedback" + (state.guessed ? "" : " miss")}>{state.feedback}</div>

      <ul className="scores">
        {scoreboard.map((r) => (
          <li key={r.id} className={r.id === state.playerId ? "me" : ""}>
            <span>{r.name}</span>
            <span className="score">{r.score}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
