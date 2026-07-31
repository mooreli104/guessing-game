import { useEffect, useRef, useState } from "react";
import { useGame } from "../GameContext";

export default function GameScreen() {
  const { state, send } = useGame();
  const [guess, setGuess] = useState("");
  const [remaining, setRemaining] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  // Derive the countdown from the server-provided deadline so it stays in sync
  // across re-renders and survives a resume mid-round.
  useEffect(() => {
    if (!state.roundEndsAt) {
      setRemaining(0);
      return;
    }
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

  return (
    <div className="game">
      <div className="top">
        <div>
          Round {state.round} / {state.totalRounds}
        </div>
        <div className="timer">{remaining}s</div>
      </div>

      <img className="animeImg" src={state.imageUrl} alt="Guess the anime" />
      <div className="answer">{state.answer && "Answer: " + state.answer}</div>

      <div className="row">
        <input
          ref={inputRef}
          autoFocus
          placeholder="Type your guess and press Enter"
          value={guess}
          disabled={state.guessed}
          onChange={(e) => setGuess(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submitGuess()}
        />
      </div>
      <div className="feedback">{state.feedback}</div>

      <table className="scores">
        <tbody>
          {scoreboard.map((r) => (
            <tr key={r.id}>
              <td>
                {r.name}
                {r.id === state.playerId ? " (you)" : ""}
              </td>
              <td className="score">{r.score}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
