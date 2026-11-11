import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles/app.css";

const mountNode = document.getElementById("App") ?? document.getElementById("root");

if (mountNode) {
  createRoot(mountNode).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}
