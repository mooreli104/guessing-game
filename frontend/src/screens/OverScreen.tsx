import { useGame } from "../GameContext";

export default function OverScreen() {
  const { state, backToLobby } = useGame();

  const standings = Object.entries(state.finalScores)
    .map(([id, p]) => ({ id, name: p.name, score: p.score }))
    .sort((a, b) => b.score - a.score);

  return (
    <div className="center">
      <div className="panel stack">
        <div>
          <div className="trophy">🏆</div>
          <div className="winner">{state.winner ? state.winner + " wins" : "Game over"}</div>
        </div>

        <ul className="standings">
          {standings.map((r, i) => (
            <li key={r.id} className={i === 0 ? "first" : ""}>
              <span className="rank">{i + 1}</span>
              <span>
                {r.name}
                {r.id === state.playerId ? " (you)" : ""}
              </span>
              <span className="points">{r.score}</span>
            </li>
          ))}
        </ul>

        <button className="go wide" onClick={backToLobby}>
          Back to lobby
        </button>
      </div>
    </div>
  );
}
