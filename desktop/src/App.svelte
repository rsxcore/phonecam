<script lang="ts">
  import { Cable, Cpu, MonitorPlay, Settings2, ShieldCheck, Smartphone, TriangleAlert, Wifi, Zap } from "@lucide/svelte";
  import { onMount } from "svelte";
  import CameraPanel from "./lib/CameraPanel.svelte";
  import { engine } from "./lib/engine.svelte";
  import { modeLabel } from "./lib/format";
  import Preview from "./lib/Preview.svelte";
  import SettingsDialog from "./lib/SettingsDialog.svelte";
  import logo from "../../assets/icon.svg";

  let settingsOpen = $state(false);

  onMount(() => {
    engine.start();
  });

  const live = $derived(engine.phase === "live");
  const viaUsb = $derived(engine.address?.startsWith("127.0.0.1") ?? false);
  const streamLabel = $derived(
    engine.settings ? `${modeLabel(engine.settings)} · ${engine.settings.codec === "HEVC" ? "HEVC" : "H.264"}` : "",
  );
</script>

<div class="app">
  <main class="stage">
    <header class="topbar">
      <div class="brand">
        <img src={logo} alt="" />
        <span>PhoneCam</span>
      </div>
      <div class="pill" class:live class:wait={engine.phase === "pairing"}>
        <span class="dot"></span>
        {#if live}
          LIVE · {engine.device ?? "Phone"}
        {:else if engine.phase === "pairing"}
          Waiting for approval
        {:else if engine.phase === "connecting"}
          Connecting…
        {:else if engine.phase === "error"}
          Error
        {:else}
          Searching
        {/if}
      </div>
      <div class="spacer"></div>
      <button class="ghost" class:active={engine.testPattern} onclick={() => engine.toggleTestPattern()} title="Show colour bars in the virtual camera">
        <MonitorPlay size={16} /> Test camera
      </button>
      <button class="icon" onclick={() => (settingsOpen = true)} title="Settings"><Settings2 size={18} /></button>
    </header>

    <section class="viewer">
      <Preview active={live} />
      {#if live}
        <div class="overlay top">
          <span class="tag">{streamLabel}</span>
          <span class="tag">{viaUsb ? "USB" : "Wi-Fi"} · encrypted</span>
        </div>
        {#if engine.phoneError || engine.throttled || engine.thermal >= 2}
          <div class="banner" class:bad={!!engine.phoneError}>
            <TriangleAlert size={15} />
            {#if engine.phoneError}
              {engine.phoneError}{engine.phoneError.includes("Camera") ? " · reopening automatically…" : ""}
            {:else if engine.throttled}
              The phone got hot, so PhoneCam switched to 1080p 30 to keep streaming.
            {:else}
              The phone is getting warm. Taking off the case or using 30 fps helps.
            {/if}
          </div>
        {/if}
      {:else}
        <div class="empty">
          {#if engine.phase === "pairing"}
            <div class="pair">
              <div class="pair-icon"><ShieldCheck size={30} /></div>
              <h2>Confirm on your phone</h2>
              <p>Tap <b>Allow</b> in PhoneCam and check that it shows the same code.</p>
              <div class="code">{engine.pairingCode?.slice(0, 3)} {engine.pairingCode?.slice(3)}</div>
              {#if engine.pairingMismatch}
                <p class="warn"><TriangleAlert size={15} /> The codes differ. Deny the request on your phone.</p>
              {:else}
                <p class="faint">You only do this once.</p>
              {/if}
            </div>
          {:else if engine.phase === "error"}
            <div class="pair">
              <div class="pair-icon bad"><TriangleAlert size={30} /></div>
              <h2>PhoneCam could not start</h2>
              <p>{engine.error}</p>
            </div>
          {:else}
            <div class="radar"><span></span><span></span><span></span><Smartphone size={34} /></div>
            <h2>{engine.phase === "connecting" ? "Connecting to your phone…" : "Looking for your phone"}</h2>
            <ol class="steps">
              <li><span>1</span> Open <b>PhoneCam</b> on your phone</li>
              <li><span>2</span> Connect both to the same Wi-Fi, or plug in USB</li>
              <li><span>3</span> That's it: the camera connects by itself</li>
            </ol>
            {#if engine.error}<p class="faint small">{engine.error}</p>{/if}
          {/if}
        </div>
      {/if}
    </section>

    <footer class="tiles">
      <div class="tile">
        <div class="k"><Zap size={14} /> Frame rate</div>
        <div class="v">{#if engine.stats}{engine.stats.fps.toFixed(0)}<small> fps</small>{:else}<span class="none">—</span>{/if}</div>
      </div>
      <div class="tile">
        <div class="k">{#if viaUsb}<Cable size={14} />{:else}<Wifi size={14} />{/if} Bitrate</div>
        <div class="v">{#if engine.stats}{engine.stats.mbps.toFixed(1)}<small> Mbps</small>{:else}<span class="none">—</span>{/if}</div>
      </div>
      <div class="tile">
        <div class="k"><Cpu size={14} /> Decode</div>
        <div class="v">
          {#if engine.stats}{engine.stats.decodeMs.toFixed(1)}<small> ms{engine.decoder.includes("GPU") ? " · GPU" : ""}</small>{:else}<span class="none">—</span>{/if}
        </div>
      </div>
      <div class="tile camera" class:ok={engine.camera?.ok}>
        <div class="k"><MonitorPlay size={14} /> Virtual camera</div>
        <div class="v small">{engine.camera?.ok ? "“PhoneCam” is ready" : engine.camera?.text ?? "Installing…"}</div>
      </div>
    </footer>
  </main>

  <aside class="side">
    {#if live && engine.settings && engine.lens}
      <CameraPanel />
    {:else}
      <div class="side-empty">
        <Smartphone size={28} />
        <p>Camera controls appear when your phone is connected.</p>
      </div>
    {/if}
  </aside>
</div>

{#if settingsOpen}
  <SettingsDialog onclose={() => (settingsOpen = false)} />
{/if}

<style>
  .app {
    height: 100vh;
    display: grid;
    grid-template-columns: 1fr 360px;
  }
  .stage {
    display: grid;
    grid-template-rows: auto 1fr auto;
    gap: 14px;
    padding: 14px 16px 16px 18px;
    min-width: 0;
    min-height: 0;
  }
  .topbar {
    display: flex;
    align-items: center;
    gap: 12px;
    height: 40px;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    font-weight: 700;
    font-size: 16px;
    letter-spacing: -0.01em;
  }
  .brand img {
    width: 28px;
    height: 28px;
  }
  .spacer {
    flex: 1;
  }
  .pill {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    border-radius: 999px;
    background: var(--panel-2);
    border: 1px solid var(--line);
    font-size: 12.5px;
    font-weight: 600;
    color: var(--dim);
  }
  .pill .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--faint);
  }
  .pill.live {
    color: var(--text);
  }
  .pill.live .dot {
    background: var(--live);
    box-shadow: 0 0 0 4px rgba(255, 59, 48, 0.18);
    animation: pulse 1.6s ease-in-out infinite;
  }
  .pill.wait .dot {
    background: var(--accent);
  }
  @keyframes pulse {
    50% {
      opacity: 0.35;
    }
  }
  .viewer {
    position: relative;
    border-radius: 20px;
    overflow: hidden;
    background: #000;
    border: 1px solid var(--line);
    min-height: 0;
  }
  .overlay.top {
    position: absolute;
    top: 14px;
    left: 14px;
    display: flex;
    gap: 8px;
  }
  .tag {
    padding: 5px 10px;
    border-radius: 999px;
    background: rgba(10, 10, 11, 0.6);
    backdrop-filter: blur(12px);
    border: 1px solid var(--line);
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--text);
  }
  .banner {
    position: absolute;
    left: 50%;
    bottom: 16px;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: 8px;
    max-width: calc(100% - 32px);
    padding: 9px 14px;
    border-radius: 12px;
    background: rgba(40, 30, 0, 0.8);
    border: 1px solid rgba(255, 214, 10, 0.35);
    color: var(--accent);
    font-size: 13px;
    backdrop-filter: blur(12px);
  }
  .banner.bad {
    background: rgba(50, 10, 8, 0.8);
    border-color: rgba(255, 59, 48, 0.4);
    color: #ff8a80;
  }
  .empty {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    padding: 24px;
    background: radial-gradient(ellipse at center, #141417 0%, #050506 70%);
  }
  .empty h2 {
    margin: 22px 0 6px;
    font-size: 22px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }
  .empty p {
    color: var(--dim);
    margin: 4px 0;
    max-width: 440px;
    line-height: 1.5;
  }
  .radar {
    position: relative;
    width: 96px;
    height: 96px;
    display: grid;
    place-items: center;
    color: var(--text);
  }
  .radar span {
    position: absolute;
    inset: 0;
    border-radius: 50%;
    border: 1.5px solid var(--accent);
    opacity: 0;
    animation: ring 2.4s ease-out infinite;
  }
  .radar span:nth-child(2) {
    animation-delay: 0.8s;
  }
  .radar span:nth-child(3) {
    animation-delay: 1.6s;
  }
  @keyframes ring {
    from {
      transform: scale(0.4);
      opacity: 0.9;
    }
    to {
      transform: scale(1.6);
      opacity: 0;
    }
  }
  .steps {
    list-style: none;
    padding: 0;
    margin: 18px 0 8px;
    display: grid;
    gap: 10px;
    text-align: left;
  }
  .steps li {
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--dim);
  }
  .steps li b {
    color: var(--text);
  }
  .steps span {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: var(--panel-2);
    border: 1px solid var(--line);
    font-family: var(--mono);
    font-size: 12px;
    color: var(--text);
  }
  .pair {
    display: flex;
    flex-direction: column;
    align-items: center;
  }
  .pair-icon {
    width: 64px;
    height: 64px;
    border-radius: 20px;
    display: grid;
    place-items: center;
    background: var(--panel-2);
    color: var(--accent);
    border: 1px solid var(--line);
  }
  .pair-icon.bad {
    color: var(--live);
  }
  .code {
    margin: 18px 0 10px;
    font-family: var(--mono);
    font-size: 48px;
    font-weight: 700;
    letter-spacing: 0.12em;
  }
  .warn {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--live) !important;
  }
  .faint {
    color: var(--faint) !important;
  }
  .small {
    font-size: 12.5px;
  }
  .tiles {
    display: grid;
    grid-template-columns: repeat(3, 1fr) 1.4fr;
    gap: 10px;
  }
  .tile {
    padding: 12px 14px;
    border-radius: 16px;
    background: var(--panel);
    border: 1px solid var(--line);
  }
  .tile .k {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--faint);
    font-size: 12px;
    font-weight: 600;
  }
  .tile .v {
    margin-top: 6px;
    font-family: var(--mono);
    font-size: 22px;
    font-weight: 600;
  }
  .tile .v small {
    font-size: 12px;
    color: var(--dim);
    font-weight: 500;
  }
  .tile .none {
    color: var(--faint);
  }
  .tile .v.small {
    font-family: var(--sans);
    font-size: 14px;
    margin-top: 10px;
  }
  .tile.camera.ok .k :global(svg) {
    color: var(--ok);
  }
  .side {
    background: var(--panel);
    border-left: 1px solid var(--line);
    min-height: 0;
  }
  .side-empty {
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: var(--faint);
    text-align: center;
    padding: 32px;
    font-size: 13.5px;
  }
</style>
