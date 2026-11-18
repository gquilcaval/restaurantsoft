import { Component, computed, effect, inject, OnDestroy, OnInit, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { concatMap, from, last } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CounterOrderView, OrderItemView, OrderSnapshot } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeEvent, RealtimeService } from '../../core/realtime/realtime.service';
import { CheckoutDialogComponent } from '../../shared/ordering/checkout-dialog.component';
import { VoidItemDialogComponent, VoidRequest } from '../../shared/ordering/void-item-dialog.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { PrintService } from '../../shared/ui/print.service';
import { ToastService } from '../../shared/ui/toast.service';
import { SoundService } from '../../shared/ui/sound.service';
import { StaffOrderComponent } from '../waiter/staff-order.component';

type Filter = 'ALL' | 'IN_KITCHEN' | 'READY' | 'UNPAID';

/** Mostrador: pedidos para llevar y delivery, desde la toma del pedido hasta la entrega y el cobro. */
@Component({
  imports: [AppShellComponent, IconComponent, FormsModule, StaffOrderComponent, CheckoutDialogComponent, VoidItemDialogComponent],
  templateUrl: './counter.page.html',
  styleUrls: ['./counter.page.scss', './counter-board.scss', './counter-detail.scss', './counter-card.scss', './counter-detail-v2.scss', './counter-mobile.scss', './counter-sheet.scss', './counter-paybar.scss']
})
export class CounterPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  private readonly printer = inject(PrintService);
  private readonly sound = inject(SoundService);
  readonly realtime = inject(RealtimeService);
  private unwatch?: () => void;
  private stopResync?: () => void;
  private clockTimer?: number;
  private readonly checkout = viewChild(CheckoutDialogComponent);

  readonly orders = signal<CounterOrderView[]>([]);
  readonly loading = signal(true);
  readonly filter = signal<Filter>('ALL');
  readonly clock = signal(Date.now());
  readonly formOpen = signal(false);
  readonly saving = signal(false);
  readonly editing = signal<CounterOrderView | null>(null);
  readonly composer = signal<CounterOrderView | null>(null);
  readonly detail = signal<CounterOrderView | null>(null);
  readonly snapshot = signal<OrderSnapshot | null>(null);
  readonly paying = signal<CounterOrderView | null>(null);
  readonly voiding = signal<OrderItemView | null>(null);
  readonly voidBusy = signal(false);
  readonly delivering = signal(false);
  channel: 'TAKEAWAY' | 'DELIVERY' = 'TAKEAWAY';
  customerName = '';
  customerPhone = '';
  deliveryAddress = '';
  deliveryReference = '';
  deliveryFee = 0;

  readonly visible = computed(() => this.orders().filter(order => {
    const filter = this.filter();
    if (filter === 'IN_KITCHEN') return order.stage === 'IN_KITCHEN';
    if (filter === 'READY') return order.stage === 'READY';
    if (filter === 'UNPAID') return order.remaining > 0;
    return true;
  }));
  readonly count = (filter: Filter) => filter === 'ALL' ? this.orders().length
    : this.orders().filter(order => filter === 'UNPAID' ? order.remaining > 0 : order.stage === filter).length;
  readonly channelFilter = signal<'ALL' | 'TAKEAWAY' | 'DELIVERY'>('ALL');
  private readonly byChannel = computed(() => this.orders().filter(order => this.channelFilter() === 'ALL' || order.channel === this.channelFilter()));
  /** Tablero por etapa: lo que se prepara, lo que espera entrega y lo que falta cobrar o cerrar. */
  readonly columns = computed(() => {
    const list = this.byChannel();
    return [
      { key: 'KITCHEN' as const, title: 'En cocina', short: 'En cocina', hint: 'Recién tomados o preparándose', tone: 'blue', icon: 'chef', orders: list.filter(o => o.stage === 'NEW' || o.stage === 'IN_KITCHEN') },
      { key: 'READY' as const, title: 'Listos para entregar', short: 'Listos', hint: 'Entrégalos al cliente o al repartidor', tone: 'green', icon: 'bell', orders: list.filter(o => o.stage === 'READY') },
      { key: 'DONE' as const, title: 'Por cobrar', short: 'Por cobrar', hint: 'Ya lo recibió; cobra para cerrar', tone: 'violet', icon: 'money', orders: list.filter(o => o.stage === 'DELIVERED') },
    ].filter(column => column.key !== 'DONE' || column.orders.length > 0);
  });
  /** Resumen en vivo bajo el título: cuántos pedidos, cuántos listos y cuánto falta cobrar. */
  readonly headerSummary = computed(() => {
    const orders = this.orders();
    if (!orders.length) return 'Para llevar y delivery';
    const ready = orders.filter(order => order.stage === 'READY').length;
    const due = orders.reduce((sum, order) => sum + Number(order.remaining), 0);
    return [`${orders.length} ${orders.length === 1 ? 'pedido' : 'pedidos'}`, ready ? `${ready} ${ready === 1 ? 'listo' : 'listos'}` : '',
      due > 0 ? `S/ ${due.toLocaleString('en-US', { maximumFractionDigits: 0 })} por cobrar` : ''].filter(Boolean).join(' · ');
  });
  readonly dueTotal = computed(() => this.byChannel().reduce((sum, o) => sum + Number(o.remaining), 0));
  readonly channelCount = (channel: 'ALL' | 'TAKEAWAY' | 'DELIVERY') => channel === 'ALL' ? this.orders().length : this.orders().filter(o => o.channel === channel).length;
  /** Si la pestaña elegida ya no existe (p. ej. «Por cobrar» quedó vacía), se vuelve a «Listos» o «En cocina». */
  private readonly keepStageValid = effect(() => {
    const keys = this.columns().map(column => column.key);
    if (!keys.includes(this.stageTab())) untracked(() => this.stageTab.set(keys.includes('READY') && this.columns().find(c => c.key === 'READY')!.orders.length ? 'READY' : 'KITCHEN'));
  });
  /** Pasos del pedido, de la toma al cobro: se muestran en cada tarjeta y en el detalle. */
  readonly steps = ['Pedido', 'Cocina', 'Listo', 'Entregado', 'Pagado'];
  /** En celular se ve una etapa a la vez; arranca en «Listos» si hay algo esperando entrega. */
  readonly stageTab = signal<'KITCHEN' | 'READY' | 'DONE'>('KITCHEN');
  private stageTouched = false;
  readonly busyId = signal<string | null>(null);
  readonly readyItems = computed(() => (this.snapshot()?.items || []).filter(item => item.status === 'READY'));

  ngOnInit(): void {
    this.load();
    this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => this.load());
    const branch = this.auth.branchId();
    if (branch) this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, event => this.onEvent(event));
    this.clockTimer = window.setInterval(() => this.clock.set(Date.now()), 30000);
  }

  load(): void {
    this.api.get<CounterOrderView[]>('/staff/counter-orders').subscribe({
      next: value => {
        this.announceCompleted(value);
        this.autoClose(value);
        this.loading.set(false); this.orders.set(value); this.syncOpenViews(value);
        if (!this.stageTouched && value.some(order => order.stage === 'READY')) { this.stageTab.set('READY'); this.stageTouched = true; }
      },
      error: err => { this.loading.set(false); this.fail(err, 'No se pudieron cargar los pedidos.'); }
    });
  }

  newOrder(channel: 'TAKEAWAY' | 'DELIVERY' = 'TAKEAWAY'): void {
    this.editing.set(null);
    this.channel = channel; this.customerName = ''; this.customerPhone = ''; this.deliveryAddress = '';
    this.deliveryReference = ''; this.deliveryFee = channel === 'DELIVERY' ? 5 : 0;
    this.formOpen.set(true);
  }

  editOrder(order: CounterOrderView): void {
    this.editing.set(order);
    this.channel = order.channel; this.customerName = order.customerName; this.customerPhone = order.customerPhone || '';
    this.deliveryAddress = order.deliveryAddress || ''; this.deliveryReference = order.deliveryReference || '';
    this.deliveryFee = Number(order.deliveryFee);
    this.formOpen.set(true);
  }

  canSave(): boolean {
    if (!this.customerName.trim() || this.saving()) return false;
    return this.channel === 'TAKEAWAY' || (!!this.deliveryAddress.trim() && !!this.customerPhone.trim());
  }

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const body = { channel: this.channel, customerName: this.customerName, customerPhone: this.customerPhone || null,
      deliveryAddress: this.deliveryAddress || null, deliveryReference: this.deliveryReference || null,
      deliveryFee: this.channel === 'DELIVERY' ? this.deliveryFee || 0 : 0 };
    const editing = this.editing();
    const request = editing ? this.api.patch<CounterOrderView>(`/staff/counter-orders/${editing.sessionId}`, body)
      : this.api.post<CounterOrderView>('/staff/counter-orders', body);
    request.subscribe({
      next: order => {
        this.saving.set(false); this.formOpen.set(false); this.load();
        // Un pedido nuevo pasa directo a la carta para tomarlo sin pasos extra.
        if (!editing) this.composer.set(order);
      },
      error: err => { this.saving.set(false); this.fail(err, 'No se pudo guardar el pedido.'); }
    });
  }

  onRoundSent(order: CounterOrderView): void {
    this.composer.set(null);
    this.toast.show('Pedido enviado', `${order.label} ya está en cocina.`, 'success', '✓');
    this.printer.print('COMANDA', `${order.label} · ${order.customerName}`);
    this.load();
    if (this.detail()?.sessionId === order.sessionId) this.loadSnapshot(order.sessionId);
  }

  // ——— Ficha del pedido como hoja en celular: arrastrar hacia abajo cierra, hacia arriba la agranda ———
  readonly sheetY = signal(0);
  readonly sheetHeight = signal(0);
  readonly sheetExpanded = signal(false);
  readonly sheetDragging = signal(false);
  private sheetDrag: { pointerId: number; y: number; time: number; base: number; full: number } | null = null;
  sheetStart(event: PointerEvent): void {
    if (!matchMedia('(max-width: 860px)').matches || (event.target as Element).closest('button, a')) return;
    const sheet = (event.currentTarget as HTMLElement).closest('.cn-detail') as HTMLElement;
    this.sheetDrag = { pointerId: event.pointerId, y: event.clientY, time: performance.now(), base: sheet.getBoundingClientRect().height, full: window.innerHeight };
    this.sheetDragging.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  sheetMove(event: PointerEvent): void {
    const drag = this.sheetDrag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const dy = event.clientY - drag.y;
    if (dy > 0 && !this.sheetExpanded()) { this.sheetHeight.set(0); this.sheetY.set(dy); return; }
    const height = drag.base - dy;
    this.sheetY.set(0);
    this.sheetHeight.set(height > drag.full ? drag.full + (height - drag.full) * 0.2 : Math.max(160, height));
  }
  sheetEnd(event: PointerEvent): void {
    const drag = this.sheetDrag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.sheetDrag = null; this.sheetDragging.set(false);
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
    const dy = event.clientY - drag.y;
    const speed = dy / Math.max(1, performance.now() - drag.time);
    const height = this.sheetHeight() || drag.base - Math.max(0, dy);
    if (!this.sheetExpanded()) {
      if (dy > Math.min(140, drag.base * 0.3) || speed > 0.5) { this.closeDetail(true); return; }
      if (dy < -50 || speed < -0.4) this.sheetExpanded.set(true);
    } else if (dy > 0) {
      if (height < drag.full * 0.3) { this.closeDetail(true); return; }
      if (dy > 90 || speed > 0.4) this.sheetExpanded.set(false);
    }
    this.sheetY.set(0); this.sheetHeight.set(0);
  }
  closeDetail(animated = false): void {
    if (!animated) { this.detail.set(null); this.sheetExpanded.set(false); this.sheetY.set(0); this.sheetHeight.set(0); return; }
    navigator.vibrate?.(6);
    this.sheetY.set(window.innerHeight); this.sheetHeight.set(0);
    window.setTimeout(() => { this.detail.set(null); this.sheetY.set(0); this.sheetExpanded.set(false); }, 260);
  }

  openDetail(order: CounterOrderView): void { this.sheetExpanded.set(false); this.sheetY.set(0); this.sheetHeight.set(0); this.detail.set(order); this.snapshot.set(null); this.loadSnapshot(order.sessionId); }

  openCheckout(order: CounterOrderView): void {
    this.api.get<OrderSnapshot>(`/staff/sessions/${order.sessionId}/orders`).subscribe({
      next: value => { this.snapshot.set(value); this.paying.set(order); },
      error: err => this.fail(err, 'No se pudo abrir la cuenta.')
    });
  }

  onSessionClosed(order: CounterOrderView): void {
    this.paying.set(null); this.detail.set(null);
    this.toast.show('Pedido cerrado', `${order.label} quedó entregado y pagado.`, 'success', '✓');
    this.load();
  }

  stepIndex(order: CounterOrderView): number {
    if (order.stage === 'NEW') return 0;
    if (order.stage === 'IN_KITCHEN') return 1;
    if (order.stage === 'READY') return 2;
    return order.remaining > 0 ? 3 : 4;
  }
  /** Lo siguiente que hay que hacer con el pedido, como botón directo en la tarjeta y en el detalle. */
  quick(order: CounterOrderView): { label: string; icon: string; kind: 'add' | 'deliver' | 'pay' | 'close' } | null {
    if (order.stage === 'NEW') return { label: 'Agregar productos', icon: 'plus', kind: 'add' };
    if (order.stage === 'READY') return { label: order.channel === 'DELIVERY' ? 'Entregar al repartidor' : 'Entregar al cliente', icon: 'check', kind: 'deliver' };
    if (order.stage === 'DELIVERED') return order.remaining > 0 ? { label: `Cobrar ${this.money(order.remaining)}`, icon: 'money', kind: 'pay' } : { label: 'Cerrar pedido', icon: 'check', kind: 'close' };
    return null;
  }
  runQuick(order: CounterOrderView): void {
    navigator.vibrate?.(10);
    const action = this.quick(order);
    if (!action) return;
    if (action.kind === 'add') this.composer.set(order);
    else if (action.kind === 'deliver') this.deliverOrder(order);
    else this.openCheckout(order);
  }
  /** Marca como entregado todo lo que cocina terminó, sin tener que abrir el pedido. */
  deliverOrder(order: CounterOrderView): void {
    if (this.busyId()) return;
    this.busyId.set(order.sessionId);
    this.api.get<OrderSnapshot>(`/staff/sessions/${order.sessionId}/orders`).pipe(
      concatMap(snapshot => {
        const ready = snapshot.items.filter(item => item.status === 'READY');
        return ready.length ? from(ready).pipe(concatMap(item => this.api.patch<OrderSnapshot>(`/staff/order-items/${item.id}/status`, { status: 'DELIVERED' })), last()) : from([snapshot]);
      })
    ).subscribe({
      next: value => {
        this.busyId.set(null);
        if (this.detail()?.sessionId === order.sessionId) this.snapshot.set(value);
        this.toast.show('Entregado', `${order.label} entregado ${order.channel === 'DELIVERY' ? 'al repartidor' : 'al cliente'}.`, 'success', '✓');
        this.load();
      },
      error: err => { this.busyId.set(null); this.fail(err, 'No se pudo registrar la entrega.'); }
    });
  }
  whatsapp(order: CounterOrderView): string {
    const digits = (order.customerPhone || '').replace(/\D/g, '');
    const phone = digits.length === 9 ? '51' + digits : digits;
    const text = order.stage === 'READY' ? `Hola ${order.customerName}, tu pedido #${order.orderNumber} ya está listo.` : `Hola ${order.customerName}, te escribimos por tu pedido #${order.orderNumber}.`;
    return `https://wa.me/${phone}?text=${encodeURIComponent(text)}`;
  }
  mapLink(order: CounterOrderView): string { return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(order.deliveryAddress || '')}`; }

  /** Entrega al cliente o al repartidor todo lo que cocina ya marcó como listo. */
  deliverReady(): void {
    const items = this.readyItems(); const order = this.detail();
    if (!items.length || !order || this.delivering()) return;
    this.delivering.set(true);
    from(items).pipe(concatMap(item => this.api.patch<OrderSnapshot>(`/staff/order-items/${item.id}/status`, { status: 'DELIVERED' })), last()).subscribe({
      next: value => { this.delivering.set(false); this.snapshot.set(value); this.toast.show('Entregado', `${order.label} entregado ${order.channel === 'DELIVERY' ? 'al repartidor' : 'al cliente'}.`, 'success', '✓'); this.load(); },
      error: err => { this.delivering.set(false); this.fail(err, 'No se pudo registrar la entrega.'); this.loadSnapshot(order.sessionId); }
    });
  }

  canVoid(item: OrderItemView): boolean {
    const manager = ['BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role());
    return item.orderStatus !== 'DRAFT' && item.status !== 'CANCELLED' && (item.status === 'SUBMITTED' || manager);
  }

  confirmVoid(request: VoidRequest): void {
    const item = this.voiding(); const order = this.detail();
    if (!item || !order || this.voidBusy()) return;
    this.voidBusy.set(true);
    this.api.post<OrderSnapshot>(`/staff/order-items/${item.id}/void`, request).subscribe({
      next: value => { this.voidBusy.set(false); this.voiding.set(null); this.snapshot.set(value); this.toast.show('Producto anulado', `${request.quantity}× ${item.name}`, 'success', '✓'); this.load(); },
      error: err => { this.voidBusy.set(false); this.fail(err, 'No se pudo anular el producto.'); }
    });
  }

  async cancelOrder(order: CounterOrderView): Promise<void> {
    const accepted = await this.dialog.confirm({ title: `Cancelar ${order.label}`, message: 'El pedido se cerrará sin cobro. Si ya tiene productos enviados a cocina, primero debes anularlos.', confirmLabel: 'Cancelar pedido', tone: 'danger', icon: '!' });
    if (!accepted) return;
    this.api.post(`/staff/sessions/${order.sessionId}/cancel`, { reason: 'Pedido de mostrador cancelado' }).subscribe({
      next: () => { this.detail.set(null); this.toast.show('Pedido cancelado', order.label, 'info', '×'); this.load(); },
      error: err => this.fail(err, 'No se pudo cancelar el pedido.')
    });
  }

  /** Pedidos entregados y ya pagados: se cierran solos (no hace falta tocar «Cerrar pedido»). */
  private readonly closing = new Set<string>();
  private autoClose(orders: CounterOrderView[]): void {
    for (const order of orders) {
      if (order.stage !== 'DELIVERED' || Number(order.remaining) > 0 || Number(order.total) <= 0 || this.closing.has(order.sessionId) || this.paying()) continue;
      this.closing.add(order.sessionId);
      this.api.post(`/staff/sessions/${order.sessionId}/payments/close`, {}).subscribe({
        next: () => {
          this.toast.show(`Pedido #${order.orderNumber} cerrado`, `${order.customerName}: entregado y pagado.`, 'success', '✓');
          if (this.detail()?.sessionId === order.sessionId) this.detail.set(null);
          this.load();
        },
        error: () => this.closing.delete(order.sessionId),
      });
    }
  }

  /** Etapa anterior de cada pedido: el aviso suena solo cuando uno que estaba en cocina pasa a listo. */
  private lastStage: Map<string, string> | null = null;
  /** Cuando cocina termina el último plato de un pedido, suena «pedido completo» y aparece el aviso. */
  private announceCompleted(next: CounterOrderView[]): void {
    if (this.lastStage) {
      for (const order of next) {
        const before = this.lastStage.get(order.sessionId);
        if (order.stage !== 'READY' || !before || before === 'READY' || before === 'DELIVERED') continue;
        this.sound.play('complete');
        navigator.vibrate?.([90, 60, 90]);
        this.toast.show(`Pedido #${order.orderNumber} completo`, `${order.customerName} · entrégalo ${order.channel === 'DELIVERY' ? 'al repartidor' : 'al cliente'}.`, 'success', '✓', 8000);
        if (!this.stageTouched) this.stageTab.set('READY');
      }
    }
    this.lastStage = new Map(next.map(order => [order.sessionId, order.stage]));
  }

  /** Minutos desde que se abrió: sirve para marcar los pedidos que se demoran. */
  minutes(value: string): number { return Math.max(0, Math.floor((this.clock() - new Date(value).getTime()) / 60000)); }
  money(value: number | null | undefined): string { return `S/ ${Number(value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`; }
  elapsed(value: string): string {
    const minutes = Math.max(0, Math.floor((this.clock() - new Date(value).getTime()) / 60000));
    return minutes < 1 ? 'Ahora' : minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
  }
  stageLabel(stage: string): string { return ({ NEW: 'Sin productos', IN_KITCHEN: 'En cocina', READY: 'Listo para entregar', DELIVERED: 'Entregado' } as Record<string, string>)[stage] || stage; }
  stageClass(stage: string): string { return ({ NEW: 'neutral', IN_KITCHEN: 'preparing', READY: 'ready', DELIVERED: 'delivered' } as Record<string, string>)[stage] || 'neutral'; }
  statusLabel(status: string): string { return ({ SUBMITTED: 'En cocina', PREPARING: 'Preparando', READY: 'Listo', DELIVERED: 'Entregado', CANCELLED: 'Anulado' } as Record<string, string>)[status] || status; }
  statusClass(status: string): string { return ({ SUBMITTED: 'neutral', PREPARING: 'preparing', READY: 'ready', DELIVERED: 'delivered', CANCELLED: 'review' } as Record<string, string>)[status] || 'neutral'; }

  private loadSnapshot(sessionId: string): void {
    this.api.get<OrderSnapshot>(`/staff/sessions/${sessionId}/orders`).subscribe({
      next: value => { if (this.detail()?.sessionId === sessionId || this.paying()?.sessionId === sessionId) this.snapshot.set(value); },
      error: () => undefined
    });
  }

  /** Mantiene actualizados el detalle y el cobro abiertos cuando cambia el pedido en otra pantalla. */
  private syncOpenViews(orders: CounterOrderView[]): void {
    const detail = this.detail();
    if (detail) {
      const fresh = orders.find(order => order.sessionId === detail.sessionId);
      if (fresh) { this.detail.set(fresh); this.loadSnapshot(fresh.sessionId); } else if (!this.paying()) this.detail.set(null);
    }
  }

  private onEvent(event: RealtimeEvent): void {
    if (event.type === 'PAYMENT_REGISTERED' || event.type === 'DISCOUNT_UPDATED') this.checkout()?.reload();
    this.load();
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.toast.show('No se pudo completar', err.error?.message || fallback, 'error', '!');
  }

  ngOnDestroy(): void { this.stopResync?.(); this.unwatch?.(); if (this.clockTimer) window.clearInterval(this.clockTimer); }
}
