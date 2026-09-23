import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export type Mode = { width: number; height: number; fps: number; highSpeed: boolean };

export type Lens = {
  id: string;
  name: string;
  front: boolean;
  focalMm: number;
  manualSensor: boolean;
  iso: [number, number] | null;
  exposureNs: [number, number] | null;
  ev: [number, number];
  evStep: number;
  minFocusDiopters: number;
  awbModes: number[];
  ois: boolean;
  eis: boolean;
  maxZoom: number;
  modes: Mode[];
};

export type Settings = {
  lensId: string;
  width: number;
  height: number;
  fps: number;
  codec: "H264" | "HEVC";
  bitrate: number;
  manualExposure: boolean;
  iso: number;
  shutterNs: number;
  ev: number;
  aeLock: boolean;
  awbMode: number;
  awbLock: boolean;
  manualFocus: boolean;
  focusDiopters: number;
  zoom: number;
  ois: boolean;
  eis: boolean;
  torch: boolean;
  orientation: "AUTO" | "LANDSCAPE" | "PORTRAIT";
};

export type Live = { iso: number; exposureNs: number; focusDiopters: number; cameraFps: number };

export type Phase = "starting" | "searching" | "connecting" | "pairing" | "live" | "error";

export type Stats = { fps: number; decodeMs: number; mbps: number; width: number; height: number };

/**
 * Everything the window shows, fed by the Rust engine's events. Settings the
 * user is dragging are applied locally at once and protected from being
 * overwritten by the phone's periodic state for a moment, so sliders never
 * jump back under the cursor.
 */
class EngineStore {
  phase = $state<Phase>("starting");
  status = $state("Starting…");
  error = $state<string | null>(null);
  pairingCode = $state<string | null>(null);
  pairingMismatch = $state(false);
  address = $state<string | null>(null);
  device = $state<string | null>(null);
  android = $state<string | null>(null);
  lenses = $state<Lens[]>([]);
  settings = $state<Settings | null>(null);
  live = $state<Live | null>(null);
  stats = $state<Stats | null>(null);
  phoneFps = $state(0);
  dropped = $state(0);
  encoder = $state("");
  decoder = $state("");
  camera = $state<{ ok: boolean; text?: string; path?: string; system?: boolean } | null>(null);
  codecs = $state<{ h264: boolean; hevc: boolean }>({ h264: true, hevc: false });
  pcName = $state("");
  testPattern = $state(false);
  /** Android thermal status: 0 cool … 3 severe … 6 shutdown. */
  thermal = $state(0);
  throttled = $state(false);
  phoneError = $state<string | null>(null);

  private heldUntil = new Map<string, number>();
  private pending: Partial<Settings> = {};
  private flushTimer: ReturnType<typeof setTimeout> | null = null;

  lens = $derived(this.lenses.find((l) => l.id === this.settings?.lensId) ?? null);
  mode = $derived(
    this.lens?.modes.find((m) => m.width === this.settings?.width && m.height === this.settings?.height && m.fps === this.settings?.fps) ??
      null,
  );

  async start() {
    await listen<Record<string, unknown>>("engine", (e) => this.handle(e.payload));
    const history = await invoke<Record<string, unknown>[]>("ready");
    history.forEach((e) => this.handle(e));
  }

  /** Changes camera settings on the phone. Rapid calls (slider drags) are coalesced. */
  set(change: Partial<Settings>) {
    if (!this.settings) return;
    const now = performance.now();
    for (const key of Object.keys(change)) this.heldUntil.set(key, now + 1200);
    this.settings = { ...this.settings, ...change };
    Object.assign(this.pending, change);
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      const set = this.pending;
      this.pending = {};
      invoke("control", { set });
    }, 40);
  }

  connect(address: string | null) {
    return invoke("connect", { address });
  }

  async registerSystem() {
    const ok = await invoke<boolean>("register_camera_system");
    if (this.camera) this.camera = { ...this.camera, system: ok };
    return ok;
  }

  toggleTestPattern() {
    this.testPattern = !this.testPattern;
    invoke("test_pattern", { on: this.testPattern });
  }

  private handle(e: Record<string, any>) {
    switch (e.event) {
      case "searching":
        if (this.phase !== "live") this.phase = "searching";
        break;
      case "connecting":
        this.address = e.address;
        if (this.phase !== "pairing") this.phase = "connecting";
        break;
      case "pairing":
        this.phase = "pairing";
        this.pairingCode = e.code;
        this.pairingMismatch = e.mismatch;
        break;
      case "connected":
        this.phase = "live";
        this.error = null;
        this.pairingCode = null;
        this.address = e.address;
        break;
      case "disconnected":
        this.phase = "searching";
        this.stats = null;
        this.error = e.reason && !String(e.reason).startsWith("Phone not found") ? e.reason : null;
        break;
      case "hello":
        this.device = e.data.device;
        this.android = e.data.android;
        this.lenses = e.data.lenses;
        this.applyState(e.data.state);
        break;
      case "state":
        this.applyState(e.data);
        break;
      case "stats":
        this.stats = { fps: e.fps, decodeMs: e.decodeMs, mbps: e.mbps, width: e.width, height: e.height };
        break;
      case "decoder":
        this.decoder = e.name;
        break;
      case "status":
        this.status = e.text;
        break;
      case "camera":
        this.camera = { ok: e.ok, text: e.text, path: e.path, system: e.system };
        break;
      case "codecs":
        this.codecs = { h264: e.h264, hevc: e.hevc };
        break;
      case "identity":
        this.pcName = e.name;
        break;
      case "fatal":
        this.phase = "error";
        this.error = e.text;
        break;
    }
  }

  private applyState(s: any) {
    if (!s) return;
    const now = performance.now();
    const incoming = s.settings as Settings;
    if (incoming) {
      const merged = { ...incoming } as Record<string, unknown>;
      if (this.settings) {
        for (const [key, until] of this.heldUntil) {
          if (until > now) merged[key] = (this.settings as Record<string, unknown>)[key];
        }
      }
      this.settings = merged as Settings;
    }
    if (s.live) this.live = s.live;
    this.phoneFps = s.sentFps ?? 0;
    this.dropped = s.dropped ?? 0;
    this.encoder = s.encoder ?? "";
    this.thermal = s.thermal ?? 0;
    this.throttled = s.throttled ?? false;
    this.phoneError = s.error ?? null;
  }
}

export const engine = new EngineStore();
