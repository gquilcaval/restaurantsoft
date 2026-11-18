import { Component, computed, input, output } from '@angular/core';
import { OrderItemView } from '../../core/api/models';

interface RoundProgress {
  round: number;
  stage: number;
  total: number;
  ready: number;
  delivered: number;
  names: string;
}

const STEPS = [
  { icon: '📋', label: 'Recibido' },
  { icon: '👨‍🍳', label: 'Preparando' },
  { icon: '🚶', label: 'En camino' },
  { icon: '🍽️', label: 'Servido' },
];

/**
 * Seguimiento del pedido como en una app de delivery: cada ronda avanza de «recibido» a «servido».
 * En modo compacto es una píldora flotante que lleva al detalle del pedido.
 */
@Component({
  selector: 'app-diner-tracker',
  template: `
    @if (compact()) {
      @if (current(); as round) {
        <button class="tracker-pill" [attr.data-stage]="round.stage" (click)="openOrder.emit()">
          <span class="tracker-pill-icon">{{ steps[round.stage - 1].icon }}</span>
          <span class="tracker-pill-copy">
            <strong>{{ headline(round) }}</strong>
            <small>Ronda {{ round.round }} · toca para ver tu pedido</small>
          </span>
          <i class="tracker-pill-bar"><b [style.width.%]="(round.stage / 4) * 100"></b></i>
        </button>
      }
    } @else {
      @for (round of rounds(); track round.round) {
        <article
          class="tracker-card"
          [attr.data-stage]="round.stage"
          [class.done]="round.stage === 4"
        >
          <header>
            <div>
              <small>Ronda {{ round.round }}</small>
              <strong>{{ headline(round) }}</strong>
            </div>
            <span class="tracker-badge">{{
              round.stage === 4 ? '✓' : steps[round.stage - 1].icon
            }}</span>
          </header>
          <div
            class="tracker-steps"
            role="progressbar"
            aria-valuemin="1"
            aria-valuemax="4"
            [attr.aria-valuenow]="round.stage"
          >
            <i class="tracker-line"><b [style.width.%]="((round.stage - 1) / 3) * 100"></b></i>
            @for (step of steps; track step.label; let index = $index) {
              <span
                class="tracker-step"
                [class.reached]="index < round.stage"
                [class.current]="index === round.stage - 1"
              >
                <em>{{ step.icon }}</em
                ><small>{{ step.label }}</small>
              </span>
            }
          </div>
          <p class="tracker-detail">{{ round.names }}</p>
        </article>
      }
    }
  `,
  styleUrl: './diner-tracker.component.scss',
})
export class DinerTrackerComponent {
  readonly items = input.required<OrderItemView[]>();
  readonly compact = input(false);
  readonly openOrder = output<void>();
  readonly steps = STEPS;

  readonly rounds = computed<RoundProgress[]>(() => {
    const groups = new Map<number, OrderItemView[]>();
    for (const item of this.items()) {
      if (item.orderStatus === 'DRAFT' || item.status === 'DRAFT' || item.status === 'CANCELLED')
        continue;
      groups.set(item.roundNumber, [...(groups.get(item.roundNumber) || []), item]);
    }
    return [...groups.entries()]
      .sort((a, b) => b[0] - a[0])
      .map(([round, items]) => {
        const count = (status: string) =>
          items
            .filter((item) => item.status === status)
            .reduce((sum, item) => sum + item.quantity, 0);
        const total = items.reduce((sum, item) => sum + item.quantity, 0);
        const delivered = count('DELIVERED');
        const ready = count('READY');
        const stage = delivered === total ? 4 : ready > 0 ? 3 : count('PREPARING') > 0 ? 2 : 1;
        return {
          round,
          stage,
          total,
          ready,
          delivered,
          names: items.map((item) => `${item.quantity}× ${item.name}`).join(' · '),
        };
      });
  });

  /** La ronda en curso más reciente; las ya servidas no ocupan la píldora flotante. */
  readonly current = computed(() => this.rounds().find((round) => round.stage < 4) || null);

  headline(round: RoundProgress): string {
    if (round.stage === 4) return '¡Buen provecho! Todo está en tu mesa';
    if (round.stage === 3)
      return round.ready + round.delivered === round.total
        ? '¡Listo! Va en camino a tu mesa'
        : `${round.ready + round.delivered} de ${round.total} platos listos, van en camino`;
    if (round.stage === 2) return 'La cocina está preparando tu pedido';
    return 'Tu pedido llegó a la cocina';
  }
}
