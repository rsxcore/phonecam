<script lang="ts" generics="T">
  /** One-of-many choice rendered as a pill row. */
  let {
    options,
    value,
    onchange,
    wrap = false,
  }: {
    options: { value: T; label: string; hint?: string; disabled?: boolean }[];
    value: T;
    onchange: (v: T) => void;
    wrap?: boolean;
  } = $props();
</script>

<div class="seg" class:wrap>
  {#each options as o (o.label)}
    <button class:on={o.value === value} disabled={o.disabled} onclick={() => onchange(o.value)} title={o.hint ?? ""}>
      <span>{o.label}</span>
      {#if o.hint}<small>{o.hint}</small>{/if}
    </button>
  {/each}
</div>

<style>
  .seg {
    display: flex;
    gap: 4px;
    padding: 4px;
    border-radius: 14px;
    background: var(--panel-2);
    border: 1px solid var(--line);
  }
  .seg.wrap {
    flex-wrap: wrap;
  }
  button {
    flex: 1 1 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1px;
    padding: 7px 8px;
    border-radius: 10px;
    color: var(--dim);
    font-weight: 600;
    font-size: 13px;
    transition: background 0.15s, color 0.15s;
  }
  .seg.wrap button {
    flex: 1 1 30%;
  }
  button:hover:not(:disabled) {
    color: var(--text);
    background: rgba(255, 255, 255, 0.04);
  }
  button.on {
    background: var(--text);
    color: #000;
  }
  button:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }
  small {
    font-size: 10px;
    font-weight: 500;
    opacity: 0.7;
    white-space: nowrap;
  }
</style>
