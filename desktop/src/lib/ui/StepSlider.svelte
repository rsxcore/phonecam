<script lang="ts">
  /** A slider over a fixed list of values (ISO stops, shutter speeds…), with an Auto switch. */
  let {
    label,
    value,
    labels,
    index,
    auto = false,
    autoLabel = "Auto",
    showAuto = true,
    onpick,
    onauto,
  }: {
    label: string;
    value: string;
    labels: string[];
    index: number;
    auto?: boolean;
    autoLabel?: string;
    showAuto?: boolean;
    onpick: (i: number) => void;
    onauto?: () => void;
  } = $props();

  const max = $derived(Math.max(labels.length - 1, 1));
  const pct = $derived((Math.min(Math.max(index, 0), max) / max) * 100);
</script>

<div class="control" class:auto>
  <div class="head">
    <span class="label">{label}</span>
    <span class="value">{value}</span>
    {#if showAuto && onauto}
      <button class="chip" class:on={auto} onclick={onauto}>{autoLabel}</button>
    {/if}
  </div>
  <input
    type="range"
    min="0"
    max={max}
    step="1"
    value={index}
    style="--pct: {pct}%"
    oninput={(e) => onpick(Number((e.currentTarget as HTMLInputElement).value))}
  />
  <div class="ends">
    <span>{labels[0]}</span>
    <span>{labels[labels.length - 1]}</span>
  </div>
</div>

<style>
  .control {
    padding: 12px 0 6px;
  }
  .head {
    display: flex;
    align-items: baseline;
    gap: 10px;
  }
  .label {
    color: var(--dim);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  .value {
    font-family: var(--mono);
    font-weight: 600;
    font-size: 15px;
  }
  .chip {
    margin-left: auto;
    align-self: center;
  }
  .ends {
    display: flex;
    justify-content: space-between;
    color: var(--faint);
    font-family: var(--mono);
    font-size: 11px;
  }
</style>
