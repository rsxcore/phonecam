<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { Link, MonitorPlay, RefreshCw, Smartphone, Trash2, X } from "@lucide/svelte";
  import { onMount } from "svelte";
  import { engine } from "./engine.svelte";

  let { onclose }: { onclose: () => void } = $props();

  type Phone = { fingerprint: string; name: string; address: string };
  let phones = $state<Phone[]>([]);
  let address = $state("");
  let message = $state<string | null>(null);

  async function refresh() {
    phones = await invoke<Phone[]>("phones");
  }
  onMount(refresh);

  async function forget(p: Phone) {
    await invoke("forget_phone", { fingerprint: p.fingerprint });
    await refresh();
  }

  async function connect() {
    try {
      await engine.connect(address.trim() || null);
      message = address.trim() ? `Connecting to ${address.trim()}…` : "Searching automatically";
    } catch (e) {
      message = String(e);
    }
  }

  async function reinstall() {
    try {
      await invoke("reinstall_camera");
      message = "Virtual camera registered. Restart OBS or your calling app if it was open.";
    } catch (e) {
      message = String(e);
    }
  }

  async function uninstall() {
    try {
      await invoke("uninstall_camera");
      message = "Virtual camera removed. It comes back the next time PhoneCam starts.";
    } catch (e) {
      message = String(e);
    }
  }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && onclose()} />

<div class="backdrop" onclick={onclose} role="presentation">
  <div class="dialog" onclick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" tabindex="-1" onkeydown={() => {}}>
    <header>
      <h2>Settings</h2>
      <button class="icon" onclick={onclose} title="Close"><X size={18} /></button>
    </header>

    <section>
      <h3><Smartphone size={15} /> Paired phones</h3>
      {#if phones.length === 0}
        <p class="faint">No phones yet. Open PhoneCam on your phone and approve this PC.</p>
      {/if}
      {#each phones as p (p.fingerprint)}
        <div class="row">
          <div class="grow">
            <div>{p.name}</div>
            <small>{p.address || "—"} · {p.fingerprint.slice(0, 16).match(/.{4}/g)?.join(" ")}</small>
          </div>
          <button class="ghost danger" onclick={() => forget(p)}><Trash2 size={14} /> Forget</button>
        </div>
      {/each}
    </section>

    <section>
      <h3><Link size={15} /> Connect by address</h3>
      <p class="faint">Only needed if automatic search can't find the phone. The address is shown in the phone's settings.</p>
      <div class="inline">
        <input bind:value={address} placeholder="192.168.1.42 — leave empty for automatic" onkeydown={(e) => e.key === "Enter" && connect()} />
        <button class="primary" onclick={connect}>Connect</button>
      </div>
    </section>

    <section>
      <h3><MonitorPlay size={15} /> Virtual camera</h3>
      <p class="faint">
        Shows up as <b>PhoneCam</b> in OBS, Discord, Zoom and browsers. Pick 1920×1080 at 60 fps in OBS for the smoothest video.
      </p>
      <div class="inline">
        <button class="ghost" onclick={reinstall}><RefreshCw size={14} /> Repair</button>
        <button class="ghost danger" onclick={uninstall}><Trash2 size={14} /> Remove</button>
      </div>
    </section>

    {#if message}<p class="message">{message}</p>{/if}

    <footer>
      PhoneCam 2.0 · this PC is <b>{engine.pcName}</b> · free &amp; open source (MIT)
    </footer>
  </div>
</div>

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    backdrop-filter: blur(6px);
    display: grid;
    place-items: center;
    z-index: 10;
    animation: fade 0.15s ease;
  }
  .dialog {
    width: min(560px, calc(100vw - 48px));
    max-height: calc(100vh - 64px);
    overflow-y: auto;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 22px;
    padding: 20px 24px 18px;
    box-shadow: 0 30px 80px rgba(0, 0, 0, 0.6);
    animation: rise 0.2s ease;
  }
  @keyframes fade {
    from {
      opacity: 0;
    }
  }
  @keyframes rise {
    from {
      transform: translateY(12px);
      opacity: 0;
    }
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  h2 {
    margin: 0;
    font-size: 20px;
    letter-spacing: -0.02em;
  }
  section {
    padding: 16px 0;
    border-bottom: 1px solid var(--line);
  }
  h3 {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 10px;
    font-size: 13px;
  }
  h3 :global(svg) {
    color: var(--accent);
  }
  .row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 0;
  }
  .grow {
    flex: 1;
    min-width: 0;
  }
  .row small {
    color: var(--faint);
    font-family: var(--mono);
    font-size: 11px;
  }
  .inline {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }
  .faint {
    color: var(--faint);
    font-size: 13px;
    line-height: 1.5;
    margin: 0;
  }
  .message {
    margin: 14px 0 0;
    padding: 10px 12px;
    border-radius: 12px;
    background: var(--panel-2);
    font-size: 13px;
    color: var(--dim);
  }
  footer {
    margin-top: 16px;
    color: var(--faint);
    font-size: 12px;
  }
  footer b {
    color: var(--dim);
  }
</style>
