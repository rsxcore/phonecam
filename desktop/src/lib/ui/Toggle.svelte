<script lang="ts">
  let { label, hint = "", checked, onchange }: { label: string; hint?: string; checked: boolean; onchange: (v: boolean) => void } = $props();
</script>

<button class="row" role="switch" aria-checked={checked} onclick={() => onchange(!checked)}>
  <span class="text">
    <span>{label}</span>
    {#if hint}<small>{hint}</small>{/if}
  </span>
  <span class="switch" class:on={checked}><span class="knob"></span></span>
</button>

<style>
  .row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 9px 0;
    text-align: left;
  }
  .text {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: 14px;
  }
  small {
    color: var(--faint);
    font-size: 12px;
  }
  .switch {
    width: 40px;
    height: 24px;
    border-radius: 12px;
    background: var(--panel-3);
    position: relative;
    transition: background 0.2s;
    flex: none;
  }
  .switch.on {
    background: var(--accent);
  }
  .knob {
    position: absolute;
    top: 3px;
    left: 3px;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: var(--dim);
    transition: transform 0.2s, background 0.2s;
  }
  .switch.on .knob {
    transform: translateX(16px);
    background: #000;
  }
</style>
