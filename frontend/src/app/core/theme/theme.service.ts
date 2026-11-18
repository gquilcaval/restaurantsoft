import { effect, Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark' | 'system';

/** Aplica marca y modo visual mediante variables CSS, sin recompilar la aplicación por restaurante. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly mode = signal<ThemeMode>((localStorage.getItem('restos_theme') as ThemeMode) || 'system');
  readonly primaryColor = signal(localStorage.getItem('restos_primary_color') || '#44d62c');
  /** Efecto vidrio: automático (se apaga en equipos modestos), siempre activo o sólido. */
  readonly glass = signal(this.initialGlass());

  /** Luz que sigue al cursor sobre el vidrio: opcional, se recuerda en cada equipo. */
  readonly cursorGlow = signal(this.readFlag('restos_cursor_glow', true));

  /** Animaciones de la interfaz: se pueden apagar en tablets lentas o por comodidad. */
  readonly motion = signal(this.readFlag('restos_motion', !matchMedia('(prefers-reduced-motion: reduce)').matches));
  /** Equipo con pocos núcleos o memoria: se sugiere apagar el vidrio. */
  readonly modestDevice = (() => { const d = navigator as Navigator & { deviceMemory?: number }; return (navigator.hardwareConcurrency || 8) <= 4 || (d.deviceMemory || 8) <= 4; })();

  constructor() {
    effect(() => document.documentElement.classList.toggle('reduce-motion', !this.motion()));
    effect(() => document.documentElement.classList.toggle('solid-ui', !this.glass()));
    effect(() => document.documentElement.classList.toggle('no-glow', !this.cursorGlow()));
    effect(() => {
      const mode = this.mode();
      document.documentElement.dataset['theme'] = mode;
      document.documentElement.style.setProperty('--brand', this.primaryColor());
      document.documentElement.style.setProperty('--on-brand', inkFor(this.primaryColor()));
      localStorage.setItem('restos_theme', mode);
      localStorage.setItem('restos_primary_color', this.primaryColor());
    });
  }

  toggleCursorGlow(): void {
    this.cursorGlow.update(value => !value);
    try { localStorage.setItem('restos_cursor_glow', this.cursorGlow() ? 'on' : 'off'); } catch { /* sin almacenamiento */ }
  }

  toggleMotion(): void {
    this.motion.update(value => !value);
    try { localStorage.setItem('restos_motion', this.motion() ? 'on' : 'off'); } catch { /* sin almacenamiento */ }
  }

  /** Vuelve a los valores de fábrica en este equipo. */
  resetPreferences(): void {
    try { ['restos_glass', 'restos_cursor_glow', 'restos_motion', 'restos_theme_user_set'].forEach(key => localStorage.removeItem(key)); } catch { /* sin almacenamiento */ }
    this.glass.set(!this.modestDevice); this.cursorGlow.set(true); this.motion.set(true); this.mode.set('system');
  }

  private readFlag(key: string, fallback: boolean): boolean {
    try { const value = localStorage.getItem(key); return value ? value === 'on' : fallback; } catch { return fallback; }
  }

  toggleGlass(): void {
    this.glass.update(value => !value);
    try { localStorage.setItem('restos_glass', this.glass() ? 'on' : 'off'); } catch { /* sin almacenamiento */ }
  }

  /** Tablets de pocos núcleos o memoria usan superficies sólidas: el desenfoque consume mucha GPU. */
  private initialGlass(): boolean {
    let saved: string | null = null;
    try { saved = localStorage.getItem('restos_glass'); } catch { /* sin almacenamiento */ }
    if (saved) return saved === 'on';
    const device = navigator as Navigator & { deviceMemory?: number };
    return (navigator.hardwareConcurrency || 8) > 4 && (device.deviceMemory || 8) > 4;
  }

  setMode(mode: ThemeMode): void {
    localStorage.setItem('restos_theme_user_set', 'true');
    this.mode.set(mode);
  }

  cycle(): void {
    localStorage.setItem('restos_theme_user_set', 'true');
    this.mode.update(mode => mode === 'light' ? 'dark' : mode === 'dark' ? 'system' : 'light');
  }

  /** Aplica la marca del restaurante respetando el modo elegido por el usuario. */
  applyBrand(primaryColor: string, defaultTheme: string): void {
    this.primaryColor.set(primaryColor);
    if (!localStorage.getItem('restos_theme_user_set')) this.mode.set(defaultTheme.toLowerCase() as ThemeMode);
  }
}

/** Texto legible sobre el color de marca: oscuro sobre colores claros, blanco sobre colores intensos. */
function inkFor(hex: string): string {
  const value = /^#?([0-9a-f]{6})$/i.exec(hex.trim())?.[1];
  if (!value) return '#ffffff';
  const [r, g, b] = [0, 2, 4].map(i => {
    const c = parseInt(value.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.36 ? '#04110a' : '#ffffff';
}
