import { useGame } from "./GameContext";
import HomeScreen from "./screens/HomeScreen";
import LobbyScreen from "./screens/LobbyScreen";
import GameScreen from "./screens/GameScreen";
import OverScreen from "./screens/OverScreen";

export default function App() {
  const { state } = useGame();

  if (state.resuming) {
    return <div className="reconnecting">Reconnecting…</div>;
  }

  return (
    <>
      {state.error && <div className="error">{state.error}</div>}
      {renderScreen(state.screen)}
    </>
  );
}

function renderScreen(screen: string) {
  switch (screen) {
    case "lobby":
      return <LobbyScreen />;
    case "game":
      return <GameScreen />;
    case "over":
      return <OverScreen />;
    default:
      return <HomeScreen />;
  }
}
