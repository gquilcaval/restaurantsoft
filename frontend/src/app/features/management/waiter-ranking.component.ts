import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, effect, inject, input, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { MEDALS } from '../../shared/team/team-medals';
import { InfoTipComponent } from '../../shared/ui/info-tip.component';

interface WaiterScore {
  userId: string;
  name: string;
  rating: number | null;
  ratings: number;
  tips: number;
  collected: number;
  tables: number;
  responseMinutes: number | null;
  responses: number;
  score: number;
  medals: string[];
}
interface RatingComment {
  stars: number;
  highlights: string[];
  comment: string | null;
  createdAt: string;
  waiter: string | null;
  tableLabel: string | null;
}
interface WaiterRanking {
  overall: number | null;
  waiters: WaiterScore[];
  comments: RatingComment[];
}

const HIGHLIGHTS: Record<string, string> = {
  FAST: '⚡ Rápido',
  FRIENDLY: '😊 Amable',
  FOOD: '😋 Comida rica',
  ATTENTIVE: '👀 Atento',
  CLEAN: '✨ Limpio',
  SLOW: '🐢 Demoró',
  COLD_FOOD: '🥶 Comida fría',
  FORGOTTEN: '🙈 Nos olvidaron',
};

/** Ranking de meseros: calificaciones de los comensales, propinas y cobros del período. */
@Component({
  selector: 'app-waiter-ranking',
  imports: [CurrencyPipe, DatePipe, InfoTipComponent],
  template: `
    <section class="management-panel ranking-panel">
      <header>
        <div>
          <h2>
            Ranking de meseros<app-info-tip
              text="Calificaciones que dejan los comensales desde el QR, propinas y cobros registrados."
            />
          </h2>
        </div>
        @if (data()?.overall; as overall) {
          <span class="ranking-overall">★ {{ overall }} <small>promedio del local</small></span>
        }
      </header>
      @if (data(); as value) {
        @for (waiter of value.waiters; track waiter.userId; let index = $index) {
          <div class="ranking-row" [style.--delay]="index * 70 + 'ms'">
            <span class="ranking-medal" [attr.data-pos]="index + 1">{{
              index < 3 ? medals[index] : index + 1
            }}</span>
            <div class="ranking-name">
              <strong
                >{{ waiter.name }}
                @for (medal of waiter.medals; track medal) {
                  <i class="ranking-medal-icon" [title]="medalTitle(medal)">{{ medalIcon(medal) }}</i>
                }
              </strong>
              <small
                >{{ waiter.tables }} mesas · {{ waiter.collected | currency: 'PEN' : 'S/ ' }} ·
                {{ waiter.score }} pts
                @if (waiter.responseMinutes !== null) {
                  · ⚡ atiende en {{ waiter.responseMinutes }} min
                }
              </small>
            </div>
            <div class="ranking-stars" [title]="waiter.ratings + ' calificaciones'">
              @if (waiter.rating !== null) {
                <span class="ranking-star-bar"
                  ><i [style.width.%]="(waiter.rating / 5) * 100"></i
                ></span>
                <b>{{ waiter.rating }}</b
                ><small>({{ waiter.ratings }})</small>
              } @else {
                <small>Sin calificaciones</small>
              }
            </div>
            <div class="ranking-tips">
              <small>Propinas</small><strong>{{ waiter.tips | currency: 'PEN' : 'S/ ' }}</strong>
            </div>
          </div>
        } @empty {
          <p class="management-caption">Aún no hay meseros con actividad en este período.</p>
        }
        @if (value.comments.length) {
          <h3 class="ranking-subtitle">Lo que dicen los clientes</h3>
          <div class="ranking-comments">
            @for (comment of value.comments; track $index) {
              <article [class.low]="comment.stars <= 3">
                <header>
                  <span
                    >{{ '★'.repeat(comment.stars) }}<i>{{ '★'.repeat(5 - comment.stars) }}</i></span
                  >
                  <small
                    >{{ comment.tableLabel }}
                    @if (comment.waiter) {
                      · {{ comment.waiter }}
                    }
                    · {{ comment.createdAt | date: 'dd/MM HH:mm' }}</small
                  >
                </header>
                @if (comment.highlights.length) {
                  <p class="ranking-chips">
                    @for (tag of comment.highlights; track tag) {
                      <span>{{ label(tag) }}</span>
                    }
                  </p>
                }
                @if (comment.comment) {
                  <p>“{{ comment.comment }}”</p>
                }
              </article>
            }
          </div>
        }
      }
    </section>
  `,
  styleUrl: './waiter-ranking.component.scss',
})
export class WaiterRankingComponent {
  private readonly api = inject(ApiService);
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly data = signal<WaiterRanking | null>(null);
  readonly medals = ['🥇', '🥈', '🥉'];

  constructor() {
    effect(() => {
      const from = this.from();
      const to = this.to();
      this.api
        .get<WaiterRanking>(`/admin/reports/waiters?from=${from}&to=${to}`)
        .subscribe({ next: (value) => this.data.set(value), error: () => this.data.set(null) });
    });
  }

  medalIcon(medal: string): string {
    return MEDALS[medal]?.icon || '';
  }

  medalTitle(medal: string): string {
    return MEDALS[medal]?.title || '';
  }

  label(tag: string): string {
    return HIGHLIGHTS[tag] || tag;
  }
}
