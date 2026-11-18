import { CurrencyPipe } from '@angular/common';
import {
  Component,
  computed,
  effect,
  inject,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import QRCode from 'qrcode';
import { concatMap, finalize, from, last } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { OrderItemView, OrderSnapshot, ReservationView, ServiceRequestView, TableTransferView, TableView, ZoneView, PrintJobView } from '../../core/api/models';
import {
  VoidItemDialogComponent,
  VoidRequest,
} from '../../shared/ordering/void-item-dialog.component';
import { PrintService } from '../../shared/ui/print.service';
import { ToastService } from '../../shared/ui/toast.service';
import { StaffOrderComponent } from './staff-order.component';
import { NotificationTone, WaiterNotificationsComponent } from './waiter-notifications.component';
import { WaiterNavService } from '../../shared/ui/waiter-nav.service';
import { CheckoutDialogComponent } from '../../shared/ordering/checkout-dialog.component';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeEvent, RealtimeService } from '../../core/realtime/realtime.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { SoundService } from '../../shared/ui/sound.service';
import { GoalBarComponent } from '../../shared/ui/goal-bar.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { TeamBoardComponent } from '../../shared/team/team-board.component';
import { FloorPlan } from '../../shared/floor/floor.models';
import { FloorTableState, FloorViewComponent, TableMove } from './floor-view.component';
import { firstName, initialsOf, WaiterOption, waiterHue } from './served-by';

/** Aviso de cambio del mesero a cargo de una mesa. */
interface WaiterChange {
  sessionId: string;
  tableLabel: string;
  waiterId: string | null;
  waiterName: string | null;
  previousWaiterId: string | null;
}

interface OpsAlert {
  key: string;
  tone: 'warning' | 'error' | 'info';
  icon: string;
  title: string;
  body: string;
}

interface ReadyTableAlert {
  table: TableView;
  items: OrderItemView[];
  quantity: number;
  names: string;
}

/** Con la cuenta en preparación el consumo está congelado: no se agregan ni anulan productos. */
const activeBillFrozen = (request: ServiceRequestView | null): boolean =>
  request?.status === 'IN_PROGRESS';

/** Centro operativo real del mesero: supervisa mesas, entrega platos, atiende alertas y registra pagos. */
@Component({
  imports: [
    WaiterNotificationsComponent,
    AppShellComponent,
    CurrencyPipe,
    FormsModule,
    StaffOrderComponent,
    VoidItemDialogComponent,
    CheckoutDialogComponent,
    FloorViewComponent,
    GoalBarComponent,
    TeamBoardComponent,
    IconComponent,
  ],
  styleUrls: [
    './waiter-tables.scss',
    './waiter-queue.scss',
    './waiter.page.scss',
    './waiter-zones.scss',
    './waiter-board.scss',
    './waiter-board-cards.scss',
    './waiter-glass.scss',
    './waiter-detail-head.scss',
    './waiter-queue-items.scss',
    './waiter-mobile.scss',
    './waiter-mobile-sheet.scss',
    './waiter-mobile-cards.scss',
    './waiter-zone-picker.scss',
    './waiter-history.scss',
    './waiter-search.scss',
    './waiter-served.scss',
    './waiter-print.scss',
  ],
  templateUrl: './waiter.page.html',
})
export class WaiterPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  private readonly printer = inject(PrintService);
  private readonly sound = inject(SoundService);
  readonly realtime = inject(RealtimeService);
  private unwatch?: () => void;
  private stopResync?: () => void;
  readonly tables = signal<TableView[]>([]);
  readonly tablesLoading = signal(true);
  readonly tablesError = signal(false);
  readonly tableSearch = signal('');
  /** Cada segmento refleja platos presentes en ese estado, no un avance ficticio de toda la mesa. */
  readonly orderStages = [
    { status: 'SUBMITTED', label: 'Cola' },
    { status: 'PREPARING', label: 'Prep.' },
    { status: 'READY', label: 'Listo' },
    { status: 'DELIVERED', label: 'Serv.' },
  ];
  readonly requests = signal<ServiceRequestView[]>([]);
  /** Próxima reserva activa de cada mesa (dentro de las siguientes 3 horas). */
  readonly reservations = signal<ReservationView[]>([]);
  readonly transfers = signal<TableTransferView[]>([]);
  readonly selected = signal<TableView | null>(null);
  readonly order = signal<OrderSnapshot | null>(null);
  readonly checkoutOpen = signal(false);
  private readonly checkout = viewChild(CheckoutDialogComponent);
  readonly detailDragY = signal(0);
  readonly detailDragging = signal(false);
  readonly detailClosing = signal(false);
  readonly servingItemIds = signal<Set<string>>(new Set());
  private detailDragStartY: number | null = null;
  readonly tableFilter = signal<'ALL' | 'OPEN' | 'READY' | 'FREE'>('ALL');
  readonly zones = signal<ZoneView[]>([]);
  /** Zonas asignadas al mesero conectado; si tiene, por defecto ve y recibe avisos solo de ellas. */
  readonly myZoneIds = signal<string[]>([]);
  /** Mesas asignadas a este mesero en el estudio de planos. */
  readonly myTableIds = signal<string[]>([]);
  readonly zoneFilter = signal<string>('ALL');
  private zoneFilterTouched = false;
  /** Mesas de la zona elegida (todas, mis zonas o una zona específica). */
  readonly zonedTables = computed(() => this.tables().filter((table) => this.inZoneFilter(table)));
  /** Lo urgente primero: «sigue esperando», luego «algo salió mal», luego por antigüedad. */
  readonly zoneRequests = computed(() =>
    this.requests().filter((request) => {
      const table = this.tables().find((item) => item.sessionId === request.sessionId);
      return !table || this.inZoneFilter(table) || (request.escalation || 0) >= 2;
    }).sort((a, b) => (b.escalation || 0) - (a.escalation || 0)
      || Number(b.reason === 'PROBLEM') - Number(a.reason === 'PROBLEM')
      || a.requestedAt.localeCompare(b.requestedAt)),
  );
  readonly reasonLabels: Record<string, string> = { CUTLERY: 'Cubiertos o servilletas', DRINK: 'Quiere pedir algo', QUESTION: 'Tiene una consulta', PROBLEM: 'Algo salió mal' };
  /** Texto de la fila del llamado según su estado. */
  callLine(request: ServiceRequestView): string {
    if ((request.escalation || 0) >= 2 && request.status === 'PENDING') return 'Su mesero no llegó · ve tú';
    if ((request.escalation || 0) >= 1) return 'Sigue esperando';
    if (request.status === 'IN_PROGRESS') return `${request.waiterName ? request.waiterName.split(' ')[0] + ' va' : 'En camino'}${request.reason ? ' · ' + this.reasonLabels[request.reason] : ''}`;
    return request.reason ? this.reasonLabels[request.reason] : 'Llama al mesero';
  }
  /** Fracción del tiempo de «En camino» usada (0 a 1, en 5 minutos). */
  callProgress(request: ServiceRequestView): number {
    if (!request.attendedAt) return 0;
    // Se usa la hora actual (no solo el reloj de 30 s) y se acota: el reloj del celular puede ir desfasado.
    const now = Math.max(this.nowTick(), Date.now());
    return Math.min(1, Math.max(0, (now - new Date(request.attendedAt).getTime()) / 300000));
  }
  callMinutesLeft(request: ServiceRequestView): number {
    return Math.min(5, Math.max(1, Math.ceil(5 - this.callProgress(request) * 5)));
  }
  /** «✓ Listo»: cierra el llamado al momento (opcional; si no, se cierra solo). */
  finishCall(request: ServiceRequestView): void {
    this.api.patch(`/staff/service-requests/${request.id}`, { status: 'RESOLVED' }).subscribe({
      next: () => { this.feedback(`${request.tableLabel} atendida.`); this.refresh(); },
      error: (err) => this.feedback(err.error?.message || 'No se pudo cerrar el llamado.', 'error'),
    });
  }
  private readonly waiterNav = inject(WaiterNavService);
  readonly mobileSection = this.waiterNav.section;
  /** Vista del salón: tarjetas detalladas o plano animado con mesas arrastrables. */
  readonly viewMode = this.waiterNav.view;
  private readonly syncPending = effect(() => this.waiterNav.pending.set(this.pendingCount()));
  readonly floorPlan = signal<FloorPlan | null>(null);
  readonly floorStates = computed<FloorTableState[]>(() => {
    const visible = new Set(this.visibleTables().map((table) => table.id));
    return this.zonedTables().map((table) => {
      const requests = table.sessionId
        ? this.requests().filter(
            (request) => request.sessionId === table.sessionId && request.status !== 'RESOLVED',
          )
        : [];
      const readyCount = this.countStatus(table, 'READY');
      const reservation = table.status === 'OPEN' ? undefined : this.reservationFor(table);
      return {
        table,
        tone: table.requiresReview
          ? 'review'
          : requests.some((request) => request.type === 'WAITER_CALL')
            ? 'call'
            : requests.some((request) => request.type === 'BILL')
              ? 'bill'
              : readyCount
                ? 'ready'
                : table.status === 'OPEN'
                  ? 'busy'
                  : 'free',
        guests: this.activeGuestCountFor(table),
        total: table.sessionId ? this.tableOrders()[table.sessionId]?.submittedTotal || 0 : 0,
        readyCount,
        cooking: this.hasStatus(table, 'SUBMITTED') || this.hasStatus(table, 'PREPARING'),
        reservedAt: reservation?.reservedAt || null,
        dimmed: !visible.has(table.id),
      };
    });
  });
  /** Fotografía operativa de cada mesa para evitar abrirlas una por una. */
  readonly tableOrders = signal<Record<string, OrderSnapshot>>({});
  readonly notificationCenter = viewChild.required(WaiterNotificationsComponent);
  /** En celular, Pendientes tiene dos pestañas: lo que falta hacer y el historial del turno (antes en la campana). */
  readonly queueTab = signal<'todo' | 'history'>('todo');
  openHistory(): void { this.queueTab.set('history'); this.notificationCenter().markAllRead(); }
  readonly qrPreview = signal<{
    label: string;
    link: string;
    dataUrl: string;
    table?: TableView;
  } | null>(null);
  readonly composer = signal<{ sessionId: string; label: string } | null>(null);
  readonly openingTable = signal(false);
  readonly voiding = signal<OrderItemView | null>(null);
  readonly voidBusy = signal(false);
  /** Evita duplicar el aviso cuando el evento en tiempo real es eco de una anulación propia. */
  private ownVoidUntil = 0;
  private ownMoveUntil = 0;
  /** Evita avisarme de un cambio de mesero que hice yo mismo. */
  private ownAssignUntil = 0;
  // ——— Mesero a cargo ———
  readonly waiterPicker = signal(false);
  readonly waiterOptions = signal<WaiterOption[]>([]);
  readonly assigning = signal(false);
  readonly firstName = firstName;
  readonly initials = initialsOf;
  readonly hue = waiterHue;
  myId(): string { return this.auth.userId(); }
  readonly teamOpen = signal(false);
  /** Color de la zona que se está atendiendo: tiñe suavemente la pantalla del mesero. */
  readonly activeZoneColor = computed(() => {
    const filter = this.zoneFilter();
    const id =
      filter === 'MINE'
        ? this.myZoneIds().length === 1
          ? this.myZoneIds()[0]
          : null
        : filter === 'ALL'
          ? null
          : filter;
    return this.zones().find((zone) => zone.id === id)?.color || null;
  });
  private readonly zoneTint = effect(() => {
    const color = this.activeZoneColor();
    document.body.classList.toggle('zone-tinted', !!color);
    if (color) document.body.style.setProperty('--zone-tint', color);
  });
  /** Comensales conectados a la mesa, para mostrarlos como avatares en la tarjeta. */
  guestsFor(table: TableView) {
    return table.sessionId
      ? this.tableOrders()[table.sessionId]?.guests.filter((person) => person.active) || []
      : [];
  }
  avatarHue(name: string): number {
    return [...name].reduce((sum, char) => sum + char.charCodeAt(0), 0) % 360;
  }
  /** Alertas proactivas de cocina e insumos; cada una se anuncia una sola vez. */
  readonly opsAlerts = signal<OpsAlert[]>([]);
  private announcedAlerts = new Set<string>();
  private opsTimer?: number;
  private clockTimer?: number;
  /** Reloj del tablero para mostrar cuánto lleva ocupada cada mesa. */
  readonly nowTick = signal(Date.now());
  /** Contadores que también filtran el tablero. */
  readonly boardKpis = computed(() => [
    { key: 'ALL' as const, label: 'Todas', count: this.zonedTables().length, icon: 'grid', tone: 'neutral' },
    { key: 'OPEN' as const, label: 'Ocupadas', count: this.occupiedCount(), icon: 'users', tone: 'orange' },
    { key: 'READY' as const, label: 'Para servir', count: this.readyTables().length, icon: 'chef', tone: 'green' },
    { key: 'FREE' as const, label: 'Libres', count: this.freeCount(), icon: 'chair', tone: 'teal' },
  ]);
  readonly publicBaseUrl = signal(location.origin);
  readonly qrNetworkReady = signal(false);
  hostGuestId = '';
  readonly readyTables = computed<ReadyTableAlert[]>(() =>
    this.zonedTables().flatMap((table) => {
      const ready = this.submittedItems(table).filter((item) => item.status === 'READY');
      if (!ready.length) return [];
      return [
        {
          table,
          items: ready,
          quantity: ready.reduce((sum, item) => sum + item.quantity, 0),
          names: ready.map((item) => `${item.quantity}× ${item.name}`).join(' · '),
        },
      ];
    }),
  );
  readonly occupiedCount = computed(
    () => this.zonedTables().filter((table) => table.status === 'OPEN').length,
  );
  readonly freeCount = computed(
    () => this.zonedTables().filter((table) => table.status !== 'OPEN').length,
  );
  /** Reservas que llegan en la próxima hora (o que ya deberían haber llegado) en la zona elegida. */
  readonly arrivals = computed(() => {
    // Con nowTick la lista se recalcula sola cada 30 s: entra quien ya está a una hora, sale quien pasó 45 min.
    const now = this.nowTick();
    return this.reservations()
      .filter((item) => item.status === 'PENDING' || item.status === 'CONFIRMED')
      .filter((item) => { const minutes = (new Date(item.reservedAt).getTime() - now) / 60000; return minutes <= 60 && minutes >= -45; })
      .filter((item) => { const table = this.tables().find((t) => t.id === item.tableId); return table ? this.inZoneFilter(table) : true; })
      .sort((a, b) => a.reservedAt.localeCompare(b.reservedAt));
  });
  /** Buscar la reserva de quien acaba de llegar: por nombre (sin tildes) o dígitos del celular. */
  readonly resQuery = signal('');
  readonly foundReservations = computed(() => {
    const query = this.resQuery().trim();
    if (!query) return [];
    const plain = (text: string) => text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const text = plain(query); const digits = query.replace(/\D/g, '');
    return this.reservations()
      .filter((item) => item.status === 'PENDING' || item.status === 'CONFIRMED')
      .filter((item) => plain(item.customerName).includes(text) || (digits.length >= 3 && (item.phone || '').replace(/\D/g, '').includes(digits)))
      .sort((a, b) => a.reservedAt.localeCompare(b.reservedAt));
  });
  /** Minutos desde que la mesa pidió para considerar que «está esperando demasiado». */
  readonly WAIT_LIMIT = 20;
  /** Mesas a las que el mesero ya avisó de la demora: no se repite el aviso por 10 minutos. */
  private readonly notifiedWait = signal<Record<string, number>>({});
  /** Mis mesas que esperan su plato hace mucho (lo más viejo aún en cocina), de la más demorada a la menos. */
  readonly waitingTables = computed(() => {
    const now = this.nowTick();
    const snoozed = this.notifiedWait();
    return this.zonedTables().flatMap((table) => {
      if (!table.sessionId || (snoozed[table.id] || 0) > now) return [];
      const items = this.submittedItems(table).filter((item) => (item.status === 'SUBMITTED' || item.status === 'PREPARING') && item.submittedAt);
      if (!items.length) return [];
      const oldest = Math.min(...items.map((item) => new Date(item.submittedAt as string).getTime()));
      const minutes = Math.floor((now - oldest) / 60000);
      if (minutes < this.WAIT_LIMIT) return [];
      const late = items.filter((item) => new Date(item.submittedAt as string).getTime() === oldest);
      return [{ table, minutes, names: late.map((item) => `${item.quantity}× ${item.name}`).join(' · '),
        preparing: late.some((item) => item.status === 'PREPARING') }];
    }).sort((a, b) => b.minutes - a.minutes);
  });
  readonly pendingCount = computed(
    () => this.zoneRequests().length + this.readyTables().length + this.transfers().length + this.arrivals().length + this.waitingTables().length,
  );
  /** El mesero fue a la mesa a avisar la demora: el aviso se calla 10 minutos. */
  notifiedDelay(table: TableView): void {
    navigator.vibrate?.(8);
    this.notifiedWait.update((current) => ({ ...current, [table.id]: Date.now() + 10 * 60000 }));
    this.feedback(`Anotado: avisaste a ${table.label}. Te recordamos en 10 min si sigue esperando.`);
  }
  readonly seatingFor = signal<ReservationView | null>(null);
  readonly seatingBusy = signal(false);
  readonly visibleTables = computed(() =>
    this.zonedTables().filter((table) => {
      if (!table.label.toLocaleLowerCase().includes(this.tableSearch().trim().toLocaleLowerCase()))
        return false;
      const filter = this.tableFilter();
      if (filter === 'OPEN') return table.status === 'OPEN';
      if (filter === 'READY') return !table.requiresReview && this.hasStatus(table, 'READY');
      if (filter === 'FREE') return table.status !== 'OPEN';
      return true;
    }),
  );

  ngOnInit(): void {
    this.api
      .get<{ baseUrl: string; networkReady: boolean }>('/public/config')
      .subscribe((config) => {
        this.publicBaseUrl.set(config.baseUrl.replace(/\/$/, ''));
        this.qrNetworkReady.set(config.networkReady);
      });
    this.refresh();
    this.loadFloor();
    this.loadOps();
    this.opsTimer = window.setInterval(() => this.loadOps(), 45000);
    this.clockTimer = window.setInterval(() => this.nowTick.set(Date.now()), 30000);
    this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => { this.refresh(); this.loadOps(); });
    const branch = this.auth.branchId();
    if (branch)
      this.unwatch = this.realtime.watch<OrderSnapshot>(
        `/topic/branches/${branch}/waiters`,
        (event) => this.onRealtime(event),
      );
  }
  /** Zonas del local y las asignadas a este usuario. */
  private loadZones(): void {
    this.api.get<{ zones: ZoneView[]; myZoneIds: string[]; myTableIds?: string[] }>('/staff/zones').subscribe({
      next: (value) => {
        this.zones.set(value.zones);
        this.myZoneIds.set(value.myZoneIds);
        this.myTableIds.set(value.myTableIds || []);
        if (!this.zoneFilterTouched) this.zoneFilter.set(value.myZoneIds.length || value.myTableIds?.length ? 'MINE' : 'ALL');
      },
      error: () => undefined,
    });
  }
  private loadOps(): void {
    this.api.get<{ alerts: OpsAlert[] }>('/staff/ops/stations').subscribe({
      next: (value) => {
        this.opsAlerts.set(value.alerts);
        for (const alert of value.alerts) {
          if (this.announcedAlerts.has(alert.key)) continue;
          this.announcedAlerts.add(alert.key);
          // Ya se ve en «Pendientes»: va a la campana sin tapar la pantalla con un aviso flotante.
          this.notificationCenter().add(alert.title, alert.body, alert.tone === 'error' ? 'error' : 'warning', alert.icon);
        }
      },
      error: () => undefined,
    });
  }
  /** Al liberarse una mesa, recuerda si hay clientes en la lista de espera que caben en ella. */
  private suggestFromWaitlist(tableLabel: string): void {
    const table = this.tables().find((item) => item.label === tableLabel);
    this.api
      .get<{ entries: { customerName: string; partySize: number; status: string }[] }>(
        '/staff/waitlist',
      )
      .subscribe({
        next: (value) => {
          const waiting = value.entries.filter((entry) => entry.status === 'WAITING');
          if (!waiting.length) return;
          const fit = table
            ? waiting.find((entry) => entry.partySize <= table.capacity)
            : waiting[0];
          this.notify(
            'Lista de espera',
            fit
              ? `${tableLabel} quedó libre: ideal para ${fit.customerName} (${fit.partySize} pers.). ${waiting.length} ${waiting.length === 1 ? 'grupo espera' : 'grupos esperan'}.`
              : `${tableLabel} quedó libre y hay ${waiting.length} ${waiting.length === 1 ? 'grupo' : 'grupos'} en espera.`,
            'info',
            '⏳',
          );
        },
        error: () => undefined,
      });
  }
  private loadFloor(): void {
    this.api
      .get<FloorPlan>('/staff/floor')
      .subscribe({ next: (plan) => this.floorPlan.set(plan), error: () => undefined });
  }
  setViewMode(mode: 'cards' | 'floor'): void {
    this.waiterNav.setView(mode);
  }
  /** Arrastrar una mesa ocupada sobre una libre la mueve; sobre una ocupada, une ambas cuentas. */
  async moveTable(change: TableMove): Promise<void> {
    const { from, to } = change;
    if (!from.sessionId) return;
    const merge = to.status === 'OPEN';
    const accepted = await this.dialog.confirm({
      title: merge ? 'Unir mesas' : 'Cambiar de mesa',
      message: merge
        ? `La cuenta de ${from.label} se unirá a ${to.label}: comensales y pedidos pasarán a ${to.label} y ${from.label} quedará libre.`
        : `Los comensales de ${from.label} pasarán a ${to.label} con todos sus pedidos. ${from.label} quedará libre.`,
      confirmLabel: merge ? 'Unir mesas' : 'Mover mesa',
      tone: 'info',
      icon: merge ? '⧉' : '⇄',
    });
    if (!accepted) return;
    this.api.post(`/staff/sessions/${from.sessionId}/move`, { tableId: to.id }).subscribe({
      next: () => {
        this.ownMoveUntil = Date.now() + 3000;
        this.sound.haptic();
        this.closeSelected();
        this.feedback(
          merge
            ? `${from.label} se unió a ${to.label}.`
            : `${from.label} ahora está en ${to.label}.`,
        );
        this.refresh();
      },
      error: (err) => this.feedback(err.error?.message || 'No se pudo mover la mesa.', 'error'),
    });
  }
  readonly zonePickerOpen = signal(false);
  zoneFilterLabel(): string {
    const filter = this.zoneFilter();
    return filter === 'MINE' ? 'Mis mesas' : filter === 'ALL' ? 'Todo el local' : this.zoneById(filter)?.name || 'Zona';
  }
  pickZone(filter: string): void { navigator.vibrate?.(8); this.selectZone(filter); this.zonePickerOpen.set(false); }
  zoneById(id: string): ZoneView | undefined { return this.zones().find((zone) => zone.id === id); }
  selectZone(filter: string): void {
    this.zoneFilterTouched = true;
    this.zoneFilter.set(filter);
  }
  inZoneFilter(table: TableView): boolean {
    const filter = this.zoneFilter();
    if (filter === 'ALL') return true;
    if (filter === 'MINE') return table.servedById === this.auth.userId() || this.myTableIds().includes(table.id)
      || (!!table.zoneId && this.myZoneIds().includes(table.zoneId));
    return table.zoneId === filter;
  }
  zoneOf(table: TableView): ZoneView | undefined {
    return this.zones().find((zone) => zone.id === table.zoneId);
  }
  zoneCount(zoneId: string): { total: number; busy: number } {
    const tables = this.tables().filter((table) => table.zoneId === zoneId);
    return { total: tables.length, busy: tables.filter((table) => table.status === 'OPEN').length };
  }
  /**
   * ¿Me toca este aviso? El servidor indica a quién (el mesero a cargo, o su reemplazo si no está en turno,
   * o todos si se escaló). Sin esa información (eventos que no son de una mesa) se usan mis zonas.
   */
  private alertsMe(event: RealtimeEvent<unknown>, table: TableView | undefined): boolean {
    const audience = event.audience;
    if (audience) return audience.alert === null || audience.alert.includes(this.auth.userId());
    return !this.outsideMyZones(table);
  }
  /** Un mesero con zonas asignadas solo recibe avisos de sus mesas (y de pedidos sin mesa). */
  private outsideMyZones(table: TableView | undefined): boolean {
    return (
      !!table &&
      this.myZoneIds().length > 0 &&
      !(table.zoneId && this.myZoneIds().includes(table.zoneId))
    );
  }
  refresh(): void {
    this.loadZones();
    this.tablesError.set(false);
    this.api.get<TableView[]>('/staff/tables').subscribe({
      next: (value) => {
        this.tablesLoading.set(false);
        this.tables.set(value);
        const current = this.selected();
        if (current) this.selected.set(value.find((table) => table.id === current.id) || null);
        value
          .filter((table) => table.sessionId)
          .forEach((table) => this.loadTableSummary(table.sessionId!));
        const activeSessions = new Set(
          value.flatMap((table) => (table.sessionId ? [table.sessionId] : [])),
        );
        this.tableOrders.update((existing) =>
          Object.fromEntries(
            Object.entries(existing).filter(([session]) => activeSessions.has(session)),
          ),
        );
      },
      error: () => {
        this.tablesLoading.set(false);
        this.tablesError.set(true);
      },
    });
    this.api
      .get<ServiceRequestView[]>('/staff/service-requests')
      .subscribe((value) => this.requests.set(value));
    this.api
      .get<TableTransferView[]>('/staff/table-transfers')
      .subscribe((value) => this.transfers.set(value));
    this.api
      .get<ReservationView[]>('/staff/reservations/upcoming?hours=12&pastMinutes=180')
      .subscribe({ next: (value) => this.reservations.set(value), error: () => undefined });
  }
  arrivalLabel(item: ReservationView): string {
    const minutes = Math.round((new Date(item.reservedAt).getTime() - Date.now()) / 60000);
    if (minutes > 0) return `Llega en ${minutes} min`;
    return minutes > -5 ? 'Llega ahora' : `Retrasada ${-minutes} min`;
  }
  /** Mesas libres donde cabe el grupo, para sentar una reserva sin mesa asignada. */
  seatOptions(item: ReservationView): TableView[] {
    return this.tables().filter((table) => table.status !== 'OPEN' && !table.requiresReview && table.capacity >= item.partySize)
      .sort((a, b) => a.capacity - b.capacity);
  }
  seatArrival(item: ReservationView, tableId?: string): void {
    if (!tableId && !item.tableId) { this.seatingFor.set(item); return; }
    if (this.seatingBusy()) return;
    this.seatingBusy.set(true);
    navigator.vibrate?.(12);
    this.api.post<ReservationView>(`/staff/reservations/${item.id}/seat`, { tableId: tableId || item.tableId }).subscribe({
      next: (value) => {
        this.seatingBusy.set(false); this.seatingFor.set(null);
        this.feedback(`${item.customerName} quedó sentado en ${value.tableLabel || 'su mesa'}.`);
        this.refresh();
      },
      error: (err) => { this.seatingBusy.set(false); this.feedback(err.error?.message || 'No se pudo sentar la reserva.', 'error'); },
    });
  }
  /** Una mesa libre abre su QR; una ocupada muestra solo datos de su propia atención. */
  select(table: TableView): void {
    this.closeSelected();
    if (!table.sessionId) {
      void this.displayQr(table);
      return;
    }
    this.selected.set(table);
    this.order.set(this.tableOrders()[table.sessionId] || null);
    this.loadOrder(table.sessionId);
  }
  closeSelected(): void {
    this.waiterPicker.set(false);
    this.checkoutOpen.set(false);
    this.selected.set(null);
    this.order.set(null);
    this.detailDragY.set(0);
    this.detailDragging.set(false);
    this.detailClosing.set(false);
    this.detailDragStartY = null;
  }
  /** La mesa la atiendo yo. */
  isMine(table: TableView): boolean { return !!table.servedById && table.servedById === this.auth.userId(); }
  /** Un mesero puede tomar cualquier mesa y pasar las suyas; pasar las de otro lo hace el encargado. */
  canPass(table: TableView): boolean {
    return this.auth.role() !== 'WAITER' || !table.servedById || this.isMine(table);
  }
  toggleWaiterPicker(): void {
    if (this.waiterPicker()) { this.waiterPicker.set(false); return; }
    this.waiterPicker.set(true);
    this.api.get<WaiterOption[]>('/staff/waiters').subscribe({ next: (value) => this.waiterOptions.set(value), error: () => this.waiterOptions.set([]) });
  }
  assignWaiter(table: TableView, waiterId: string): void {
    if (!table.sessionId || this.assigning()) return;
    this.assigning.set(true);
    this.ownAssignUntil = Date.now() + 4000;
    this.api.put<WaiterChange>(`/staff/sessions/${table.sessionId}/waiter`, { waiterId }).subscribe({
      next: (value) => {
        this.assigning.set(false); this.waiterPicker.set(false);
        this.feedback(value.waiterId === this.auth.userId() ? `Ahora atiendes ${value.tableLabel}.` : `${value.tableLabel} pasó a ${firstName(value.waiterName)}.`);
        this.refresh();
      },
      error: (err) => { this.assigning.set(false); this.feedback(err.error?.message || 'No se pudo cambiar el mesero.', 'error'); },
    });
  }
  takeTable(table: TableView): void { this.assignWaiter(table, this.auth.userId()); }
  startDetailDrag(event: PointerEvent): void {
    if (!window.matchMedia('(max-width: 980px)').matches) return;
    this.detailDragStartY = event.clientY;
    this.detailDragging.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  moveDetailDrag(event: PointerEvent): void {
    if (this.detailDragStartY === null) return;
    this.detailDragY.set(Math.max(0, event.clientY - this.detailDragStartY));
  }
  endDetailDrag(event: PointerEvent): void {
    if (this.detailDragStartY === null) return;
    const shouldClose = this.detailDragY() > 90;
    this.detailDragStartY = null;
    this.detailDragging.set(false);
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
    if (shouldClose) {
      this.detailClosing.set(true);
      this.detailDragY.set(window.innerHeight);
      window.setTimeout(() => this.closeSelected(), 240);
    } else {
      this.detailDragY.set(0);
    }
  }
  openCheckout(): void {
    if (this.selected()?.sessionId) this.checkoutOpen.set(true);
  }
  onSessionClosed(): void {
    const label = this.selected()?.label || 'La mesa';
    this.closeSelected();
    this.feedback(`${label} quedó libre.`);
    this.refresh();
  }
  private reloadSelected(): void {
    const sessionId = this.selected()?.sessionId;
    if (sessionId) this.loadOrder(sessionId);
  }
  private loadOrder(sessionId: string): void {
    this.api.get<OrderSnapshot>(`/staff/sessions/${sessionId}/orders`).subscribe({
      next: (value) => {
        this.applySnapshot(value);
        // Una respuesta lenta no debe reemplazar el detalle de otra mesa recién seleccionada.
        if (this.selected()?.sessionId !== sessionId) return;
        this.order.set(value);
        this.hostGuestId =
          value.guests.find((person) => person.active && person.host)?.id ||
          value.guests.find((person) => person.active)?.id ||
          '';
      },
      error: () => {
        if (this.selected()?.sessionId !== sessionId) return;
        if (!this.order()) this.closeSelected();
        this.feedback(
          'No se pudieron cargar los pedidos. Intenta abrir la mesa nuevamente.',
          'error',
        );
      },
    });
  }
  private loadTableSummary(sessionId: string): void {
    this.api
      .get<OrderSnapshot>(`/staff/sessions/${sessionId}/orders`)
      .subscribe((value) => this.applySnapshot(value));
  }
  private applySnapshot(value: OrderSnapshot): void {
    this.tableOrders.update((current) => ({ ...current, [value.sessionId]: value }));
  }
  private onRealtime(event: RealtimeEvent<OrderSnapshot>): void {
    // El canal reúne pedidos, llamadas y cambios de mesa; solo los pedidos contienen una fotografía completa.
    const payload = event.payload as unknown;
    const snapshot = this.isOrderSnapshot(payload) ? payload : null;
    if (snapshot) {
      this.applySnapshot(snapshot);
      if (this.selected()?.sessionId === snapshot.sessionId) this.order.set(snapshot);
    }
    const sessionId = this.eventSessionId(payload);
    const table = this.tables().find((value) => value.sessionId === sessionId);
    const muted = !this.alertsMe(event, table);
    // Cambio de mesero: solo suena al que recibe la mesa, y no cuando la tomó él mismo.
    const assignment = event.type === 'TABLE_WAITER_CHANGED' ? (payload as WaiterChange) : null;
    const passedToMe = !!assignment && assignment.waiterId === this.auth.userId() && !!assignment.previousWaiterId
      && assignment.previousWaiterId !== this.auth.userId() && Date.now() > this.ownAssignUntil;
    if (!muted && (!assignment || passedToMe)) this.sound.forEvent(event.type);
    const tableLabel = this.eventTableLabel(payload) || table?.label || 'Una mesa';
    if (event.type === 'ORDER_SUBMITTED')
      this.notifyIfMine(
        muted,
        'Nuevo pedido',
        `${tableLabel} envió una nueva ronda a cocina.`,
        'info',
        '▤',
      );
    if (event.type === 'ORDER_ITEM_PREPARING')
      this.notifyIfMine(
        muted,
        'Pedido en preparación',
        `Cocina comenzó a preparar un plato de ${tableLabel}.`,
        'info',
        '♨',
      );
    if (event.type === 'ORDER_ITEM_READY')
      this.notifyIfMine(
        muted,
        'Plato listo',
        `${tableLabel} tiene un plato listo para entregar.`,
        'warning',
        '✓',
      );
    if (event.type === 'TABLE_GUEST_JOINED') {
      const name =
        payload && typeof payload === 'object' && 'displayName' in payload
          ? String((payload as { displayName: unknown }).displayName)
          : 'Un comensal';
      this.notifyIfMine(muted, 'Nuevo comensal', `${name} se unió a ${tableLabel}.`, 'info', '+');
    }
    if (event.type === 'TABLE_TRANSFER_REQUESTED') {
      const name =
        payload && typeof payload === 'object' && 'displayName' in payload
          ? String((payload as { displayName: unknown }).displayName)
          : 'Un comensal';
      this.notifyIfMine(
        muted,
        'Cambio de mesa',
        `${name} solicita cambiarse a ${this.eventDestinationLabel(payload) || 'otra mesa'}.`,
        'warning',
        '⇄',
      );
    }
    if (event.type === 'BILL_REQUESTED')
      this.notifyIfMine(
        muted,
        'Cuenta solicitada',
        `${tableLabel} solicita la cuenta${this.eventNote(payload) ? ' · ' + this.eventNote(payload) : ''}.`,
        'warning',
        '▤',
      );
    if (event.type === 'WAITER_CALLED') {
      const reason = (payload as { reason?: string }).reason;
      this.notifyIfMine(
        muted,
        reason === 'PROBLEM' ? 'Algo salió mal en la mesa' : 'Llamada de mesa',
        reason ? `${tableLabel}: ${this.reasonLabels[reason].toLowerCase()}.` : `${tableLabel} necesita un mesero.`,
        reason === 'PROBLEM' ? 'error' : 'warning',
        '!',
      );
    }
    if (event.type === 'WAITER_CALL_ESCALATED') {
      const level = (payload as { escalation?: number }).escalation || 1;
      // Nivel 2: el llamado se abrió a todo el equipo, sin importar la zona.
      if (level >= 2 && muted) this.sound.forEvent(event.type);
      this.notifyIfMine(
        level >= 2 ? false : muted,
        level >= 2 ? `${tableLabel} sigue sin atención` : `${tableLabel} sigue esperando`,
        level >= 2 ? 'Su mesero no llegó. Si estás cerca, toca «Voy».' : 'La mesa dice que aún no la atienden.',
        'error',
        '!',
      );
    }
    if (event.type === 'TABLE_TRANSFER_CANCELLED')
      this.notifyIfMine(
        muted,
        'Cambio cancelado',
        `El comensal canceló su solicitud de cambio de mesa.`,
        'info',
        '×',
      );
    if (event.type === 'TABLE_SESSION_REVIEW_REQUIRED')
      this.notifyIfMine(
        muted,
        'Revisión requerida',
        `${tableLabel} conserva una atención anterior que debe revisarse.`,
        'warning',
        '!',
      );
    if (event.type === 'ORDER_ITEM_CANCELLED' && Date.now() > this.ownVoidUntil)
      this.notifyIfMine(
        muted,
        'Plato anulado',
        `Se anuló un producto de ${tableLabel}.`,
        'info',
        '⊘',
      );
    if (event.type === 'MENU_AVAILABILITY_CHANGED') {
      const change = payload as { name?: string; available?: boolean };
      this.notifyIfMine(
        muted,
        change.available ? 'Producto disponible' : 'Producto agotado',
        `${change.name || 'Un producto'} ${change.available ? 'vuelve a estar disponible' : 'se marcó como agotado'}.`,
        change.available ? 'success' : 'warning',
        change.available ? '✓' : '⊘',
      );
    }
    if (
      (event.type === 'DISCOUNT_UPDATED' || event.type === 'PAYMENT_REGISTERED') &&
      sessionId === this.selected()?.sessionId
    )
      this.checkout()?.reload();
    if (event.type === 'FLOOR_UPDATED') this.loadFloor();
    if (event.type === 'SERVICE_RATED') {
      const rated = payload as { tableLabel?: string; stars?: number; waiterName?: string };
      const stars = Math.max(1, Math.min(5, Number(rated.stars) || 0));
      this.notify(
        `${'★'.repeat(stars)}${'☆'.repeat(5 - stars)} Nueva calificación`,
        `${rated.tableLabel || 'Una mesa'} calificó la atención${rated.waiterName ? ' de ' + rated.waiterName : ''} con ${stars} ${stars === 1 ? 'estrella' : 'estrellas'}.`,
        stars >= 4 ? 'success' : 'warning',
        '★',
      );
    }
    if (event.type === 'TABLE_MOVED' && Date.now() > this.ownMoveUntil) {
      const move = payload as { kind?: string; sourceLabel?: string; destinationLabel?: string };
      this.notify(
        move.kind === 'MERGED' ? 'Mesas unidas' : 'Mesa movida',
        move.kind === 'MERGED'
          ? `${move.sourceLabel} se unió a ${move.destinationLabel}.`
          : `${move.sourceLabel} pasó a ${move.destinationLabel}.`,
        'info',
        move.kind === 'MERGED' ? '⧉' : '⇄',
      );
    }
    if (passedToMe) this.notify('Te pasaron una mesa', `Ahora atiendes ${assignment!.tableLabel}.`, 'info', '⇄');
    if (assignment && assignment.previousWaiterId === this.auth.userId() && assignment.waiterId !== this.auth.userId()
      && Date.now() > this.ownAssignUntil)
      this.notify('Mesa reasignada', `${assignment.tableLabel} ahora la atiende ${this.firstName(assignment.waiterName) || 'otro mesero'}.`, 'info', '⇄');
    if (event.type === 'PRINT_FAILED') {
      this.notify('Comanda sin imprimir', (payload as { message?: string }).message || `La comanda de ${tableLabel} no se imprimió.`, 'error', '⎙');
      if (sessionId && sessionId === this.selected()?.sessionId) this.loadOrder(sessionId);
    }
    if (event.type === 'PRINT_UPDATED' && sessionId && sessionId === this.selected()?.sessionId) this.loadOrder(sessionId);
    if (event.type === 'DISH_WAITING') {
      const items = (payload as { items?: string[] }).items || [];
      this.notifyIfMine(muted, 'Plato esperando', `${tableLabel}: ${items.slice(0, 2).join(', ')}${items.length > 2 ? ' y más' : ''} lleva más de 2 minutos listo.`, 'warning', '✓');
    }
    if (event.type === 'BILL_WAITING')
      this.notifyIfMine(muted, 'Cuenta sin atender', `${tableLabel} sigue esperando la cuenta.`, 'error', '▤');
    if (event.type === 'TABLE_SESSION_CLOSED' && !muted) this.suggestFromWaitlist(tableLabel);
    if (event.type === 'TABLE_SESSION_CLOSED')
      this.notifyIfMine(
        muted,
        'Mesa cerrada',
        `${tableLabel} finalizó su atención.`,
        'success',
        '✓',
      );
    this.refresh();
  }
  private isOrderSnapshot(value: unknown): value is OrderSnapshot {
    return (
      !!value &&
      typeof value === 'object' &&
      Array.isArray((value as OrderSnapshot).items) &&
      Array.isArray((value as OrderSnapshot).guests)
    );
  }
  private eventSessionId(value: unknown): string | undefined {
    return value && typeof value === 'object' && 'sessionId' in value
      ? String((value as { sessionId: unknown }).sessionId)
      : undefined;
  }
  private eventTableLabel(value: unknown): string | undefined {
    return value && typeof value === 'object' && 'tableLabel' in value
      ? String((value as { tableLabel: unknown }).tableLabel)
      : undefined;
  }
  private eventNote(value: unknown): string | undefined {
    return value &&
      typeof value === 'object' &&
      'note' in value &&
      (value as { note: unknown }).note
      ? String((value as { note: unknown }).note)
      : undefined;
  }
  private eventDestinationLabel(value: unknown): string | undefined {
    return value && typeof value === 'object' && 'destinationTableLabel' in value
      ? String((value as { destinationTableLabel: unknown }).destinationTableLabel)
      : undefined;
  }

  private notifyIfMine(
    muted: boolean,
    title: string,
    body: string,
    tone: NotificationTone = 'info',
    icon = 'i',
  ): void {
    if (!muted) this.notify(title, body, tone, icon);
  }
  /** Registra el evento en el historial y muestra un toast que desaparece automáticamente. */
  private notify(
    title: string,
    body: string,
    tone: NotificationTone = 'info',
    icon = 'i',
    save = true,
  ): void {
    if (save) this.notificationCenter().add(title, body, tone, icon);
    // Lo informativo queda en la campana; solo lo que requiere acción (o la respuesta a tu acción) aparece en pantalla.
    if (!save || tone !== 'info') this.toast.show(title, body, tone, icon);
  }
  private feedback(body: string, tone: NotificationTone = 'success'): void {
    this.notify(
      tone === 'error' ? 'No se pudo completar' : 'Listo',
      body,
      tone,
      tone === 'error' ? '!' : '✓',
      false,
    );
  }
  /** Estado principal de la mesa: lo más urgente manda (revisión, cuenta, llamada, platos listos). */
  tableState(table: TableView): 'free' | 'busy' | 'ready' | 'bill' | 'call' | 'review' {
    if (table.requiresReview) return 'review';
    if (table.status !== 'OPEN') return 'free';
    const request = this.zoneRequests().find((item) => item.sessionId === table.sessionId && item.status !== 'RESOLVED');
    if (request?.type === 'BILL') return 'bill';
    if (request) return 'call';
    return this.hasStatus(table, 'READY') ? 'ready' : 'busy';
  }
  /** Mesa partida en palabra y número («Mesa» · «01») para la tarjeta del celular. */
  labelParts(label: string): { word: string; number: string } {
    const match = /^(.*?)\s*(\d+)\s*$/.exec(label.trim());
    return match ? { word: match[1] || 'Mesa', number: match[2] } : { word: '', number: label };
  }
  /** Lo siguiente que hay que hacer en la mesa, como botón directo en la tarjeta. */
  quickAction(table: TableView): { label: string; icon: string } {
    const state = this.tableState(table);
    if (state === 'call') { const request = this.tableRequest(table); return { label: request ? this.requestActionLabel(request) : 'Atender', icon: 'bell' }; }
    if (state === 'bill') return { label: 'Cobrar', icon: 'receipt' };
    if (state === 'ready') { const ready = this.readyTables().find((item) => item.table.id === table.id); return { label: `Servir ${ready?.quantity || ''}`.trim(), icon: 'check' }; }
    if (state === 'review') return { label: 'Revisar', icon: 'alert' };
    if (state === 'free') return { label: 'Tomar pedido', icon: 'plus' };
    return { label: 'Pedido', icon: 'plus' };
  }
  quickBusy(table: TableView): boolean {
    const ready = this.readyTables().find((item) => item.table.id === table.id);
    return (!!ready && this.readyServing(ready)) || (this.tableState(table) === 'free' && this.openingTable());
  }
  runQuick(table: TableView): void {
    navigator.vibrate?.(12);
    const state = this.tableState(table);
    const request = this.tableRequest(table);
    if ((state === 'call' || state === 'bill') && request) { this.handle(request); return; }
    if (state === 'ready') { const ready = this.readyTables().find((item) => item.table.id === table.id); if (ready) this.serveReady(ready); return; }
    if (state === 'free') { this.takeOrderAtFreeTable(table); return; }
    if (state === 'busy' && table.sessionId) { this.composer.set({ sessionId: table.sessionId, label: table.label }); return; }
    this.select(table);
  }
  private tableRequest(table: TableView): ServiceRequestView | undefined {
    return this.zoneRequests().find((item) => item.sessionId === table.sessionId && item.status !== 'RESOLVED');
  }
  tableStateLabel(table: TableView): string {
    return { free: 'Libre', busy: 'Ocupada', ready: 'Listo para servir', bill: 'Pide la cuenta', call: 'Te llama', review: 'Revisar' }[this.tableState(table)];
  }
  tableStateShort(table: TableView): string {
    return { free: 'Libre', busy: 'Ocupada', ready: 'Para servir', bill: 'Cuenta', call: 'Te llama', review: 'Revisar' }[this.tableState(table)];
  }
  /** Consumo enviado de la mesa (sin anulados). */
  tableTotal(table: TableView): number {
    return this.submittedItems(table)
      .filter((item) => item.status !== 'CANCELLED')
      .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0);
  }
  openMinutes(table: TableView): string {
    if (!table.openedAt) return '';
    const minutes = Math.max(0, Math.floor((this.nowTick() - new Date(table.openedAt).getTime()) / 60000));
    return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
  }
  /** Cuánto lleva esperando una solicitud («ahora», «hace 4 min»). */
  sinceLabel(value: string): string {
    const minutes = Math.max(0, Math.floor((this.nowTick() - new Date(value).getTime()) / 60000));
    return minutes < 1 ? 'ahora' : minutes < 60 ? `hace ${minutes} min` : `hace ${Math.floor(minutes / 60)} h`;
  }
  money(value: number): string {
    return `S/ ${Number(value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }
  submittedItems(table: TableView): OrderItemView[] {
    return (
      (table.sessionId ? this.tableOrders()[table.sessionId]?.items : [])?.filter(
        (item) => item.orderStatus !== 'DRAFT',
      ) || []
    );
  }
  activeGuestCountFor(table: TableView): number {
    return table.sessionId
      ? this.tableOrders()[table.sessionId]?.guests.filter((person) => person.active).length || 0
      : 0;
  }
  occupancyPercent(table: TableView): number {
    return Math.min(
      100,
      Math.round((this.activeGuestCountFor(table) / Math.max(table.capacity, 1)) * 100),
    );
  }
  countStatus(table: TableView, status: string): number {
    return this.submittedItems(table)
      .filter((item) => item.status === status)
      .reduce((sum, item) => sum + item.quantity, 0);
  }
  hasStatus(table: TableView, status: string): boolean {
    return this.countStatus(table, status) > 0;
  }
  tableOrderStage(table: TableView): number {
    if (this.hasStatus(table, 'READY')) return 3;
    if (this.hasStatus(table, 'PREPARING')) return 2;
    if (this.hasStatus(table, 'SUBMITTED')) return 1;
    return this.hasStatus(table, 'DELIVERED') ? 4 : 1;
  }
  tableOrderProgressLabel(table: TableView): string {
    return (
      { 1: 'En cocina', 2: 'En preparación', 3: 'Listo para servir', 4: 'Servido' } as Record<
        number,
        string
      >
    )[this.tableOrderStage(table)];
  }
  orderItemCount(): number {
    return this.billableItems().reduce((sum, item) => sum + item.quantity, 0);
  }
  reservationFor(table: TableView): ReservationView | undefined {
    // Solo la reserva cercana (de 30 min atrás a 3 h adelante), como antes de ampliar la búsqueda.
    const now = Date.now();
    return this.reservations().find((item) => {
      const minutes = (new Date(item.reservedAt).getTime() - now) / 60000;
      return item.tableId === table.id && minutes >= -30 && minutes <= 180;
    });
  }
  guestInitials(name: string): string {
    return name
      .trim()
      .split(/\s+/)
      .map((part) => part[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }
  guestName(item: OrderItemView): string {
    if (!item.guestId) return `Mesero${item.createdByName ? ' · ' + item.createdByName : ''}`;
    return (
      this.order()?.guests.find((person) => person.id === item.guestId)?.displayName || 'Comensal'
    );
  }
  async displayQr(table: TableView): Promise<void> {
    const link = `${this.publicBaseUrl()}/diner?qr=${table.qrPublicId}`;
    const dataUrl = await QRCode.toDataURL(link, {
      width: 280,
      margin: 2,
      errorCorrectionLevel: 'M',
    });
    this.qrPreview.set({ label: table.label, link, dataUrl, table });
  }
  copyQrLink(): void {
    const preview = this.qrPreview();
    if (!preview) return;
    navigator.clipboard
      .writeText(preview.link)
      .then(() => this.feedback(`Enlace de ${preview.label} copiado.`))
      .catch(() => this.feedback('No se pudo copiar el enlace.', 'error'));
  }
  requestActionLabel(request: ServiceRequestView): string {
    if (request.type === 'BILL')
      return request.status === 'PENDING' ? 'Preparar cuenta' : 'Ver cuenta';
    return request.status === 'PENDING' ? 'Voy' : 'En camino';
  }
  activeBill(): ServiceRequestView | null {
    return (
      this.requests().find(
        (request) => request.type === 'BILL' && request.sessionId === this.selected()?.sessionId,
      ) || null
    );
  }
  handle(request: ServiceRequestView): void {
    // Un llamado ya tomado no necesita «resolver»: se cierra solo al actuar en la mesa o a los 5 minutos.
    // Si la mesa insistió («Aún no»), «Voy de nuevo» lo vuelve a tomar y reinicia el reloj.
    if (request.type === 'WAITER_CALL' && request.status === 'IN_PROGRESS' && !(request.escalation || 0)) {
      const table = this.tables().find((item) => item.sessionId === request.sessionId);
      if (table) this.select(table);
      return;
    }
    if (request.type === 'BILL' && request.status === 'IN_PROGRESS') {
      this.openRequestTable(request);
      return;
    }
    const status = request.status === 'PENDING' || (request.type === 'WAITER_CALL' && (request.escalation || 0) > 0) ? 'IN_PROGRESS' : 'RESOLVED';
    this.api.patch(`/staff/service-requests/${request.id}`, { status }).subscribe({
      next: () => {
        this.feedback(
          request.type === 'BILL'
            ? 'Cuenta en preparación; el consumo quedó congelado.'
            : status === 'IN_PROGRESS'
              ? `Le avisamos a ${request.tableLabel} que vas en camino.`
              : 'Solicitud resuelta.',
        );
        this.refresh();
        if (request.type === 'BILL') this.openRequestTable(request);
      },
      error: (err) =>
        this.feedback(err.error?.message || 'No se pudo actualizar la solicitud.', 'error'),
    });
  }
  private openRequestTable(request: ServiceRequestView): void {
    const table = this.tables().find((item) => item.sessionId === request.sessionId);
    if (table) {
      this.select(table);
      this.checkoutOpen.set(true);
    }
  }
  async approveTransfer(transfer: TableTransferView): Promise<void> {
    const accepted = await this.dialog.confirm({
      title: 'Aprobar cambio de mesa',
      message: `${transfer.displayName} pasará de ${transfer.sourceTableLabel} a ${transfer.destinationTableLabel} junto con sus consumos.`,
      confirmLabel: 'Aprobar cambio',
      tone: 'info',
      icon: '⇄',
    });
    if (!accepted) return;
    this.api.post(`/staff/table-transfers/${transfer.id}/approve`, {}).subscribe({
      next: () => {
        this.feedback(
          `${transfer.displayName} fue trasladado a ${transfer.destinationTableLabel}.`,
        );
        this.refresh();
        this.reloadSelected();
      },
      error: (err: any) =>
        this.feedback(err.error?.message || 'No se pudo aprobar el cambio.', 'error'),
    });
  }
  async rejectTransfer(transfer: TableTransferView): Promise<void> {
    const reason = await this.dialog.prompt({
      title: 'Rechazar cambio de mesa',
      message: `Indica por qué ${transfer.displayName} no puede cambiarse a ${transfer.destinationTableLabel}.`,
      inputLabel: 'Motivo del rechazo',
      initialValue: 'La mesa de destino no está disponible',
      confirmLabel: 'Rechazar solicitud',
      tone: 'warning',
      icon: '!',
    });
    if (reason === null) return;
    this.api.patch(`/staff/table-transfers/${transfer.id}/reject`, { reason }).subscribe({
      next: () => {
        this.feedback('Solicitud de cambio rechazada.');
        this.refresh();
      },
      error: (err: any) =>
        this.feedback(err.error?.message || 'No se pudo rechazar el cambio.', 'error'),
    });
  }
  deliver(item: OrderItemView): void {
    this.confirmServed([item], this.selected()?.label || 'la mesa');
  }
  serveReady(ready: ReadyTableAlert): void {
    this.confirmServed(ready.items, ready.table.label);
  }
  // ——— Comandas impresas (estaciones sin pantalla de cocina) ———
  readonly servingRound = signal<string | null>(null);
  /** Una fila por ronda con comanda: cómo salió en cada estación y si quedan platos por servir. */
  readonly printedRounds = computed(() => {
    const order = this.order();
    if (!order) return [];
    const rounds = new Map<string, { orderId: string; round: number; pending: number; jobs: PrintJobView[] }>();
    for (const item of order.items) {
      if (!item.printed || item.orderStatus === 'DRAFT') continue;
      const round = rounds.get(item.orderId) || { orderId: item.orderId, round: item.roundNumber, pending: 0, jobs: [] };
      if (item.status !== 'DELIVERED' && item.status !== 'CANCELLED') round.pending++;
      rounds.set(item.orderId, round);
    }
    // La comanda más reciente de cada estación (una reimpresión reemplaza a la anterior).
    for (const job of order.printJobs || []) {
      const round = rounds.get(job.orderId);
      if (!round) continue;
      round.jobs = [...round.jobs.filter((other) => other.stationName !== job.stationName), job];
    }
    return [...rounds.values()].sort((a, b) => b.round - a.round).map((round) => {
      const failedJob = round.jobs.find((job) => job.status === 'FAILED') || null;
      return { ...round, failedJob, failed: !!failedJob };
    });
  });
  jobLabel(job: PrintJobView): string {
    return job.status === 'PRINTED' ? 'impresa' : job.status === 'FAILED' ? 'no salió' : 'imprimiendo…';
  }
  retryPrint(job: PrintJobView): void {
    this.api.post(`/staff/print-jobs/${job.id}/retry`, {}).subscribe({
      next: () => { this.feedback('Volviendo a imprimir la comanda…'); const id = this.selected()?.sessionId; if (id) this.loadOrder(id); },
      error: (err) => this.feedback(err.error?.message || 'No se pudo reintentar.', 'error'),
    });
  }
  reprint(orderId: string): void {
    this.api.post(`/staff/orders/${orderId}/reprint`, {}).subscribe({
      next: () => { this.feedback('Reimprimiendo la comanda…'); const id = this.selected()?.sessionId; if (id) this.loadOrder(id); },
      error: (err) => this.feedback(err.error?.message || 'No se pudo reimprimir.', 'error'),
    });
  }
  /** Con comanda impresa el timbre avisa en cocina; el mesero marca servida toda la ronda con un toque. */
  serveRound(orderId: string): void {
    if (this.servingRound()) return;
    this.servingRound.set(orderId);
    this.api.post<OrderSnapshot>(`/staff/orders/${orderId}/deliver`, {}).subscribe({
      next: (value) => { this.servingRound.set(null); this.applySnapshot(value); this.order.set(value); this.feedback('Ronda servida.'); },
      error: (err) => { this.servingRound.set(null); this.feedback(err.error?.message || 'No se pudo marcar como servida.', 'error'); },
    });
  }
  isServing(itemId: string): boolean {
    return this.servingItemIds().has(itemId);
  }
  readyServing(ready: ReadyTableAlert): boolean {
    return ready.items.some((item) => this.isServing(item.id));
  }
  private async confirmServed(items: OrderItemView[], tableLabel: string): Promise<void> {
    if (!items.length || items.some((item) => this.isServing(item.id))) return;
    if (!['WAITER', 'BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role())) {
      this.feedback(
        'Esta sesión pertenece a cocina. Ingresa con un usuario mesero para confirmar la entrega.',
        'error',
      );
      return;
    }
    const quantity = items.reduce((sum, item) => sum + item.quantity, 0);
    const accepted = await this.dialog.confirm({
      title: quantity === 1 ? 'Confirmar plato servido' : 'Confirmar platos servidos',
      message: `Confirma que llevaste a ${tableLabel}: ${items.map((item) => `${item.quantity}× ${item.name}`).join(', ')}.`,
      confirmLabel: quantity === 1 ? 'Confirmar servido' : 'Confirmar servidos',
      tone: 'success',
      icon: '✓',
    });
    if (!accepted) return;
    const ids = items.map((item) => item.id);
    this.servingItemIds.update((current) => new Set([...current, ...ids]));
    from(items)
      .pipe(
        concatMap((item) =>
          this.api.patch<OrderSnapshot>(`/staff/order-items/${item.id}/status`, {
            status: 'DELIVERED',
          }),
        ),
        last(),
        finalize(() =>
          this.servingItemIds.update(
            (current) => new Set([...current].filter((id) => !ids.includes(id))),
          ),
        ),
      )
      .subscribe({
        next: (value) => {
          this.applySnapshot(value);
          if (this.selected()?.sessionId === value.sessionId) this.order.set(value);
          this.sound.haptic();
          this.feedback(
            quantity === 1 ? 'Plato servido en la mesa.' : 'Platos servidos en la mesa.',
          );
          this.refresh();
        },
        error: (err) => {
          this.refresh();
          this.reloadSelected();
          this.feedback(
            err.status === 409
              ? 'El estado del plato cambió. Actualizamos la mesa para mostrar la información vigente.'
              : err.error?.message || 'No se pudo confirmar el servicio.',
            'error',
          );
        },
      });
  }
  canCancel(): boolean {
    return !!this.selected()?.requiresReview || !this.billableItems().length;
  }
  /** Platos enviados que siguen en la cuenta; los anulados quedan solo como registro. */
  private billableItems(): OrderItemView[] {
    return (this.order()?.items || []).filter(
      (item) => item.orderStatus !== 'DRAFT' && item.status !== 'CANCELLED',
    );
  }
  isManager(): boolean {
    return ['BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role());
  }
  /** Salón y cocina anulan lo que aún no se prepara; un administrador puede anular en cualquier estado. */
  canVoid(item: OrderItemView): boolean {
    return (
      item.orderStatus !== 'DRAFT' &&
      item.status !== 'CANCELLED' &&
      activeBillFrozen(this.activeBill()) === false &&
      (item.status === 'SUBMITTED' || this.isManager())
    );
  }
  openComposer(): void {
    const table = this.selected();
    if (table?.sessionId) this.composer.set({ sessionId: table.sessionId, label: table.label });
  }
  takeOrderAtFreeTable(table: TableView): void {
    if (this.openingTable()) return;
    this.openingTable.set(true);
    this.api.post<{ sessionId: string }>(`/staff/tables/${table.id}/open`, {}).subscribe({
      next: (value) => {
        this.openingTable.set(false);
        this.qrPreview.set(null);
        this.refresh();
        this.composer.set({ sessionId: value.sessionId, label: table.label });
      },
      error: (err) => {
        this.openingTable.set(false);
        this.feedback(err.error?.message || 'No se pudo abrir la mesa.', 'error');
        this.refresh();
      },
    });
  }
  onRoundSent(snapshot: OrderSnapshot, label: string): void {
    this.composer.set(null);
    this.applySnapshot(snapshot);
    if (this.selected()?.sessionId === snapshot.sessionId) this.order.set(snapshot);
    this.feedback(`Pedido de ${label} enviado a cocina.`);
    this.printer.print('COMANDA', `${label} · enviada a cocina`);
    this.refresh();
  }
  confirmVoid(request: VoidRequest): void {
    const item = this.voiding();
    if (!item || this.voidBusy()) return;
    this.voidBusy.set(true);
    this.api.post<OrderSnapshot>(`/staff/order-items/${item.id}/void`, request).subscribe({
      next: (value) => {
        this.ownVoidUntil = Date.now() + 3000;
        this.voidBusy.set(false);
        this.voiding.set(null);
        this.applySnapshot(value);
        if (this.selected()?.sessionId === value.sessionId) this.order.set(value);
        this.feedback(`${request.quantity}× ${item.name} anulado.`);
        this.refresh();
      },
      error: (err) => {
        this.voidBusy.set(false);
        this.feedback(err.error?.message || 'No se pudo anular el producto.', 'error');
        this.reloadSelected();
      },
    });
  }
  async reactivateSession(): Promise<void> {
    const session = this.selected()?.sessionId;
    if (!session) return;
    const accepted = await this.dialog.confirm({
      title: 'Reactivar atención',
      message:
        'Confirma que revisaste el consumo anterior. El QR volverá a permitir el ingreso de comensales.',
      confirmLabel: 'Reactivar atención',
      tone: 'warning',
      icon: '↻',
    });
    if (!accepted) return;
    this.api.post(`/staff/sessions/${session}/reactivate`, {}).subscribe({
      next: () => {
        this.feedback('Atención reactivada. El QR vuelve a estar disponible.');
        this.refresh();
        this.loadOrder(session);
      },
      error: (err: any) =>
        this.feedback(err.error?.message || 'No se pudo reactivar la atención.', 'error'),
    });
  }
  transferHost(): void {
    const session = this.selected()?.sessionId;
    if (!session || !this.hostGuestId) return;
    this.api.post(`/staff/sessions/${session}/host`, { guestId: this.hostGuestId }).subscribe({
      next: () => {
        this.feedback('Anfitrión actualizado.');
        this.loadOrder(session);
      },
      error: (err: any) =>
        this.feedback(err.error?.message || 'No se pudo cambiar el anfitrión.', 'error'),
    });
  }
  activeGuestCount(): number {
    return this.order()?.guests.filter((person) => person.active).length || 0;
  }
  async retireGuest(guestId: string, name: string): Promise<void> {
    const session = this.selected()?.sessionId;
    if (!session) return;
    const accepted = await this.dialog.confirm({
      title: 'Retirar comensal',
      message: `${name} dejará de aparecer como activo. Sus consumos enviados permanecerán en la cuenta de la mesa.`,
      confirmLabel: 'Retirar',
      tone: 'warning',
      icon: '−',
    });
    if (!accepted) return;
    this.api
      .post(`/staff/sessions/${session}/guests/${guestId}/retire`, {
        reason: 'Retirado por el mesero desde la gestión de mesa',
      })
      .subscribe({
        next: () => {
          this.feedback(`${name} fue retirado de la mesa.`);
          this.loadOrder(session);
          this.refresh();
        },
        error: (err: any) =>
          this.feedback(err.error?.message || 'No se pudo retirar al comensal.', 'error'),
      });
  }
  async cancelSession(): Promise<void> {
    const session = this.selected()?.sessionId;
    const stale = !!this.selected()?.requiresReview;
    if (!session) return;
    const accepted = await this.dialog.confirm({
      title: 'Cancelar atención',
      message: stale
        ? 'Se liberará la mesa y los consumos anteriores quedarán en el historial como cancelados.'
        : 'Se liberará la mesa y los productos todavía no enviados serán descartados.',
      confirmLabel: 'Cancelar atención',
      tone: 'danger',
      icon: '!',
    });
    if (!accepted) return;
    this.api
      .post(`/staff/sessions/${session}/cancel`, {
        reason: stale
          ? 'Atención antigua revisada y cancelada por el personal'
          : 'Atención cancelada por el mesero',
      })
      .subscribe({
        next: () => {
          this.feedback('Atención cancelada y mesa liberada.');
          this.order.set(null);
          this.selected.set(null);
          this.refresh();
        },
        error: (err) =>
          this.feedback(err.error?.message || 'No se pudo cancelar la atención.', 'error'),
      });
  }
  statusLabel(status: string): string {
    return (
      (
        {
          DRAFT: 'Seleccionando',
          SUBMITTED: 'En cocina',
          PREPARING: 'Preparando',
          READY: 'Listo para servir',
          DELIVERED: 'Servido en mesa',
          CANCELLED: 'Anulado',
        } as Record<string, string>
      )[status] || status
    );
  }
  statusClass(status: string): string {
    return (
      (
        {
          DRAFT: 'neutral',
          SUBMITTED: 'neutral',
          PREPARING: 'preparing',
          READY: 'ready',
          DELIVERED: 'delivered',
          CANCELLED: 'review',
        } as Record<string, string>
      )[status] || 'neutral'
    );
  }
  ngOnDestroy(): void { this.stopResync?.();
    document.body.classList.remove('zone-tinted');
    if (this.opsTimer) window.clearInterval(this.opsTimer);
    if (this.clockTimer) window.clearInterval(this.clockTimer);
    this.unwatch?.();
  }
}
