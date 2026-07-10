import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./index.css";
// tokens.css must load after index.css: it remaps the legacy variables
// onto the brand tokens and adds explicit light/dark theme overrides.
import "./ui/tokens.css";
import "./ui/kit.css";
import { initThemeMode } from "./ui/theme";
import { UiKitGallery } from "./ui/UiKitGallery";
import { GrantAccessScreen } from "./ui/GrantAccessScreen";

initThemeMode();

const search = new URLSearchParams(window.location.search);
const showUiKit = search.has("uikit");
// The Android→browser Picker hand-off gets a dedicated single-purpose page
// instead of the full app shell (see docs/join-flow.md).
const showGrant = search.get("grant-files") === "1" || search.get("grant-folder") === "1";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {showUiKit ? <UiKitGallery /> : showGrant ? <GrantAccessScreen /> : <App />}
  </StrictMode>
);
