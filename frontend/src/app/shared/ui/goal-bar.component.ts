import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { SoundService } from './sound.service';
import { IconComponent } from './icon.component';
import { ToastService } from './toast.service';

interface GoalProgress {
  date: string;
  goal: number;
  revenue: number;
  percent: number | null;
  projected: number | null;
  reached: boolean;
}

const DAYS = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'];

/**
 * Meta del día: «Llevamos S/ 3,200 de S/ 4,500 🎯», con proyección al cierre y celebración al cumplirla.
 * Los encargados definen la meta de cada día de la semana desde aquí mismo.
 */
@Component({
  selector: 'app-goal-bar',
  imports: [CurrencyPipe, FormsModule, IconComponent],
  templateUrl: './goal-bar.component.html',
  styleUrl: './goal-bar.component.scss',
})
export class GoalBarComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);
  private readonly sound = inject(SoundService);
  private readonly toast = inject(ToastService);
  readonly size = input<'compact' | 'large'>('compact');
  readonly progress = signal<GoalProgress | null>(null);
  readonly celebrating = signal(false);
  readonly editing = signal(false);
  readonly days = DAYS;
  amounts: number[] = [0, 0, 0, 0, 0, 0, 0];
  private timer?: number;
  private refreshTimer?: number;
  private unwatch?: () => void;
  private stopResync?: () => void;
  readonly manager = computed(() =>
    ['BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role()),
  );
  readonly percent = computed(() => Math.min(100, Number(this.progress()?.percent || 0)));
  readonly missing = computed(() =>
    Math.max(0, Number(this.progress()?.goal || 0) - Number(this.progress()?.revenue || 0)),
  );
  readonly mood = computed(() => {
    const value = this.progress();
    if (!value || !value.goal) return '';
    if (value.reached) return '¡Meta cumplida! 🎉 Todo lo que entre ahora es extra.';
    if (value.projected && value.projected >= value.goal) return '🔥 A este ritmo la superamos';
    if (value.projected)
      return `⚡ Al ritmo actual cerraríamos en S/ ${Math.round(value.projected).toLocaleString('en-US')}`;
    return '💪 ¡Vamos equipo!';
  });
  readonly milestones = [25, 50, 75];

  ngOnInit(): void {
    this.load();
    this.timer = window.setInterval(() => this.load(), 60000);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => this.load());
    const branch = this.auth.branchId();
    if (branch && this.auth.role() !== 'KITCHEN')
      this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, (event) => {
        if (event.type === 'PAYMENT_REGISTERED' || event.type === 'GOAL_UPDATED') {
          window.clearTimeout(this.refreshTimer);
          this.refreshTimer = window.setTimeout(() => this.load(), 800);
        }
      });
  }

  ngOnDestroy(): void { this.stopResync?.();
    if (this.timer) window.clearInterval(this.timer);
    window.clearTimeout(this.refreshTimer);
    this.unwatch?.();
  }

  load(): void {
    this.api.get<GoalProgress>('/staff/team/goal').subscribe({
      next: (value) => {
        const before = this.progress();
        this.progress.set(value);
        if (value.reached && value.goal > 0)
          this.celebrateOnce(value.date, !!before && !before.reached);
      },
      error: () => undefined,
    });
  }

  /** Celebra una sola vez por día y equipo; con sonido si se cumplió mientras la pantalla estaba abierta. */
  private celebrateOnce(date: string, live: boolean): void {
    const key = `restos_goal_celebrated_${this.auth.branchId()}_${date}`;
    try {
      if (localStorage.getItem(key)) return;
      localStorage.setItem(key, '1');
    } catch {
      /* sin almacenamiento */
    }
    this.celebrating.set(true);
    if (live) this.sound.play('payment');
    this.toast.show(
      '¡Meta del día cumplida! 🎉',
      'Gracias equipo, lo lograron.',
      'success',
      '🎯',
      8000,
    );
    window.setTimeout(() => this.celebrating.set(false), 4500);
  }

  openEditor(): void {
    this.api.get<number[]>('/admin/team/goals').subscribe({
      next: (value) => {
        this.amounts = value.map((amount) => Number(amount) || 0);
        this.editing.set(true);
      },
      error: () => undefined,
    });
  }

  copyToAll(index: number): void {
    this.amounts = this.amounts.map(() => this.amounts[index]);
  }

  save(): void {
    this.api
      .put<number[]>('/admin/team/goals', {
        amounts: this.amounts.map((value) => Math.max(0, Number(value) || 0)),
      })
      .subscribe({
        next: () => {
          this.editing.set(false);
          this.toast.show('Metas guardadas', 'El equipo ya ve la meta de hoy.', 'success', '🎯');
          this.load();
        },
        error: (err) =>
          this.toast.show(
            'No se pudo guardar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          ),
      });
  }
}
