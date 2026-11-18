import { Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';

interface RatingPrompt {
  waiterName: string | null;
  tableLabel: string | null;
  rated: boolean;
}

const POSITIVE = [
  { value: 'FAST', label: '⚡ Rápido' },
  { value: 'FRIENDLY', label: '😊 Amable' },
  { value: 'FOOD', label: '😋 Comida rica' },
  { value: 'ATTENTIVE', label: '👀 Atento' },
  { value: 'CLEAN', label: '✨ Limpio' },
];
const NEGATIVE = [
  { value: 'SLOW', label: '🐢 Demoró' },
  { value: 'COLD_FOOD', label: '🥶 Comida fría' },
  { value: 'FORGOTTEN', label: '🙈 Nos olvidaron' },
  { value: 'FRIENDLY', label: '😊 Igual fue amable' },
];
const LABELS = ['', 'Muy mala', 'Mala', 'Regular', 'Buena', '¡Excelente!'];

/** Calificación del servicio al terminar: estrellas, aspectos destacados y un comentario opcional. */
@Component({
  selector: 'app-diner-rating',
  imports: [FormsModule],
  template: `
    @if (sent()) {
      <div class="rating-thanks">
        <div class="rating-confetti" aria-hidden="true">
          @for (piece of confetti; track $index) {
            <i [style.--x]="piece.x" [style.--d]="piece.d" [style.--c]="piece.c"></i>
          }
        </div>
        <strong>¡Gracias por tu opinión! 💚</strong>
        <small
          >Llega directo al equipo{{
            prompt()?.waiterName ? ' y a ' + prompt()!.waiterName : ''
          }}.</small
        >
      </div>
    } @else if (prompt(); as info) {
      <div class="rating-card">
        <strong>{{
          info.waiterName ? '¿Cómo te atendió ' + info.waiterName + '?' : '¿Cómo estuvo tu visita?'
        }}</strong>
        <div class="rating-stars" role="radiogroup" aria-label="Calificación">
          @for (star of [1, 2, 3, 4, 5]; track star) {
            <button
              role="radio"
              [attr.aria-checked]="stars() === star"
              [attr.aria-label]="star + (star === 1 ? ' estrella' : ' estrellas')"
              [class.on]="star <= (hover() || stars())"
              [style.--i]="star"
              (click)="choose(star)"
              (pointerenter)="hover.set(star)"
              (pointerleave)="hover.set(0)"
            >
              ★
            </button>
          }
        </div>
        <small class="rating-label">{{ labels[hover() || stars()] || 'Toca una estrella' }}</small>
        @if (stars()) {
          <div class="rating-chips">
            @for (option of stars() >= 4 ? positive : negative; track option.value) {
              <button [class.on]="highlights().has(option.value)" (click)="toggle(option.value)">
                {{ option.label }}
              </button>
            }
          </div>
          <textarea
            [(ngModel)]="comment"
            maxlength="500"
            rows="2"
            placeholder="¿Algo más que quieras contarnos? (opcional)"
          ></textarea>
          <button class="primary full" [disabled]="busy()" (click)="send()">
            {{ busy() ? 'Enviando…' : 'Enviar calificación' }}
          </button>
        }
      </div>
    }
  `,
  styleUrl: './diner-rating.component.scss',
})
export class DinerRatingComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly sessionId = input.required<string>();
  readonly guestToken = input.required<string>();
  readonly prompt = signal<RatingPrompt | null>(null);
  readonly stars = signal(0);
  readonly hover = signal(0);
  readonly highlights = signal<Set<string>>(new Set());
  readonly busy = signal(false);
  readonly sent = signal(false);
  readonly positive = POSITIVE;
  readonly negative = NEGATIVE;
  readonly labels = LABELS;
  readonly confetti = Array.from({ length: 24 }, (_, index) => ({
    x: `${Math.round((Math.random() - 0.5) * 260)}px`,
    d: `${index * 25}ms`,
    c: ['#44d62c', '#f5c542', '#ff6b5f', '#72b7e8', '#c084fc'][index % 5],
  }));
  comment = '';

  ngOnInit(): void {
    this.api
      .get<RatingPrompt>(
        `/public/sessions/${this.sessionId()}/rating`,
        undefined,
        this.guestToken(),
      )
      .subscribe({
        next: (value) => (value.rated ? this.sent.set(true) : this.prompt.set(value)),
        error: () => undefined,
      });
  }

  choose(star: number): void {
    if (star >= 4 !== this.stars() >= 4) this.highlights.set(new Set());
    this.stars.set(star);
  }

  toggle(value: string): void {
    this.highlights.update((current) => {
      const next = new Set(current);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });
  }

  send(): void {
    if (!this.stars() || this.busy()) return;
    this.busy.set(true);
    this.api
      .post(
        `/public/sessions/${this.sessionId()}/rating`,
        {
          stars: this.stars(),
          highlights: [...this.highlights()],
          comment: this.comment.trim() || null,
        },
        undefined,
        this.guestToken(),
      )
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.sent.set(true);
        },
        error: () => this.busy.set(false),
      });
  }
}
