import { useState } from "react";
import { useGame } from "../GameContext";

export default function HomeScreen() {
  const { send } = useGame();
  const [username, setUsername] = useState("");
  const [code, setCode] = useState("");

  function createRoom() {
    if (!username) return alert("Enter a name first");
    send({ type: "CREATE_ROOM", username });
  }

  function joinRoom() {
    if (!username || !code) return alert("Enter a name and code");
    send({ type: "JOIN_ROOM", username, code: code.toUpperCase() });
  }

  return (
    <div className="center">
      <h1>AniGuessr</h1>

      <input
        placeholder="Your name"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
      />

      <div className="row">
        <button onClick={createRoom}>Create Room</button>
      </div>

      <div className="row">
        <input
          placeholder="Room code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        <button onClick={joinRoom}>Join Room</button>
      </div>
    </div>
  );
}
