<script lang="ts">
  import { Aperture, Focus, Gauge, Image, Scan, Thermometer, Video } from "@lucide/svelte";
  import { engine } from "./engine.svelte";
  import { ISO_STOPS, SHUTTER_STOPS, WHITE_BALANCE, ev, focus, modeLabel, nearest, shutter } from "./format";
  import Segmented from "./ui/Segmented.svelte";
  import StepSlider from "./ui/StepSlider.svelte";
  import Toggle from "./ui/Toggle.svelte";

  const s = $derived(engine.settings!);
  const lens = $derived(engine.lens!);
  const live = $derived(engine.live);
  const highSpeed = $derived(engine.mode?.highSpeed ?? false);
  const manualSensorOk = $derived(lens.manualSensor && !highSpeed);

  const isoStops = $derived(ISO_STOPS.filter((v) => !lens.iso || (v >= lens.iso[0] && v <= lens.iso[1])));
  const isoNow = $derived(s.manualExposure ? s.iso : live?.iso || 400);
  const shutterStops = $derived(
    SHUTTER_STOPS.map((d) => Math.round(1e9 / d)).filter((ns) => ns <= 1e9 / s.fps && (!lens.exposureNs || ns >= lens.exposureNs[0])),
  );
  const shutterNow = $derived(s.manualExposure ? s.shutterNs : live?.exposureNs || 1e9 / s.fps);
  const evSteps = $derived(Array.from({ length: lens.ev[1] - lens.ev[0] + 1 }, (_, i) => lens.ev[0] + i));
  const wb = $derived(WHITE_BALANCE.filter((w) => lens.awbModes.includes(w.mode)));
  const maxZoom = $derived(Math.min(lens.maxZoom, 8));
  const focusNow = $derived(s.manualFocus ? s.focusDiopters : live?.focusDiopters ?? 0);

  // Going manual keeps the picture as it is: seed the other value from what auto chose.
  function setIso(i: number) {
    engine.set({ manualExposure: true, iso: isoStops[i], shutterNs: s.manualExposure ? s.shutterNs : Math.max(live?.exposureNs ?? 0, 1e6) });
  }
  function setShutter(i: number) {
    engine.set({ manualExposure: true, shutterNs: shutterStops[i], iso: s.manualExposure ? s.iso : live?.iso || s.iso });
  }
</script>

<div class="panel-scroll">
  <section>
    <h3><Video size={15} /> Camera</h3>
    <Segmented
      options={engine.lenses.map((l) => ({ value: l.id, label: l.name }))}
      value={s.lensId}
      onchange={(id) => engine.set({ lensId: id, zoom: 1 })}
    />
    <div class="gap"></div>
    <Segmented
      wrap
      options={lens.modes.map((m) => ({ value: `${m.width}x${m.height}@${m.fps}`, label: modeLabel(m), hint: m.highSpeed ? "bright light" : undefined }))}
      value={`${s.width}x${s.height}@${s.fps}`}
      onchange={(v) => {
        const m = lens.modes.find((x) => `${x.width}x${x.height}@${x.fps}` === v)!;
        engine.set({ width: m.width, height: m.height, fps: m.fps });
      }}
    />
  </section>

  <section>
    <h3><Aperture size={15} /> Exposure</h3>
    {#if manualSensorOk}
      <StepSlider
        label="ISO"
        value={String(isoNow)}
        labels={isoStops.map(String)}
        index={nearest(isoStops, isoNow)}
        auto={!s.manualExposure}
        onpick={setIso}
        onauto={() => engine.set({ manualExposure: false })}
      />
      <StepSlider
        label="Shutter"
        value={shutter(shutterNow)}
        labels={shutterStops.map(shutter)}
        index={nearest(shutterStops, shutterNow)}
        auto={!s.manualExposure}
        onpick={setShutter}
        onauto={() => engine.set({ manualExposure: false })}
      />
    {:else if highSpeed}
      <p class="hint">Manual ISO and shutter are available at 30 fps. At 60 fps the camera exposes automatically; use EV to adjust.</p>
    {/if}
    <StepSlider
      label="EV"
      value={ev(s.ev, lens.evStep)}
      labels={evSteps.map((v) => ev(v, lens.evStep))}
      index={evSteps.indexOf(s.ev)}
      auto={s.ev === 0}
      autoLabel="Reset"
      onpick={(i) => engine.set({ ev: evSteps[i] })}
      onauto={() => engine.set({ ev: 0 })}
    />
    <Toggle label="Lock exposure" hint="Keeps brightness steady when you move" checked={s.aeLock} onchange={(v) => engine.set({ aeLock: v })} />
  </section>

  <section>
    <h3><Thermometer size={15} /> White balance</h3>
    <div class="wb">
      {#each wb as w (w.mode)}
        <button class:on={s.awbMode === w.mode} onclick={() => engine.set({ awbMode: w.mode })}>
          <span>{w.name}</span><small>{w.kelvin}</small>
        </button>
      {/each}
    </div>
    <Toggle label="Lock white balance" checked={s.awbLock} onchange={(v) => engine.set({ awbLock: v })} />
  </section>

  {#if lens.minFocusDiopters > 0}
    <section>
      <h3><Focus size={15} /> Focus</h3>
      <StepSlider
        label="Distance"
        value={s.manualFocus ? focus(s.focusDiopters) : `AF · ${focus(focusNow)}`}
        labels={Array.from({ length: 101 }, (_, i) => (i === 0 ? "∞" : i === 100 ? "Close" : ""))}
        index={Math.round((focusNow / lens.minFocusDiopters) * 100)}
        auto={!s.manualFocus}
        autoLabel="AF"
        onpick={(i) => engine.set({ manualFocus: true, focusDiopters: (i / 100) * lens.minFocusDiopters })}
        onauto={() => engine.set({ manualFocus: false })}
      />
    </section>
  {/if}

  {#if maxZoom > 1}
    <section>
      <h3><Scan size={15} /> Zoom</h3>
      <StepSlider
        label="Zoom"
        value={`${s.zoom.toFixed(1)}×`}
        labels={Array.from({ length: 71 }, (_, i) => (i === 0 ? "1×" : i === 70 ? `${maxZoom}×` : ""))}
        index={Math.round(((s.zoom - 1) / (maxZoom - 1)) * 70)}
        auto={s.zoom <= 1.01}
        autoLabel="1×"
        onpick={(i) => engine.set({ zoom: 1 + (i / 70) * (maxZoom - 1) })}
        onauto={() => engine.set({ zoom: 1 })}
      />
    </section>
  {/if}

  <section>
    <h3><Gauge size={15} /> Stream</h3>
    <Segmented
      options={[
        { value: "H264", label: "H.264", hint: "works everywhere" },
        { value: "HEVC", label: "HEVC", hint: engine.codecs.hevc ? "sharper" : "not on this PC", disabled: !engine.codecs.hevc },
      ]}
      value={s.codec}
      onchange={(v) => engine.set({ codec: v as "H264" | "HEVC" })}
    />
    <StepSlider
      label="Bitrate"
      value={`${Math.round(s.bitrate / 1e6)} Mbps`}
      labels={Array.from({ length: 57 }, (_, i) => (i === 0 ? "4" : i === 56 ? "60" : ""))}
      index={Math.round(s.bitrate / 1e6) - 4}
      showAuto={false}
      onpick={(i) => engine.set({ bitrate: (i + 4) * 1_000_000 })}
    />
    <div class="gap"></div>
    <Segmented
      options={[
        { value: "AUTO", label: "Auto", hint: "follows the phone" },
        { value: "PORTRAIT", label: "Upright", hint: "phone stands tall" },
        { value: "LANDSCAPE", label: "On its side", hint: "phone lies sideways" },
      ]}
      value={s.orientation}
      onchange={(v) => engine.set({ orientation: v as "AUTO" | "LANDSCAPE" | "PORTRAIT" })}
    />
  </section>

  <section>
    <h3><Image size={15} /> Image</h3>
    {#if lens.ois}<Toggle label="Optical stabilization" hint="Steadier handheld video" checked={s.ois} onchange={(v) => engine.set({ ois: v })} />{/if}
    {#if lens.eis && !highSpeed}<Toggle label="Electronic stabilization" hint="Crops the frame slightly" checked={s.eis} onchange={(v) => engine.set({ eis: v })} />{/if}
    {#if !lens.front && !highSpeed}<Toggle label="Flashlight" checked={s.torch} onchange={(v) => engine.set({ torch: v })} />{/if}
  </section>
</div>

<style>
  .panel-scroll {
    height: 100%;
    overflow-y: auto;
    padding: 4px 20px 24px;
  }
  section {
    padding: 16px 0 14px;
    border-bottom: 1px solid var(--line);
  }
  section:last-of-type {
    border-bottom: none;
  }
  h3 {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 12px;
    font-size: 13px;
    font-weight: 650;
    color: var(--text);
  }
  h3 :global(svg) {
    color: var(--accent);
  }
  .gap {
    height: 8px;
  }
  .hint {
    margin: 4px 0 6px;
    color: var(--faint);
    font-size: 12.5px;
    line-height: 1.45;
  }
  .wb {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 6px;
    margin-bottom: 4px;
  }
  .wb button {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 8px 4px;
    border-radius: 12px;
    background: var(--panel-2);
    border: 1px solid var(--line);
    font-size: 12px;
    font-weight: 600;
    color: var(--dim);
    transition: all 0.15s;
  }
  .wb button:hover {
    color: var(--text);
  }
  .wb button.on {
    background: var(--text);
    color: #000;
    border-color: var(--text);
  }
  .wb small {
    font-family: var(--mono);
    font-size: 10px;
    opacity: 0.7;
  }
</style>
