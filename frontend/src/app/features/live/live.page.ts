import { CurrencyPipe, DatePipe } from '@angular/common';
import {
  Component,
  computed,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { TableView, ZoneView } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeEvent, RealtimeService } from '../../core/realtime/realtime.service';
import { FloorPlan } from '../../shared/floor/floor.models';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { CountUpDirective } from '../../shared/ui/count-up.directive';
import { SoundService } from '../../shared/ui/sound.service';
import { FloorTableState, FloorViewComponent } from '../waiter/floor-view.component';
import { WaiterLoadComponent } from '../waiter/waiter-load.component';
import { GoalBarComponent } from '../../shared/ui/goal-bar.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { TeamBoardComponent } from '../../shared/team/team-board.component';

interface ProductCount {
  name: string;
  quantity: number;
}
interface HourAmount {
  hour: number;
  amount: number;
}
interface LiveTable {
  tableId: string;
  sessionId: string;
  total: number;
  guests: number;
  ready: number;
  cooking: boolean;
  calling: boolean;
  bill: boolean;
}
interface RecentPayment {
  id: string;
  label: string;
  method: string;
  amount: number;
  paidAt: string;
}
interface LiveSummary {
  revenue: number;
  payments: number;
  averageTicket: number;
  orders: number;
  tables: number;
  busyTables: number;
  guests: number;
  inKitchen: number;
  kitchenMinutes: number | null;
  topHour: ProductCount[];
  topToday: ProductCount[];
  hourly: HourAmount[];
  recent: RecentPayment[];
  live: LiveTable[];
  generatedAt: string;
  lastWeekRevenue: number;
  lastWeekTicket: number;
  ratingAverage: number | null;
  ratingsToday: number;
}
interface TableHeat {
  tableId: string;
  revenue: number;
  turns: number;
  minutes: number;
}
interface Occupancy {
  tableId: string;
  openedAt: string;
  closedAt: string | null;
}
interface DayReplay {
  date: string;
  dayStart: string;
  sessions: Occupancy[];
  hourly: HourAmount[];
}

type Mode = 'live' | 'heat' | 'replay';
type HeatMetric = 'revenue' | 'turns' | 'minutes';

const pad = (value: number) => String(value).padStart(2, '0');
const isoDate = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

/**
 * Modo TV: el pulso del local para una pantalla grande en caja o el celular del dueño. Plano en vivo,
 * ventas que suben, lo más pedido, tiempo de cocina, mapa de calor y repetición del día.
 */
@Component({
  imports: [
    AppShellComponent,
    FloorViewComponent,
    CountUpDirective,
    CurrencyPipe,
    DatePipe,
    FormsModule,
    GoalBarComponent,
    TeamBoardComponent,
    IconComponent,
    WaiterLoadComponent,
  ],
  templateUrl: './live.page.html',
  styleUrls: ['./live-theme.scss', './live.page.scss', './live-kpis.scss', './live-panels.scss', './live-replay.scss'],
})
export class LivePage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);
  private readonly sound = inject(SoundService);
  private readonly screen = viewChild<ElementRef<HTMLElement>>('screen');
  readonly mode = signal<Mode>('live');
  readonly now = signal(Date.now());
  readonly fullscreen = signal(false);
  readonly tables = signal<TableView[]>([]);
  readonly zones = signal<ZoneView[]>([]);
  readonly plan = signal<FloorPlan | null>(null);
  readonly summary = signal<LiveSummary | null>(null);
  readonly freshPayments = signal<Set<string>>(new Set());
  // Mapa de calor
  readonly heatPeriod = signal<'today' | '7' | '30'>('7');
  readonly heatMetric = signal<HeatMetric>('revenue');
  readonly heatRows = signal<TableHeat[]>([]);
  // Repetición
  readonly replayDate = signal(isoDate(new Date()));
  readonly replay = signal<DayReplay | null>(null);
  readonly replayMinute = signal(12 * 60);
  readonly playing = signal(false);
  private playTimer?: number;
  private tickTimer?: number;
  private pollTimer?: number;
  private refreshTimer?: number;
  private unwatch?: () => void;
  private stopResync?: () => void;
  private knownPayments: Set<string> | null = null;

  readonly occupancyPercent = computed(() => {
    const value = this.summary();
    return value && value.tables ? Math.round((value.busyTables / value.tables) * 100) : 0;
  });
  readonly topProducts = computed(() => {
    const value = this.summary();
    if (!value) return { title: 'Lo más pedido', items: [] as ProductCount[] };
    return value.topHour.length
      ? { title: 'Lo más pedido · última hora', items: value.topHour }
      : { title: 'Lo más pedido · hoy', items: value.topToday };
  });
  readonly maxProduct = computed(() =>
    Math.max(1, ...this.topProducts().items.map((item) => item.quantity)),
  );
  readonly salesHours = computed(() => this.hourBars(this.summary()?.hourly || []));
  /** Comparación con el mismo día de la semana pasada, hasta esta misma hora. */
  readonly salesTrend = computed(() => {
    const value = this.summary();
    if (!value || !Number(value.lastWeekRevenue)) return null;
    return Math.round(((Number(value.revenue) - Number(value.lastWeekRevenue)) / Number(value.lastWeekRevenue)) * 100);
  });
  readonly ticketTrend = computed(() => {
    const value = this.summary();
    if (!value || !Number(value.lastWeekTicket) || !Number(value.averageTicket)) return null;
    return Math.round(((Number(value.averageTicket) - Number(value.lastWeekTicket)) / Number(value.lastWeekTicket)) * 100);
  });

  /** Estado de cada mesa en vivo, con el mismo lenguaje visual que ve el mesero. */
  readonly liveStates = computed<FloorTableState[]>(() => {
    const live = new Map((this.summary()?.live || []).map((item) => [item.tableId, item]));
    return this.tables().map((table) => {
      const info = live.get(table.id);
      return {
        table,
        tone: table.requiresReview
          ? 'review'
          : info?.calling
            ? 'call'
            : info?.bill
              ? 'bill'
              : info?.ready
                ? 'ready'
                : table.status === 'OPEN'
                  ? 'busy'
                  : 'free',
        guests: info?.guests || 0,
        total: info?.total || 0,
        readyCount: info?.ready || 0,
        cooking: !!info?.cooking,
        reservedAt: null,
        dimmed: false,
      };
    });
  });

  // ——— Mapa de calor ———
  readonly heatMap = computed(() => {
    const metric = this.heatMetric();
    const rows = this.heatRows();
    const max = Math.max(1, ...rows.map((row) => Number(row[metric]) || 0));
    return Object.fromEntries(
      rows.map((row) => [
        row.tableId,
        {
          ratio: (Number(row[metric]) || 0) / max,
          label: this.heatLabel(metric, row),
          detail:
            metric === 'revenue'
              ? `${row.turns} ${row.turns === 1 ? 'atención' : 'atenciones'}`
              : metric === 'turns'
                ? this.money(row.revenue)
                : `${row.turns} giros`,
        },
      ]),
    );
  });
  readonly heatStates = computed<FloorTableState[]>(() =>
    this.tables().map((table) => ({
      table: { ...table, status: 'FREE', sessionId: null },
      tone: 'free',
      guests: 0,
      total: 0,
      readyCount: 0,
      cooking: false,
      reservedAt: null,
      dimmed: false,
    })),
  );
  /** «¿La terraza rinde?»: ranking de zonas por el indicador elegido y promedio por mesa. */
  readonly zoneRanking = computed(() => {
    const metric = this.heatMetric();
    const rows = new Map(this.heatRows().map((row) => [row.tableId, row]));
    const groups = new Map<
      string,
      { name: string; color: string; total: number; tables: number }
    >();
    for (const table of this.tables()) {
      const zone = this.zones().find((item) => item.id === table.zoneId);
      const key = zone?.id || '';
      const group = groups.get(key) || {
        name: zone?.name || 'Sin zona',
        color: zone?.color || '#7b8794',
        total: 0,
        tables: 0,
      };
      group.total += Number(rows.get(table.id)?.[metric]) || 0;
      group.tables++;
      groups.set(key, group);
    }
    const list = [...groups.values()].map((group) => ({
      ...group,
      perTable: group.total / Math.max(1, group.tables),
    }));
    const best = Math.max(1, ...list.map((group) => group.perTable));
    return list
      .sort((a, b) => b.perTable - a.perTable)
      .map((group) => ({ ...group, ratio: group.perTable / best }));
  });
  readonly bestTable = computed(() => {
    const metric = this.heatMetric();
    const best = [...this.heatRows()].sort((a, b) => Number(b[metric]) - Number(a[metric]))[0];
    if (!best || !Number(best[metric])) return null;
    const table = this.tables().find((item) => item.id === best.tableId);
    return table ? { label: table.label, value: this.heatLabel(metric, best) } : null;
  });
  readonly idleTables = computed(
    () =>
      this.heatRows().filter(
        (row) => !row.turns && this.tables().some((table) => table.id === row.tableId),
      ).length,
  );

  // ——— Repetición del día ———
  readonly replayRange = computed(() => {
    const value = this.replay();
    const first = value?.sessions.length
      ? Math.min(...value.sessions.map((item) => this.minuteOfDay(item.openedAt)))
      : 9 * 60;
    const today = this.replayDate() === isoDate(new Date());
    const last = today ? this.minuteOfDay(new Date(this.now()).toISOString()) : 24 * 60 - 1;
    const start = Math.max(0, Math.floor(first / 60) * 60 - 30);
    return { start, end: Math.max(start + 60, last) };
  });
  readonly replayAt = computed(() => {
    const value = this.replay();
    if (!value) return Date.now();
    return new Date(value.dayStart).getTime() + this.replayMinute() * 60000;
  });
  readonly replayStates = computed<FloorTableState[]>(() => {
    const at = this.replayAt();
    const sessions = this.replay()?.sessions || [];
    return this.tables().map((table) => {
      const active = sessions.find(
        (item) =>
          item.tableId === table.id &&
          new Date(item.openedAt).getTime() <= at &&
          (!item.closedAt || new Date(item.closedAt).getTime() > at),
      );
      return {
        table: {
          ...table,
          status: active ? 'OPEN' : 'FREE',
          sessionId: null,
          openedAt: active?.openedAt || null,
        },
        tone: active ? 'busy' : 'free',
        guests: 0,
        total: 0,
        readyCount: 0,
        cooking: false,
        reservedAt: null,
        dimmed: false,
      };
    });
  });
  private readonly dayFormat = new Intl.DateTimeFormat('es-PE', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  readonly todayLabel = computed(() => this.dayFormat.format(this.now()));
  readonly replayHour = computed(() => Math.floor(this.replayMinute() / 60));
  readonly nowHour = computed(() => new Date(this.now()).getHours());
  readonly replayBusy = computed(
    () => this.replayStates().filter((item) => item.tone === 'busy').length,
  );
  /** Máximo de mesas ocupadas a la vez en cada hora (muestreado cada 10 minutos). */
  readonly occupancyHours = computed(() => {
    const value = this.replay();
    if (!value) return [];
    const { start, end } = this.replayRange();
    const dayStart = new Date(value.dayStart).getTime();
    const revenue = new Map(value.hourly.map((item) => [item.hour, Number(item.amount)]));
    const total = Math.max(1, this.tables().length);
    const hours = [];
    for (let hour = Math.floor(start / 60); hour <= Math.floor(end / 60); hour++) {
      let peak = 0;
      for (let minute = 0; minute < 60; minute += 10) {
        const at = dayStart + (hour * 60 + minute) * 60000;
        const busy = value.sessions.filter(
          (item) =>
            new Date(item.openedAt).getTime() <= at &&
            (!item.closedAt || new Date(item.closedAt).getTime() > at),
        ).length;
        peak = Math.max(peak, busy);
      }
      hours.push({ hour, peak, ratio: peak / total, revenue: revenue.get(hour) || 0 });
    }
    return hours;
  });
  readonly peakHour = computed(() => {
    const hours = this.occupancyHours();
    const best = [...hours].sort((a, b) => b.peak - a.peak || b.revenue - a.revenue)[0];
    return best && best.peak ? best : null;
  });

  ngOnInit(): void {
    this.loadBase();
    this.loadSummary();
    this.loadHeat();
    this.tickTimer = window.setInterval(() => this.now.set(Date.now()), 1000);
    this.pollTimer = window.setInterval(() => this.loadSummary(), 30000);
    this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => { this.loadSummary(); this.loadHeat(); });
    const branch = this.auth.branchId();
    if (branch)
      this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, (event) =>
        this.onEvent(event),
      );
    document.addEventListener('fullscreenchange', this.onFullscreen);
  }

  ngOnDestroy(): void { this.stopResync?.();
    this.unwatch?.();
    [this.tickTimer, this.pollTimer, this.playTimer, this.refreshTimer].forEach(
      (timer) => timer && window.clearInterval(timer),
    );
    document.removeEventListener('fullscreenchange', this.onFullscreen);
  }

  private loadBase(): void {
    this.api
      .get<TableView[]>('/staff/tables')
      .subscribe({ next: (value) => this.tables.set(value), error: () => undefined });
    this.api
      .get<{ zones: ZoneView[] }>('/staff/zones')
      .subscribe({ next: (value) => this.zones.set(value.zones), error: () => undefined });
    this.api
      .get<FloorPlan>('/staff/floor')
      .subscribe({ next: (value) => this.plan.set(value), error: () => undefined });
  }

  private loadSummary(): void {
    this.api.get<LiveSummary>('/admin/live/summary').subscribe({
      next: (value) => {
        const ids = value.recent.map((item) => item.id);
        if (this.knownPayments) {
          const fresh = ids.filter((id) => !this.knownPayments!.has(id));
          if (fresh.length) {
            this.freshPayments.set(new Set(fresh));
            window.setTimeout(() => this.freshPayments.set(new Set()), 4000);
          }
        }
        this.knownPayments = new Set([...(this.knownPayments || []), ...ids]);
        this.summary.set(value);
      },
      error: () => undefined,
    });
  }

  /** Cada evento del salón refresca el tablero (agrupando ráfagas de eventos). */
  private onEvent(event: RealtimeEvent): void {
    this.sound.forEvent(event.type);
    if (event.type === 'FLOOR_UPDATED' || event.type === 'ZONES_UPDATED') this.loadBase();
    window.clearTimeout(this.refreshTimer);
    this.refreshTimer = window.setTimeout(() => {
      this.loadSummary();
      this.api
        .get<TableView[]>('/staff/tables')
        .subscribe({ next: (value) => this.tables.set(value), error: () => undefined });
    }, 700);
  }

  setMode(mode: Mode): void {
    this.mode.set(mode);
    this.stopReplay();
    if (mode === 'heat') this.loadHeat();
    if (mode === 'replay') this.loadReplay();
  }

  // ——— Mapa de calor ———
  setHeatPeriod(period: 'today' | '7' | '30'): void {
    this.heatPeriod.set(period);
    this.loadHeat();
  }

  private loadHeat(): void {
    const to = new Date();
    const from = new Date();
    if (this.heatPeriod() !== 'today') from.setDate(from.getDate() - Number(this.heatPeriod()) + 1);
    this.api
      .get<TableHeat[]>(`/admin/live/heat?from=${isoDate(from)}&to=${isoDate(to)}`)
      .subscribe({ next: (value) => this.heatRows.set(value), error: () => undefined });
  }

  private heatLabel(metric: HeatMetric, row: TableHeat): string {
    if (metric === 'revenue') return this.money(row.revenue);
    if (metric === 'turns') return `${row.turns} ${row.turns === 1 ? 'giro' : 'giros'}`;
    return this.duration(row.minutes);
  }

  zoneValue(total: number): string {
    const metric = this.heatMetric();
    if (metric === 'revenue') return this.money(total);
    if (metric === 'turns') return `${Math.round(total * 10) / 10} giros`;
    return this.duration(total);
  }

  // ——— Repetición ———
  setReplayDate(date: string): void {
    this.replayDate.set(date);
    this.loadReplay();
  }

  private loadReplay(): void {
    this.api.get<DayReplay>(`/admin/live/replay?date=${this.replayDate()}`).subscribe({
      next: (value) => {
        this.replay.set(value);
        this.replayMinute.set(this.replayRange().start);
      },
      error: () => undefined,
    });
  }

  togglePlay(): void {
    if (this.playing()) {
      this.stopReplay();
      return;
    }
    const { start, end } = this.replayRange();
    if (this.replayMinute() >= end) this.replayMinute.set(start);
    this.playing.set(true);
    this.playTimer = window.setInterval(() => {
      const next = this.replayMinute() + 5;
      if (next >= this.replayRange().end) {
        this.replayMinute.set(this.replayRange().end);
        this.stopReplay();
      } else this.replayMinute.set(next);
    }, 110);
  }

  private stopReplay(): void {
    this.playing.set(false);
    if (this.playTimer) window.clearInterval(this.playTimer);
  }

  jumpToHour(hour: number): void {
    this.stopReplay();
    this.replayMinute.set(
      Math.max(this.replayRange().start, Math.min(this.replayRange().end, hour * 60)),
    );
  }

  clockLabel(minute: number): string {
    return `${pad(Math.floor(minute / 60) % 24)}:${pad(minute % 60)}`;
  }

  private minuteOfDay(value: string): number {
    const date = new Date(value);
    return date.getHours() * 60 + date.getMinutes();
  }

  // ——— Pantalla completa ———
  toggleFullscreen(): void {
    const element = this.screen()?.nativeElement;
    if (!element) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void element.requestFullscreen?.().catch(() => undefined);
  }

  private readonly onFullscreen = () => this.fullscreen.set(!!document.fullscreenElement);

  // ——— Utilidades ———
  private hourBars(rows: HourAmount[]) {
    if (!rows.length) return [];
    const first = Math.min(...rows.map((row) => row.hour));
    const last = Math.max(new Date().getHours(), ...rows.map((row) => row.hour));
    const max = Math.max(1, ...rows.map((row) => Number(row.amount)));
    const map = new Map(rows.map((row) => [row.hour, Number(row.amount)]));
    return Array.from({ length: last - first + 1 }, (_, index) => {
      const hour = first + index;
      const amount = map.get(hour) || 0;
      return { hour, amount, ratio: amount / max };
    });
  }

  money(value: number): string {
    return `S/ ${Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
  }

  duration(minutes: number): string {
    const value = Math.round(minutes);
    return value < 60 ? `${value} min` : `${Math.floor(value / 60)} h ${value % 60} min`;
  }

  methodLabel(method: string): string {
    return (
      (
        {
          CASH: 'Efectivo',
          CARD: 'Tarjeta',
          YAPE: 'Yape',
          PLIN: 'Plin',
          TRANSFER: 'Transferencia',
        } as Record<string, string>
      )[method] || method
    );
  }
}
