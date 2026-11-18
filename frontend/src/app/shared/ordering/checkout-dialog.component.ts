import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CustomerView, GuestShare, LoyaltyState, OrderChannel, OrderSnapshot, PaymentSummary } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { DialogService } from '../ui/dialog.service';
import { PrintService } from '../ui/print.service';
import { ToastService } from '../ui/toast.service';
import { SoundService } from '../ui/sound.service';

/**
 * Cobro de una atención (mesa, para llevar o delivery): congela el consumo, aplica descuentos,
 * registra pagos con vuelto y propina, divide la cuenta y cierra la atención.
 */
@Component({
  selector: 'app-checkout-dialog',
  imports: [CurrencyPipe, FormsModule],
  templateUrl: './checkout-dialog.component.html',
  styleUrls: ['./checkout-dialog.scss', './checkout-dialog-pay.scss', './checkout-dialog-more.scss', './checkout-dialog-customer.scss'],
})
export class CheckoutDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly printer = inject(PrintService);
  private readonly toast = inject(ToastService);
  private readonly sound = inject(SoundService);
  readonly sessionId = input.required<string>();
  readonly label = input.required<string>();
  readonly order = input<OrderSnapshot | null>(null);
  readonly channel = input<OrderChannel>('DINE_IN');
  readonly closed = output<void>();
  readonly sessionClosed = output<string>();
  /** Pagado por adelantado (aún falta entregar): quien abrió el cobro cierra sus ventanas. */
  readonly paid = output<void>();
  readonly changed = output<PaymentSummary>();

  readonly payment = signal<PaymentSummary | null>(null);
  readonly busy = signal(false);
  readonly discountOpen = signal(false);
  readonly payingGuest = signal<GuestShare | null>(null);
  readonly loyalty = signal<LoyaltyState | null>(null);
  readonly customerResults = signal<CustomerView[]>([]);
  readonly customerFormOpen = signal(false);
  customerQuery = '';
  newCustomerName = '';
  newCustomerPhone = '';
  redeemPoints = 0;
  private customerSearchTimer?: number;
  discountKind: 'PERCENT' | 'AMOUNT' = 'PERCENT';
  discountValue = 10;
  discountReason = '';
  paymentMethod = 'CASH';
  readonly methods = [
    { id: 'CASH', label: 'Efectivo', icon: '💵' }, { id: 'CARD', label: 'Tarjeta', icon: '💳' },
    { id: 'YAPE', label: 'Yape', icon: 'Y' }, { id: 'PLIN', label: 'Plin', icon: 'P' },
  ];
  // ——— Gesto (celular): arrastrar el encabezado hacia abajo cierra el cobro ———
  readonly dragY = signal(0);
  readonly dragging = signal(false);
  private drag: { pointerId: number; y: number; time: number } | null = null;
  dragStart(event: PointerEvent): void {
    if (!matchMedia('(max-width: 620px)').matches || (event.target as Element).closest('button')) return;
    this.drag = { pointerId: event.pointerId, y: event.clientY, time: performance.now() };
    this.dragging.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  dragMove(event: PointerEvent): void {
    if (!this.drag || this.drag.pointerId !== event.pointerId) return;
    const dy = event.clientY - this.drag.y;
    this.dragY.set(dy > 0 ? dy : dy * 0.15);
  }
  dragEnd(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.drag = null; this.dragging.set(false);
    const dy = event.clientY - drag.y;
    if (dy > 140 || dy / Math.max(1, performance.now() - drag.time) > 0.5) {
      navigator.vibrate?.(6);
      this.dragY.set(window.innerHeight);
      window.setTimeout(() => this.closed.emit(), 240);
      return;
    }
    this.dragY.set(0);
  }
  paymentAmount = 0;
  paymentTip = 0;
  paymentReceived: number | null = null;
  paymentReference = '';
  splitParts = 2;

  readonly billInProgress = computed(() => this.payment()?.bill?.status === 'IN_PROGRESS');
  /** Platos enviados que siguen en la cuenta; los anulados quedan fuera. */
  private readonly billable = computed(() => (this.order()?.items || []).filter(item => item.orderStatus !== 'DRAFT' && item.status !== 'CANCELLED'));
  readonly pendingDelivery = computed(() => this.billable().filter(item => item.status !== 'DELIVERED').reduce((sum, item) => sum + item.quantity, 0));
  /** En mesa se cobra al terminar el servicio; para llevar y delivery se puede cobrar por adelantado. */
  readonly canPay = computed(() => this.channel() !== 'DINE_IN' || (this.billable().length > 0 && this.pendingDelivery() === 0));
  readonly showSplit = computed(() => (this.payment()?.shares.length || 0) > 1);

  ngOnInit(): void { this.reload(); }

  reload(): void {
    this.api.get<PaymentSummary>(`/staff/sessions/${this.sessionId()}/payments`).subscribe({
      next: value => { this.accept(value, true); this.loadLoyalty(); },
      error: err => this.fail(err, 'No se pudo consultar la cuenta.')
    });
  }

  private loadLoyalty(): void {
    this.api.get<LoyaltyState>(`/staff/sessions/${this.sessionId()}/loyalty`).subscribe({
      next: value => { this.loyalty.set(value); this.redeemPoints = this.maxRedeemable(); },
      error: () => undefined
    });
  }

  /** Puntos que realmente se pueden usar: los del cliente, sin exceder el consumo de la cuenta. */
  maxRedeemable(): number {
    const state = this.loyalty(); const summary = this.payment();
    if (!state?.customer || !summary) return 0;
    const byTotal = Math.floor(Number(summary.subtotal) / Number(state.program.pointValue));
    return Math.max(0, Math.min(state.customer.points, byTotal));
  }

  canRedeem(): boolean {
    const state = this.loyalty(); const summary = this.payment();
    return !!state?.customer && state.program.enabled && !summary?.discount && summary?.paid === 0
      && this.maxRedeemable() >= state.program.minRedeem;
  }

  searchCustomers(): void {
    window.clearTimeout(this.customerSearchTimer);
    const query = this.customerQuery.trim();
    if (query.length < 2) { this.customerResults.set([]); return; }
    this.customerSearchTimer = window.setTimeout(() => this.api.get<CustomerView[]>(`/staff/customers?q=${encodeURIComponent(query)}`)
      .subscribe(value => this.customerResults.set(value.slice(0, 6))), 250);
  }

  linkCustomer(customer: CustomerView | null): void {
    this.run(this.api.put<LoyaltyState>(`/staff/sessions/${this.sessionId()}/customer`, { customerId: customer?.id || null }), value => {
      this.loyalty.set(value); this.redeemPoints = this.maxRedeemable();
      this.customerQuery = ''; this.customerResults.set([]); this.customerFormOpen.set(false);
    });
  }

  createCustomer(): void {
    if (!this.newCustomerName.trim() || !this.newCustomerPhone.trim()) return;
    this.run(this.api.post<CustomerView>('/staff/customers', { fullName: this.newCustomerName, phone: this.newCustomerPhone }), customer => {
      this.newCustomerName = ''; this.newCustomerPhone = '';
      this.linkCustomer(customer);
    });
  }

  redeem(): void {
    this.run(this.api.post<LoyaltyState>(`/staff/sessions/${this.sessionId()}/loyalty/redeem`, { points: this.redeemPoints }), value => {
      this.loyalty.set(value);
      this.toast.show('Puntos canjeados', `${value.redeemedPoints} puntos aplicados como descuento.`, 'success', '★');
      this.reload();
    });
  }

  cancelRedeem(): void {
    this.run(this.api.delete<LoyaltyState>(`/staff/sessions/${this.sessionId()}/loyalty/redeem`), value => { this.loyalty.set(value); this.reload(); });
  }

  isManager(): boolean { return ['BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role()); }

  begin(): void {
    this.run(this.api.post(`/staff/sessions/${this.sessionId()}/payments/begin`, {}), () => {
      this.toast.show('Cuenta en preparación', 'El consumo quedó congelado para cobrar.', 'success', '✓');
      this.reload();
    });
  }

  async cancelBill(): Promise<void> {
    const bill = this.payment()?.bill;
    if (!bill) return;
    const accepted = await this.dialog.confirm({ title: 'Cancelar preparación de cuenta', message: 'El consumo volverá a estar disponible para nuevos pedidos. Solo es posible antes de registrar pagos.', confirmLabel: 'Reabrir consumo', tone: 'warning', icon: '↺' });
    if (!accepted) return;
    this.run(this.api.patch(`/staff/service-requests/${bill.requestId}`, { status: 'RESOLVED' }), () => {
      this.toast.show('Consumo reabierto', 'Ya se pueden agregar pedidos nuevamente.', 'info', '↺');
      this.reload();
    });
  }

  applyDiscount(): void {
    this.run(this.api.put<PaymentSummary>(`/staff/sessions/${this.sessionId()}/payments/discount`, { kind: this.discountKind, value: this.discountValue, reason: this.discountReason.trim() }), value => {
      this.accept(value, true); this.discountOpen.set(false); this.discountReason = '';
    });
  }

  removeDiscount(): void {
    this.run(this.api.put<PaymentSummary>(`/staff/sessions/${this.sessionId()}/payments/discount`, { kind: null }), value => this.accept(value, true));
  }

  /** Prepara el formulario con lo que le falta pagar a un comensal. */
  chargeShare(share: GuestShare): void {
    this.payingGuest.set(share.guestId ? share : null);
    this.paymentAmount = Math.min(Number(share.pending), Number(this.payment()?.remaining || 0));
    this.suggestTip();
  }

  /** Propone la propina que eligió la mesa desde el celular, proporcional al monto de este pago. */
  private suggestTip(): void {
    const percent = this.payment()?.preference?.tipPercent || 0;
    this.paymentTip = percent ? Math.round(Number(this.paymentAmount || 0) * percent) / 100 : 0;
  }

  /** Atajo según lo que pidió la mesa: una cuenta, la siguiente parte igual o el próximo comensal. */
  applyPreference(): void {
    const summary = this.payment();
    const preference = summary?.preference;
    if (!summary || !preference) return;
    if (preference.mode === 'EQUAL') { this.splitParts = preference.parts || this.splitParts; this.splitEqually(); return; }
    if (preference.mode === 'GUEST') {
      const next = summary.shares.find(share => Number(share.pending) > 0);
      if (next) { this.chargeShare(next); return; }
    }
    this.payingGuest.set(null);
    this.paymentAmount = Number(summary.remaining);
    this.suggestTip();
  }

  /** Divide el saldo pendiente en partes iguales; la última parte absorbe los céntimos sobrantes. */
  splitEqually(): void {
    const remaining = Number(this.payment()?.remaining || 0);
    const parts = Math.max(2, Math.min(20, Math.round(this.splitParts || 2)));
    this.payingGuest.set(null);
    this.paymentAmount = Math.floor((remaining / parts) * 100) / 100;
    this.suggestTip();
  }

  amountDue(): number { return Math.round((Number(this.paymentAmount || 0) + Number(this.paymentTip || 0)) * 100) / 100; }

  /** Exacto y billetes peruanos que alcanzan para pagar, para registrar el efectivo con un toque. */
  /** Mitad del saldo, redondeada a céntimos. */
  half(value: number): number { return Math.round(Number(value || 0) * 50) / 100; }
  /** Propina como porcentaje del monto que se está cobrando. */
  tipFor(percent: number): number { return Math.round(Number(this.paymentAmount || 0) * percent) / 100; }
  cashOptions(): number[] {
    const due = this.amountDue();
    if (due <= 0) return [];
    return [...new Set([due, ...[10, 20, 50, 100, 200].filter(bill => bill > due)])].slice(0, 5);
  }

  canSubmitPayment(): boolean {
    const remaining = Number(this.payment()?.remaining || 0);
    if (this.paymentAmount <= 0 || this.paymentAmount > remaining || this.paymentTip < 0 || this.busy()) return false;
    return this.paymentMethod !== 'CASH' || this.paymentReceived === null || !(this.paymentReceived > 0) || this.paymentReceived >= this.amountDue();
  }

  /**
   * Un solo toque: si la cuenta aún no estaba cerrada para cobrar, se cierra y enseguida se registra el pago
   * (antes eran dos pasos: «Empezar a cobrar» y luego «Registrar»).
   */
  payNow(): void {
    if (this.billInProgress()) { this.pay(); return; }
    if (!this.canSubmitPayment() || this.busy()) return;
    this.busy.set(true);
    this.api.post(`/staff/sessions/${this.sessionId()}/payments/begin`, {}).subscribe({
      next: () => { this.busy.set(false); this.pay(); },
      error: err => { this.busy.set(false); this.fail(err, 'No se pudo iniciar el cobro.'); this.reload(); },
    });
  }

  pay(): void {
    if (!this.canSubmitPayment()) return;
    const received = this.paymentMethod === 'CASH' && this.paymentReceived !== null && this.paymentReceived > 0 ? this.paymentReceived : null;
    const change = received !== null ? received - this.amountDue() : 0;
    const body = { method: this.paymentMethod, amount: this.paymentAmount, reference: this.paymentReference || null, tip: this.paymentTip || 0, received, guestId: this.payingGuest()?.guestId || null };
    this.run(this.api.post<PaymentSummary>(`/staff/sessions/${this.sessionId()}/payments`, body), value => {
      const changeText = change > 0 ? ` Entrega S/ ${change.toFixed(2)} de vuelto.` : '';
      this.sound.haptic();
      this.toast.show(value.remaining === 0 ? 'Pago completo' : 'Pago registrado', value.remaining === 0 ? `${changeText} Ya puedes cerrar.`.trim() : `${changeText} Saldo: S/ ${Number(value.remaining).toFixed(2)}.`.trim(), 'success', '✓');
      this.paymentReference = ''; this.paymentTip = 0; this.paymentReceived = null; this.payingGuest.set(null);
      this.accept(value, true);
      // Cuenta pagada completa: se cierran todas las ventanas. Si ya no queda nada por entregar, la atención se
      // cierra sola (la mesa queda libre); si se pagó por adelantado, el pedido sigue hasta entregarlo.
      if (value.remaining === 0) {
        if (this.pendingDelivery() === 0) {
          this.api.post(`/staff/sessions/${this.sessionId()}/payments/close`, {}).subscribe({
            next: () => { this.printer.print('COMPROBANTE', `${this.label()} · cuenta pagada`); this.sessionClosed.emit(this.sessionId()); },
            error: () => this.reload(),
          });
        } else {
          window.setTimeout(() => this.paid.emit(), 700);
        }
      }
    });
  }

  async closeSession(): Promise<void> {
    const accepted = await this.dialog.confirm({ title: 'Cerrar atención', message: `La cuenta de ${this.label()} está pagada. Al cerrar, la atención termina${this.channel() === 'DINE_IN' ? ' y la mesa queda libre' : ''}.`, confirmLabel: 'Cerrar atención', tone: 'success', icon: '✓' });
    if (!accepted) return;
    this.run(this.api.post(`/staff/sessions/${this.sessionId()}/payments/close`, {}), () => {
      this.printer.print('COMPROBANTE', `${this.label()} · cuenta pagada`);
      this.sessionClosed.emit(this.sessionId());
    });
  }

  printPrebill(): void {
    const summary = this.payment();
    if (summary) this.printer.print('PRECUENTA', `${this.label()} · Total S/ ${Number(summary.total).toFixed(2)}`);
  }

  methodLabel(method: string): string { return ({ CASH: 'Efectivo', CARD: 'Tarjeta', YAPE: 'Yape', PLIN: 'Plin' } as Record<string, string>)[method] || method; }
  guestLabel(guestId: string | null): string { return guestId ? this.payment()?.shares.find(share => share.guestId === guestId)?.name || 'Comensal' : ''; }

  private accept(value: PaymentSummary, resetAmount: boolean): void {
    const first = !this.payment();
    this.payment.set(value);
    if (first && value.preference?.mode === 'EQUAL' && value.preference.parts) this.splitParts = value.preference.parts;
    if (resetAmount) { this.paymentAmount = Number(value.remaining); this.suggestTip(); }
    this.changed.emit(value);
  }

  private run<T>(request: Observable<T>, next: (value: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    request.subscribe({
      next: value => { this.busy.set(false); next(value); },
      error: err => { this.busy.set(false); this.fail(err, 'No se pudo completar la operación.'); this.reload(); }
    });
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.toast.show('No se pudo completar', err.error?.message || fallback, 'error', '!');
  }
}
