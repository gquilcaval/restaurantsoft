import { CurrencyPipe } from '@angular/common';
import { Component, computed, input, output, signal } from '@angular/core';
import { GuestView, OrderItemView } from '../../core/api/models';

export interface BillChoice {
  splitMode: 'SINGLE' | 'GUEST' | 'EQUAL';
  splitParts: number | null;
  tipPercent: number;
}

/** Pedir la cuenta desde el celular: cómo dividirla y cuánta propina dejar; el mesero lo recibe listo. */
@Component({
  selector: 'app-diner-bill-sheet',
  imports: [CurrencyPipe],
  template: `
    <div class="bill-backdrop" (click)="cancel.emit()">
      <section
        class="bill-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="bill-title"
        (click)="$event.stopPropagation()"
      >
        <i class="bill-grip" aria-hidden="true"></i>
        <p class="eyebrow">Pedir la cuenta</p>
        <h2 id="bill-title">¿Cómo quieren pagar?</h2>

        <div class="bill-options" role="radiogroup" aria-label="Forma de pago">
          <button
            role="radio"
            [attr.aria-checked]="mode() === 'SINGLE'"
            [class.active]="mode() === 'SINGLE'"
            (click)="mode.set('SINGLE')"
          >
            <span>🧾</span>
            <div>
              <strong>Una sola cuenta</strong><small>Paga una persona por toda la mesa</small>
            </div>
            <b>{{ total() | currency: 'PEN' : 'S/ ' }}</b>
          </button>
          @if (payers().length > 1) {
            <button
              role="radio"
              [attr.aria-checked]="mode() === 'GUEST'"
              [class.active]="mode() === 'GUEST'"
              (click)="mode.set('GUEST')"
            >
              <span>👥</span>
              <div>
                <strong>Cada uno lo suyo</strong
                ><small>{{ payers().length }} pagos separados</small>
              </div>
              <b>Tú {{ mine() | currency: 'PEN' : 'S/ ' }}</b>
            </button>
          }
          <button
            role="radio"
            [attr.aria-checked]="mode() === 'EQUAL'"
            [class.active]="mode() === 'EQUAL'"
            (click)="mode.set('EQUAL')"
          >
            <span>➗</span>
            <div><strong>Partes iguales</strong><small>Dividir el total entre varios</small></div>
            <b>{{ total() / parts() | currency: 'PEN' : 'S/ ' }} c/u</b>
          </button>
        </div>

        @if (mode() === 'GUEST') {
          <div class="bill-detail">
            @for (payer of payers(); track payer.id) {
              <div [class.me]="payer.id === myGuestId()">
                <span>{{ payer.id === myGuestId() ? 'Tú' : payer.name }}</span
                ><strong>{{ payer.amount | currency: 'PEN' : 'S/ ' }}</strong>
              </div>
            }
            @if (shared() > 0) {
              <p>
                Incluye {{ shared() | currency: 'PEN' : 'S/ ' }} de platos para compartir, divididos
                entre todos.
              </p>
            }
          </div>
        }
        @if (mode() === 'EQUAL') {
          <div class="bill-parts">
            <button
              (click)="setParts(parts() - 1)"
              [disabled]="parts() <= 2"
              aria-label="Menos partes"
            >
              −
            </button>
            <strong>{{ parts() }} <small>personas</small></strong>
            <button
              (click)="setParts(parts() + 1)"
              [disabled]="parts() >= 30"
              aria-label="Más partes"
            >
              +
            </button>
          </div>
        }

        <p class="bill-label">¿Dejas propina para el equipo?</p>
        <div class="bill-tips">
          @for (option of tips; track option) {
            <button [class.active]="tip() === option" (click)="tip.set(option)">
              <strong>{{ option ? option + ' %' : 'No' }}</strong>
              @if (option) {
                <small>{{ (total() * option) / 100 | currency: 'PEN' : 'S/ ' }}</small>
              }
            </button>
          }
        </div>

        <div class="bill-total">
          <span>Total {{ tip() ? 'con propina' : 'de la mesa' }}</span>
          <strong>{{ total() * (1 + tip() / 100) | currency: 'PEN' : 'S/ ' }}</strong>
        </div>
        <button class="primary full bill-submit" [disabled]="busy()" (click)="submit()">
          {{ busy() ? 'Enviando…' : 'Pedir la cuenta' }}
        </button>
        <button class="bill-cancel" (click)="cancel.emit()">Seguir pidiendo</button>
      </section>
    </div>
  `,
  styleUrl: './diner-bill-sheet.component.scss',
})
export class DinerBillSheetComponent {
  readonly guests = input.required<GuestView[]>();
  readonly items = input.required<OrderItemView[]>();
  readonly myGuestId = input.required<string>();
  readonly busy = input(false);
  readonly confirm = output<BillChoice>();
  readonly cancel = output<void>();
  readonly tips = [0, 5, 10, 15];
  readonly mode = signal<BillChoice['splitMode']>('SINGLE');
  readonly tip = signal(0);
  readonly parts = signal(2);

  private readonly billable = computed(() =>
    this.items().filter(
      (item) =>
        item.orderStatus !== 'DRAFT' && item.status !== 'DRAFT' && item.status !== 'CANCELLED',
    ),
  );
  readonly total = computed(() =>
    this.billable().reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
  );
  /** Platos que pidió el mesero para la mesa: se reparten entre todos. */
  readonly shared = computed(() =>
    this.billable()
      .filter((item) => !item.guestId)
      .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
  );
  readonly payers = computed(() => {
    const people = this.guests().filter(
      (guest) => guest.active || this.billable().some((item) => item.guestId === guest.id),
    );
    const sharedEach = people.length ? this.shared() / people.length : 0;
    return people.map((guest) => ({
      id: guest.id,
      name: guest.displayName,
      amount:
        this.billable()
          .filter((item) => item.guestId === guest.id)
          .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0) + sharedEach,
    }));
  });
  readonly mine = computed(
    () => this.payers().find((payer) => payer.id === this.myGuestId())?.amount || 0,
  );

  constructor() {
    queueMicrotask(() =>
      this.parts.set(Math.max(2, this.guests().filter((guest) => guest.active).length)),
    );
  }

  setParts(value: number): void {
    this.parts.set(Math.max(2, Math.min(30, value)));
  }

  submit(): void {
    this.confirm.emit({
      splitMode: this.mode(),
      splitParts: this.mode() === 'EQUAL' ? this.parts() : null,
      tipPercent: this.tip(),
    });
  }
}
