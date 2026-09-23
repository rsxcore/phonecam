export const ISO_STOPS = [50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400];

/** Denominators of common shutter speeds, fastest first. */
export const SHUTTER_STOPS = [8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 120, 100, 60, 50, 30, 25, 15];

/** Camera2 white-balance modes, warm to cool, with approximate colour temperatures. */
export const WHITE_BALANCE: { mode: number; name: string; kelvin: string }[] = [
  { mode: 1, name: "Auto", kelvin: "AWB" },
  { mode: 2, name: "Tungsten", kelvin: "2700K" },
  { mode: 4, name: "Warm", kelvin: "3000K" },
  { mode: 3, name: "Fluorescent", kelvin: "4000K" },
  { mode: 5, name: "Daylight", kelvin: "5500K" },
  { mode: 6, name: "Cloudy", kelvin: "6500K" },
  { mode: 7, name: "Twilight", kelvin: "7500K" },
  { mode: 8, name: "Shade", kelvin: "8000K" },
];

export function shutter(ns: number): string {
  if (!ns) return "—";
  const d = 1e9 / ns;
  return d >= 1.5 ? `1/${Math.round(d)}` : `${(ns / 1e9).toFixed(1)}s`;
}

export function ev(steps: number, step: number): string {
  const v = steps * step;
  return Math.abs(v) < 0.01 ? "±0" : `${v > 0 ? "+" : ""}${v.toFixed(1)}`;
}

export function focus(d: number): string {
  if (d <= 0.01) return "∞";
  const m = 1 / d;
  return m >= 1 ? `${m.toFixed(1)} m` : `${Math.round(m * 100)} cm`;
}

export function modeLabel(m: { width: number; height: number; fps: number }): string {
  const h = Math.min(m.width, m.height);
  const name = h >= 2160 ? "4K" : `${h}p`;
  return `${name} ${m.fps}`;
}

export function nearest(values: number[], target: number): number {
  let best = 0;
  values.forEach((v, i) => {
    if (Math.abs(v - target) < Math.abs(values[best] - target)) best = i;
  });
  return best;
}
