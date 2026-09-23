<script lang="ts">
  import { invoke, Channel } from "@tauri-apps/api/core";
  import { onMount } from "svelte";

  let { active }: { active: boolean } = $props();

  let canvas: HTMLCanvasElement;
  let box: HTMLDivElement;
  let hasPicture = $state(false);
  let unsupported = $state<string | null>(null);

  /**
   * Live preview decoded by WebCodecs on the GPU, from the same encoded
   * stream the virtual camera gets. Nothing is re-encoded for it.
   */
  onMount(() => {
    const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true })!;
    let decoder: VideoDecoder | null = null;
    let codec = 1;
    let waitingForKey = true;
    let rotation = 0;
    let latest: VideoFrame | null = null;
    let raf = 0;

    const reset = () => {
      try {
        decoder?.close();
      } catch {}
      decoder = null;
      waitingForKey = true;
    };

    const draw = () => {
      raf = requestAnimationFrame(draw);
      const frame = latest;
      if (!frame) return;
      latest = null;
      const dpr = window.devicePixelRatio || 1;
      const cw = Math.round(box.clientWidth * dpr);
      const ch = Math.round(box.clientHeight * dpr);
      if (canvas.width !== cw || canvas.height !== ch) {
        canvas.width = cw;
        canvas.height = ch;
      }
      const turned = rotation % 180 !== 0;
      const fw = turned ? frame.displayHeight : frame.displayWidth;
      const fh = turned ? frame.displayWidth : frame.displayHeight;
      const scale = Math.min(cw / fw, ch / fh);
      ctx.fillStyle = "#000";
      ctx.fillRect(0, 0, cw, ch);
      ctx.save();
      ctx.translate(cw / 2, ch / 2);
      ctx.rotate((rotation * Math.PI) / 180);
      const w = frame.displayWidth * scale;
      const h = frame.displayHeight * scale;
      ctx.drawImage(frame, -w / 2, -h / 2, w, h);
      ctx.restore();
      frame.close();
      if (!hasPicture) hasPicture = true;
    };
    raf = requestAnimationFrame(draw);

    const codecString = (data: Uint8Array): string => {
      if (codec === 2) return "hev1.1.6.L153.B0";
      for (let i = 0; i + 4 < data.length; i++) {
        if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1 && (data[i + 3] & 0x1f) === 7) {
          const hex = (b: number) => b.toString(16).padStart(2, "0");
          return `avc1.${hex(data[i + 4])}${hex(data[i + 5])}${hex(data[i + 6])}`;
        }
      }
      return "avc1.640028";
    };

    const channel = new Channel<ArrayBuffer>();
    channel.onmessage = (buffer) => {
      const bytes = new Uint8Array(buffer);
      const view = new DataView(buffer);
      if (bytes[0] === 0x10) {
        codec = bytes[1];
        reset();
        return;
      }
      if (bytes[0] !== 0x11) return;
      const key = bytes[1] === 1;
      const pts = Number(view.getBigInt64(2, true));
      rotation = view.getUint16(10, true);
      const data = bytes.subarray(12);
      if (waitingForKey) {
        if (!key) return;
        const config: VideoDecoderConfig = { codec: codecString(data), optimizeForLatency: true, hardwareAcceleration: "prefer-hardware" };
        decoder = new VideoDecoder({
          output: (frame) => {
            latest?.close();
            latest = frame;
          },
          error: () => reset(),
        });
        try {
          decoder.configure(config);
        } catch (err) {
          unsupported = codec === 2 ? "HEVC preview is not available on this PC. The virtual camera still works." : String(err);
          reset();
          return;
        }
        waitingForKey = false;
      }
      // Never queue up: a busy decoder skips ahead to the next key frame.
      if (!decoder || decoder.decodeQueueSize > 3) {
        waitingForKey = !key;
        if (!key) return;
      }
      try {
        decoder?.decode(new EncodedVideoChunk({ type: key ? "key" : "delta", timestamp: pts, data }));
      } catch {
        reset();
      }
    };

    let running = false;
    const sync = () => {
      const want = active && document.visibilityState === "visible";
      if (want && !running) {
        running = true;
        waitingForKey = true;
        invoke("start_preview", { channel });
      } else if (!want && running) {
        running = false;
        invoke("stop_preview");
        reset();
      }
    };
    const onVisibility = () => sync();
    document.addEventListener("visibilitychange", onVisibility);
    const stopEffect = $effect.root(() => {
      $effect(() => {
        active;
        sync();
        if (!active) hasPicture = false;
      });
    });

    return () => {
      stopEffect();
      document.removeEventListener("visibilitychange", onVisibility);
      cancelAnimationFrame(raf);
      invoke("stop_preview");
      reset();
      latest?.close();
    };
  });
</script>

<div class="preview" bind:this={box}>
  <canvas bind:this={canvas} class:visible={hasPicture}></canvas>
  {#if unsupported}
    <p class="note">{unsupported}</p>
  {/if}
</div>

<style>
  .preview {
    position: absolute;
    inset: 0;
  }
  canvas {
    width: 100%;
    height: 100%;
    display: block;
    opacity: 0;
    transition: opacity 0.4s ease;
  }
  canvas.visible {
    opacity: 1;
  }
  .note {
    position: absolute;
    left: 50%;
    bottom: 24px;
    transform: translateX(-50%);
    color: var(--dim);
    font-size: 13px;
  }
</style>
