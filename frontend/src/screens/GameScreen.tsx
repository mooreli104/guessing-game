import { useEffect, useState } from "react";
import { useGame } from "../GameContext";

export default function GameScreen() {
  const { state, send } = useGame();
  const [guess, setGuess] = useState("");
  const [remaining, setRemaining] = useState(0);

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
