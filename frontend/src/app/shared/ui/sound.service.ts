import { inject, Injectable, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

export type SoundKind = 'ready' | 'call' | 'payment' | 'order' | 'complete';

/**
 * Sonidos de aviso generados con Web Audio (sin archivos): campanita para plato listo, timbre para
 * llamadas y cuenta, caja registradora para pagos y un toque suave para pedidos nuevos.
 * Cada usuario puede silenciarlos; la preferencia se recuerda en este equipo.
 */
@Injectable({ providedIn: 'root' })
export class SoundService {
  private readonly auth = inject(AuthService);
  private context: AudioContext | null = null;
  private lastPlayed = new Map<SoundKind, number>();
  readonly enabled = signal(this.readPreference());

  constructor() {
    // Los navegadores solo permiten sonar después de una interacción del usuario, y al bloquear el
    // celular vuelven a suspender el audio: se reactiva en cada toque y al volver a la pantalla.
    const unlock = () => {
      const ctx = this.audio();
      if (ctx && ctx.state !== 'running') ctx.resume().catch(() => undefined);
    };
    document.addEventListener('pointerdown', unlock, { passive: true });
    document.addEventListener('keydown', unlock);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && this.context) unlock(); });
  }

  /** Relee la preferencia del usuario conectado (en tablets compartidas cambia de usuario con PIN). */
  reload(): void {
    this.enabled.set(this.readPreference());
  }

  toggle(): void {
    this.enabled.update((value) => !value);
    try {
      localStorage.setItem(this.key(), this.enabled() ? 'on' : 'off');
    } catch {
      /* sin almacenamiento */
    }
    if (this.enabled()) this.play('order', true);
  }

  /** Vibración corta al confirmar una acción en el celular (Android; iPhone no lo permite en la web). */
  haptic(kind: 'tap' | 'success' = 'success'): void {
    try {
      navigator.vibrate?.(kind === 'tap' ? 12 : [14, 40, 22]);
    } catch {
      /* sin soporte */
    }
  }

  /** Traduce un evento en tiempo real al sonido que le corresponde. */
  forEvent(type: string): void {
    const kind: Record<string, SoundKind> = {
      ORDER_ITEM_READY: 'ready',
      WAITER_CALLED: 'call',
      WAITER_CALL_ESCALATED: 'call',
      BILL_REQUESTED: 'call',
      BILL_WAITING: 'call',
      PRINT_FAILED: 'call',
      DISH_WAITING: 'ready',
      TABLE_WAITER_CHANGED: 'order',
      PAYMENT_REGISTERED: 'payment',
      ORDER_SUBMITTED: 'order',
    };
    if (!kind[type]) return;
    this.play(kind[type]);
    // En el celular del personal, además del sonido, vibra (Android; iPhone no lo permite en la web).
    if (this.enabled()) {
      const pattern: Partial<Record<SoundKind, number[]>> = { call: type === 'WAITER_CALL_ESCALATED' ? [400, 120, 400, 120, 400, 120, 400] : [260, 120, 260, 120, 260], ready: [180, 90, 180], payment: [120], order: [90] };
      try { navigator.vibrate?.(pattern[kind[type]] || [120]); } catch { /* sin soporte */ }
    }
  }

  play(kind: SoundKind, force = false): void {
    if (!this.enabled() && !force) return;
    const now = Date.now();
    // Varios eventos seguidos (por ejemplo, tres platos listos) suenan una sola vez.
    if (!force && now - (this.lastPlayed.get(kind) || 0) < 1200) return;
    this.lastPlayed.set(kind, now);
    const ctx = this.audio();
    if (!ctx) return;
    // Si quedó suspendido (pantalla bloqueada), se intenta reactivar y sonar en cuanto se pueda.
    if (ctx.state !== 'running') { ctx.resume().then(() => { if (ctx.state === 'running') { this.lastPlayed.delete(kind); this.play(kind, true); } }).catch(() => undefined); return; }
    const start = ctx.currentTime + 0.01;
    if (kind === 'complete') {
      // «Ta-da» de cuatro notas ascendentes: el pedido completo está listo para entregar.
      [523.3, 659.3, 784, 1046.5].forEach((frequency, index) => this.tone(ctx, frequency, start + index * 0.11, index === 3 ? 1.1 : 0.35, 0.2, 'triangle'));
      this.tone(ctx, 2093, start + 0.33, 0.8, 0.05);
    } else if (kind === 'ready') {
      this.tone(ctx, 1318.5, start, 1.4, 0.22);
      this.tone(ctx, 2637, start, 0.9, 0.07);
      this.tone(ctx, 1975.5, start + 0.16, 1.2, 0.16);
    } else if (kind === 'call') {
      this.tone(ctx, 659.3, start, 0.7, 0.25, 'triangle');
      this.tone(ctx, 523.3, start + 0.38, 0.9, 0.25, 'triangle');
    } else if (kind === 'payment') {
      this.noise(ctx, start, 0.06);
      this.tone(ctx, 2093, start + 0.06, 0.18, 0.12, 'square');
      this.tone(ctx, 2637, start + 0.12, 0.2, 0.1, 'square');
      this.tone(ctx, 1568, start + 0.2, 0.9, 0.2);
      this.tone(ctx, 3136, start + 0.2, 0.6, 0.06);
    } else {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.setValueAtTime(440, start);
      osc.frequency.exponentialRampToValueAtTime(880, start + 0.12);
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.25);
      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(start + 0.3);
    }
  }

  private tone(
    ctx: AudioContext,
    frequency: number,
    start: number,
    duration: number,
    volume: number,
    type: OscillatorType = 'sine',
  ): void {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, start);
    gain.gain.exponentialRampToValueAtTime(volume, start + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
    osc.connect(gain).connect(ctx.destination);
    osc.start(start);
    osc.stop(start + duration + 0.05);
  }

  private noise(ctx: AudioContext, start: number, duration: number): void {
    const buffer = ctx.createBuffer(1, Math.floor(ctx.sampleRate * duration), ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let index = 0; index < data.length; index++)
      data[index] = (Math.random() * 2 - 1) * (1 - index / data.length);
    const source = ctx.createBufferSource();
    const filter = ctx.createBiquadFilter();
    const gain = ctx.createGain();
    filter.type = 'highpass';
    filter.frequency.value = 3000;
    gain.gain.value = 0.25;
    source.buffer = buffer;
    source.connect(filter).connect(gain).connect(ctx.destination);
    source.start(start);
  }

  private audio(): AudioContext | null {
    if (this.context) return this.context;
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return null;
    this.context = new Ctor();
    return this.context;
  }

  private key(): string {
    return `restos_sound_${this.auth.userName()}_${this.auth.role()}`;
  }

  private readPreference(): boolean {
    try {
      return localStorage.getItem(this.key()) !== 'off';
    } catch {
      return true;
    }
  }
}
