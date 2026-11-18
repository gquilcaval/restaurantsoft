import { Component, computed, inject, signal, ViewEncapsulation } from '@angular/core';
import { ThemeService } from '../../core/theme/theme.service';

/**
 * Botón de tema claro/oscuro: muestra a qué se va a cambiar (luna en claro, sol en oscuro)
 * y alterna solo entre esos dos estados, con una transición suave de colores.
 * Uso: <button class="icon-button" appThemeToggle></button>
 */
@Component({
  selector: 'button[appThemeToggle]',
  exportAs: 'themeToggle',
  encapsulation: ViewEncapsulation.None,
  host: {
    type: 'button',
    class: 'theme-toggle-btn',
    '[attr.aria-label]': "isDark() ? 'Cambiar a modo claro' : 'Cambiar a modo oscuro'",
    '[attr.title]': "isDark() ? 'Modo claro' : 'Modo oscuro'",
    '(click)': 'toggle()',
  },
  template: `
    <svg viewBox="0 0 24 24" aria-hidden="true" [class.tt-spin]="spin()">
      @if (isDark()) {
        <circle cx="12" cy="12" r="4.2" />
        <path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6" />
      } @else {
        <path d="M20.5 14.6A8.5 8.5 0 0 1 9.4 3.5a8.5 8.5 0 1 0 11.1 11.1Z" />
      }
    </svg>
    <ng-content />
  `,
  styles: [`
    .theme-toggle-btn { display: inline-grid; place-items: center; }
    .theme-toggle-btn svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .theme-toggle-btn svg.tt-spin { animation: tt-spin .45s cubic-bezier(.2, .9, .3, 1); }
    @keyframes tt-spin { from { transform: rotate(-90deg) scale(.6); opacity: 0; } }
    :root.theme-switching, :root.theme-switching * { transition: background-color .35s ease, color .35s ease, border-color .35s ease, fill .35s ease !important; }
  `],
})
export class ThemeToggleComponent {
  private readonly theme = inject(ThemeService);
  private readonly systemDark = signal(matchMedia('(prefers-color-scheme: dark)').matches);
  readonly spin = signal(false);
  readonly isDark = computed(() => this.theme.mode() === 'dark' || (this.theme.mode() === 'system' && this.systemDark()));

  constructor() {
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (event) => this.systemDark.set(event.matches));
  }

  toggle(): void {
    const root = document.documentElement;
    root.classList.add('theme-switching');
    this.theme.setMode(this.isDark() ? 'light' : 'dark');
    this.spin.set(false);
    requestAnimationFrame(() => this.spin.set(true));
    setTimeout(() => root.classList.remove('theme-switching'), 400);
  }
}
