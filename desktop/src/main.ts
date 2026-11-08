import "@fontsource-variable/inter";
import "@fontsource-variable/jetbrains-mono";
import "./app.css";
import { invoke } from "@tauri-apps/api/core";
import { mount } from "svelte";
import App from "./App.svelte";

// The release window has no devtools: write script errors to %TEMP%\phonecam-ui.log.
const report = (what: string, err: unknown) => {
  const text = err instanceof Error ? `${err.message}\n${err.stack ?? ""}` : String(err);
  invoke("js_log", { line: `${new Date().toISOString()} ${what}: ${text}` }).catch(() => {});
};
window.addEventListener("error", (e) => report("error", e.error ?? e.message));
window.addEventListener("unhandledrejection", (e) => report("unhandled rejection", e.reason));

mount(App, { target: document.getElementById("app")! });
