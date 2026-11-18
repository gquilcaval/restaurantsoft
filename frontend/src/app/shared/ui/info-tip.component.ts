import { Component, input, signal } from '@angular/core';

/** Ícono ⓘ con una explicación corta al pasar el mouse, enfocar o tocar (reemplaza textos de ayuda largos). */
@Component({
  selector: 'app-info-tip',
  template: `
    <button type="button" class="tip" [class.open]="open()" [attr.aria-label]="text()" (click)="open.set(!open())" (blur)="open.set(false)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM12 11v5M12 8h.01" /></svg>
      <span role="tooltip">{{ text() }}</span>
    </button>
  `,
  styles: `
    :host { display: inline-flex; vertical-align: middle; margin-left: 4px; }
    .tip { position: relative; width: 22px; height: 22px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--muted); cursor: help; }
    .tip:hover, .tip:focus-visible, .tip.open { color: var(--brand-accent); background: var(--brand-soft); }
    svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; }
    span {
      position: absolute; z-index: 30; left: 50%; bottom: calc(100% + 8px); width: max-content; max-width: 260px; padding: 9px 11px;
      border-radius: 10px; background: var(--text); color: var(--surface); font-size: .76rem; font-weight: 600; line-height: 1.4;
      text-align: left; text-transform: none; letter-spacing: 0; white-space: normal; box-shadow: var(--shadow);
      opacity: 0; visibility: hidden; transform: translate(-50%, 4px); transition: opacity .15s, transform .15s, visibility 0s .15s; pointer-events: none;
    }
    .tip:hover span, .tip:focus-visible span, .tip.open span { opacity: 1; visibility: visible; transform: translate(-50%, 0); transition-delay: 0s; }
  `,
})
export class InfoTipComponent {
  readonly text = input.required<string>();
  readonly open = signal(false);
}
