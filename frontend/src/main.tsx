import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { GameProvider } from "./GameContext";
import App from "./App";

// Fonts are bundled rather than pulled from a CDN, so the deployed game stays a
// single origin with no third-party requests -- the same property the backend relies on.
// Latin subsets only: the full imports ship Cyrillic, Greek and Vietnamese too, which
// this interface never renders.
import "@fontsource/baloo-2/latin-700.css";
import "@fontsource/baloo-2/latin-800.css";
import "@fontsource/nunito/latin-400.css";
import "@fontsource/nunito/latin-700.css";
import "@fontsource/nunito/latin-800.css";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <GameProvider>
      <App />
    </GameProvider>
  </StrictMode>
);
