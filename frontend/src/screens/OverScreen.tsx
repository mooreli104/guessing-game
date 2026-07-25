import { useGame } from "../GameContext";

export default function OverScreen() {
  const { state, backToLobby } = useGame();

  const standings = Object.entries(state.finalScores)
    .map(([id, p]) => ({ id, name: p.name, score: p.score }))
    .sort((a, b) => b.score - a.score);

  return (
    <div className="center">
      <h1>Game Over</h1>
      {state.winner && <div className="winner">🏆 {state.winner} wins!</div>}

      <table className="scores">
        <tbody>
          {standings.map((r) => (
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

      <button onClick={backToLobby}>Back to Lobby</button>
    </div>
  );
}
