import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { SoundService } from '../../shared/ui/sound.service';

interface PublicWaitlist {
  customerName: string;
  partySize: number;
  status: 'WAITING' | 'NOTIFIED' | 'SEATED' | 'CANCELLED' | 'NO_SHOW';
  position: number;
  estimatedMinutes: number | null;
  tableLabel: string | null;
  notifiedAt: string | null;
  restaurant: string;
  logoUrl: string | null;
  color: string | null;
  branchName: string;
}

/**
 * Turno del cliente en la lista de espera: posición en vivo, tiempo estimado y una pantalla de aviso
 * (con vibración y sonido) cuando su mesa está lista. No requiere instalar nada ni registrarse.
 */
@Component({
  selector: 'app-waitlist-page',
  templateUrl: './waitlist.page.html',
  styleUrl: './waitlist.page.scss',
})
export class WaitlistPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly sound = inject(SoundService);
  readonly data = signal<PublicWaitlist | null>(null);
  readonly error = signal('');
  readonly soundReady = signal(false);
  readonly cancelling = signal(false);
  private token = '';
  private timer?: number;
  private alerted = false;
  readonly progress = computed(() => {
    const value = this.data();
    if (!value || value.status !== 'WAITING') return 100;
    return Math.max(8, Math.min(92, 100 - (value.position - 1) * 18));
  });

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('t') || '';
    if (!this.token) {
      this.error.set('El enlace no es válido. Pide al restaurante que te lo envíe de nuevo.');
      return;
    }
    this.load();
    this.timer = window.setInterval(() => this.load(), 15000);
    document.addEventListener('visibilitychange', this.onVisible);
  }

  ngOnDestroy(): void {
    if (this.timer) window.clearInterval(this.timer);
    document.removeEventListener('visibilitychange', this.onVisible);
  }

  private readonly onVisible = () => {
    if (!document.hidden) this.load();
  };

  private load(): void {
    this.api.get<PublicWaitlist>(`/public/waitlist/${this.token}`).subscribe({
      next: (value) => {
        this.data.set(value);
        if (value.color) document.documentElement.style.setProperty('--brand', value.color);
        if (value.status === 'NOTIFIED' && !this.alerted) {
          this.alerted = true;
          navigator.vibrate?.([300, 150, 300, 150, 600]);
          this.sound.play('ready', true);
        }
        if (!['WAITING', 'NOTIFIED'].includes(value.status) && this.timer)
          window.clearInterval(this.timer);
      },
      error: (err) => this.error.set(err.error?.message || 'No pudimos cargar tu turno.'),
    });
  }

  /** El navegador solo deja sonar después de un toque; así el aviso se escuchará aunque el celular esté en la mesa. */
  enableSound(): void {
    this.sound.play('order', true);
    this.soundReady.set(true);
  }

  cancel(): void {
    if (this.cancelling()) return;
    this.cancelling.set(true);
    this.api.post<PublicWaitlist>(`/public/waitlist/${this.token}/cancel`, {}).subscribe({
      next: (value) => {
        this.cancelling.set(false);
        this.data.set(value);
      },
      error: () => this.cancelling.set(false),
    });
  }

  initial(name: string): string {
    return name.trim().slice(0, 1).toUpperCase();
  }
}
