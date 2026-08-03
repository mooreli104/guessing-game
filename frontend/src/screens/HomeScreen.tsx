import { useState } from "react";
import { useGame } from "../GameContext";

export default function HomeScreen() {
  const { send } = useGame();
  const [username, setUsername] = useState("");
  const [code, setCode] = useState("");
  const [hint, setHint] = useState("");

  function createRoom() {
    if (!username.trim()) return setHint("Pick a name first");
    setHint("");
    send({ type: "CREATE_ROOM", username: username.trim() });
  }

  function joinRoom() {
    if (!username.trim()) return setHint("Pick a name first");
    if (!code.trim()) return setHint("Enter the 4-character room code");
    setHint("");
    send({ type: "JOIN_ROOM", username: username.trim(), code: code.trim().toUpperCase() });
  }

  return (
    <div className="center">
      <div className="wordmark">
        <h1>
          Otaku<span>Guessr</span>
        </h1>
        <div className="tagline">The title is scrubbed off the cover. Type it first.</div>
      </div>

      <div className="panel stack">
        <div>
          <div className="field-label">Your name</div>
          <input
            placeholder="Who's playing?"
            value={username}
            maxLength={16}
            onChange={(e) => setUsername(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && createRoom()}
          />
        </div>

        <button className="wide" onClick={createRoom}>
          Create room
        </button>

        <div className="divider">or</div>

        <div className="join-row">
          <input
            placeholder="CODE"
            value={code}
            maxLength={4}
            onChange={(e) => setCode(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && joinRoom()}
          />
          <button className="go" onClick={joinRoom}>
            Join
          </button>
        </div>

        <div className="hint">{hint}</div>
      </div>

      <div className="footer">
        {/* A real link, not a route: this is a static page outside the SPA, and it is what
            makes that page discoverable to a crawler. The .html is deliberate -- see the
            comment in public/how-to-play.html. */}
        <a href="/how-to-play.html">How to play</a>
      </div>
    </div>
  );
}
