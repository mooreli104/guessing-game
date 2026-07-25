import { useState } from "react";
import { useGame } from "../GameContext";

export default function LobbyScreen() {
  const { state, send, leaveLobby } = useGame();
  const [rounds, setRounds] = useState(3);
  const [seconds, setSeconds] = useState(30);

  const isHost = state.host === state.playerId;

  function startGame() {
    send({ type: "START_GAME", rounds, roundSeconds: seconds });
  }

  return (
    <div className="center">
      <h1>Lobby</h1>
      <div>Room code:</div>
      <div className="code">{state.code}</div>

      <h3>Players</h3>
      <ul className="players">
        {Object.entries(state.players).map(([id, p]) => (
          <li key={id}>
            {p.name}
            {id === state.playerId ? " (you)" : ""}
          </li>
        ))}
      </ul>

      {isHost ? (
        <div className="hostControls">
          <label>
            Rounds:{" "}
            <input
              type="number"
              min={1}
              value={rounds}
              onChange={(e) => setRounds(Number(e.target.value))}
            />
          </label>
          <label>
            Seconds/round:{" "}
            <input
              type="number"
              min={5}
              value={seconds}
              onChange={(e) => setSeconds(Number(e.target.value))}
            />
          </label>
          <div>
            <button onClick={startGame}>Start Game</button>
          </div>
        </div>
      ) : (
        <div>Waiting for the host to start…</div>
      )}

      <div className="row">
        <button onClick={leaveLobby}>Leave Lobby</button>
      </div>
    </div>
  );
}
