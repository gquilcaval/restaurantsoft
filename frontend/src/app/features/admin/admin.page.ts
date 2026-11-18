import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ServiceRequestView, TableView } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ThemeService } from '../../core/theme/theme.service';
import { CountUpDirective } from '../../shared/ui/count-up.directive';
import { GoalBarComponent } from '../../shared/ui/goal-bar.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { MEDALS, TeamRanking } from '../../shared/team/team-medals';

interface Settings { name: string; logoUrl: string | null; primaryColor: string; defaultTheme: 'LIGHT' | 'DARK' | 'SYSTEM'; }
interface DashboardView {
  totalTables: number; openTables: number; activeGuests: number; ordersToday: number; todayRevenue: number; averageTicket: number;
  sales: { day: string; total: number }[];
  itemStates: { queued: number; preparing: number; ready: number; delivered: number };
}
interface LiveSummary {
  revenue: number; payments: number; averageTicket: number; tables: number; busyTables: number; guests: number; inKitchen: number;
  kitchenMinutes: number | null; topToday: { name: string; quantity: number }[]; hourly: { hour: number; amount: number }[];
  live: { tableId: string; ready: number; calling: boolean; bill: boolean }[];
  lastWeekRevenue: number; lastWeekTicket: number; ratingAverage: number | null; ratingsToday: number; lastComment: string | null;
}
interface GoalProgress { goal: number; revenue: number; percent: number | null; projected: number | null; reached: boolean; }
interface OpsAlert { key: string; tone: string; title: string; body: string; }
interface WaitlistBoard { entries: { customerName: string; partySize: number; status: string; createdAt: string }[]; freeTables: { label: string; capacity: number }[]; }
interface Reservation { customerName: string; partySize: number; reservedAt: string; status: string; tableLabel: string | null; }
interface AttentionItem { key: string; tone: 'danger' | 'warn' | 'info' | 'ok'; icon: string; title: string; detail: string; action: string; go: () => void; }
type Period = 'today' | 'week' | 'month';

const EMPTY: DashboardView = { totalTables: 0, openTables: 0, activeGuests: 0, ordersToday: 0, todayRevenue: 0, averageTicket: 0, sales: [],
  itemStates: { queued: 0, preparing: 0, ready: 0, delivered: 0 } };

/**
 * Resumen («centro de mando»): cuánto llevas hoy frente a la meta, qué necesita tu atención ahora mismo
 * (con un botón para resolverlo), el salón y la cocina en vivo, lo más vendido y el equipo.
 */
import { initialsOf, waiterHue } from '../waiter/served-by';
import { WaiterLoadComponent } from '../waiter/waiter-load.component';
@Component({
  imports: [AppShellComponent, FormsModule, CountUpDirective, GoalBarComponent, WaiterLoadComponent],
  templateUrl: './admin.page.html',
  styleUrls: ['./resumen-waiters.scss', './admin-summary.scss', './admin-summary-panels.scss', './resumen.scss', './resumen-cards.scss', './resumen-live.scss', './resumen-extra.scss'],
})
export class AdminPage implements OnInit, OnDestroy {
  readonly theme = inject(ThemeService);
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly realtime = inject(RealtimeService);
  private unwatch?: () => void;
  private stopResync?: () => void;
  private refreshTimer?: number;
  private pollTimer?: number;
  readonly medals = MEDALS;

  readonly dashboard = signal<DashboardView>(EMPTY);
  readonly summary = signal<LiveSummary | null>(null);
  readonly goal = signal<GoalProgress | null>(null);
  readonly alerts = signal<OpsAlert[]>([]);
  readonly requests = signal<ServiceRequestView[]>([]);
  readonly waitlist = signal<WaitlistBoard | null>(null);
  readonly reservations = signal<Reservation[]>([]);
  readonly team = signal<TeamRanking | null>(null);
  readonly tables = signal<TableView[]>([]);
  readonly month = signal<{ total: number; days: { day: string; amount: number }[] } | null>(null);
  readonly period = signal<Period>('today');
  readonly loading = signal(true);

  // ——— Saludo ———
  readonly greeting = (() => {
    const hour = new Date().getHours();
    return hour < 12 ? 'Buenos días' : hour < 19 ? 'Buenas tardes' : 'Buenas noches';
  })();
  readonly firstName = this.auth.userName().split(' ')[0];
  readonly todayLabel = (() => {
    const text = new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());
    return text.charAt(0).toUpperCase() + text.slice(1);
  })();
  readonly weekdayShort = new Intl.DateTimeFormat('es-PE', { weekday: 'short' }).format(new Date()).replace('.', '');
  readonly inService = computed(() => (this.summary()?.busyTables || 0) > 0 || (this.summary()?.inKitchen || 0) > 0);

  // ——— Tarjeta principal según el período ———
  readonly heroValue = computed(() => {
    if (this.period() === 'week') return this.dashboard().sales.reduce((sum, sale) => sum + Number(sale.total), 0);
    if (this.period() === 'month') return this.month()?.total || 0;
    return Number(this.summary()?.revenue || 0);
  });
  readonly heroLabel = computed(() => ({ today: 'Ventas de hoy', week: 'Ventas de los últimos 7 días', month: 'Ventas de este mes' })[this.period()]);
  /** Variación frente al mismo día de la semana pasada, hasta esta misma hora. */
  readonly trend = computed(() => {
    const value = this.summary();
    if (this.period() !== 'today' || !value || !Number(value.lastWeekRevenue)) return null;
    return Math.round(((Number(value.revenue) - Number(value.lastWeekRevenue)) / Number(value.lastWeekRevenue)) * 100);
  });
  readonly goalPercent = computed(() => Math.min(100, Number(this.goal()?.percent || 0)));
  readonly bars = computed(() => {
    if (this.period() === 'week') {
      const list = this.dashboard().sales.map((sale) => ({ label: this.shortDay(sale.day), value: Number(sale.total) }));
      return this.normalize(list, list.length - 1);
    }
    if (this.period() === 'month') {
      const list = (this.month()?.days || []).map((day) => ({ label: day.day.slice(8), value: Number(day.amount) }));
      return this.normalize(list, list.length - 1);
    }
    const hourly = new Map((this.summary()?.hourly || []).map((item) => [item.hour, Number(item.amount)]));
    const now = new Date().getHours();
    const list = Array.from({ length: 12 }, (_, index) => 11 + index).map((hour) => ({ label: `${hour}h`, value: hourly.get(hour) || 0 }));
    return this.normalize(list, list.findIndex((item) => item.label === `${now}h`));
  });
  readonly bestDay = computed(() => {
    const list = this.bars();
    const best = [...list].sort((a, b) => b.value - a.value)[0];
    return best && best.value ? best : null;
  });

  // ——— Cifras compactas ———
  readonly occupancy = computed(() => {
    const value = this.summary();
    return value && value.tables ? Math.round((value.busyTables / value.tables) * 100) : 0;
  });
  readonly ticketTrend = computed(() => {
    const value = this.summary();
    if (!value || !Number(value.lastWeekTicket) || !Number(value.averageTicket)) return null;
    return Math.round(((Number(value.averageTicket) - Number(value.lastWeekTicket)) / Number(value.lastWeekTicket)) * 100);
  });

  // ——— Necesita tu atención ———
  readonly attention = computed<AttentionItem[]>(() => {
    const items: AttentionItem[] = [];
    for (const alert of this.alerts()) {
      const stock = alert.key.startsWith('STOCK');
      items.push({ key: alert.key, tone: stock ? 'warn' : 'danger', icon: stock ? '📦' : '⏰', title: alert.title, detail: alert.body,
        action: stock ? 'Ver compras' : 'Ver cocina', go: () => this.go(stock ? '/inventory' : '/kitchen') });
    }
    const pending = this.requests().filter((request) => request.status !== 'RESOLVED');
    const calls = pending.filter((request) => request.type === 'WAITER_CALL');
    const bills = pending.filter((request) => request.type === 'BILL');
    if (calls.length) items.push({ key: 'calls', tone: 'danger', icon: '✋', title: `${calls.length} ${calls.length === 1 ? 'mesa llama' : 'mesas llaman'} al mesero`,
      detail: calls.map((request) => request.tableLabel).join(', '), action: 'Ver mesas', go: () => this.go('/waiter') });
    if (bills.length) items.push({ key: 'bills', tone: 'info', icon: '🧾', title: `${bills.length} ${bills.length === 1 ? 'mesa pide' : 'mesas piden'} la cuenta`,
      detail: bills.map((request) => request.tableLabel + (request.note ? ` · ${request.note}` : '')).join(' — '), action: 'Cobrar', go: () => this.go('/waiter') });
    const waiting = (this.waitlist()?.entries || []).filter((entry) => entry.status === 'WAITING');
    if (waiting.length) {
      const first = waiting[0];
      const free = this.waitlist()?.freeTables.find((table) => table.capacity >= first.partySize);
      items.push({ key: 'waitlist', tone: 'info', icon: '👥', title: `${waiting.length} ${waiting.length === 1 ? 'grupo espera' : 'grupos esperan'} mesa${free ? ` · ${free.label} libre` : ''}`,
        detail: `${first.customerName} (${first.partySize}) espera hace ${this.minutesSince(first.createdAt)} min`, action: 'Sentar',
        go: () => this.go('/reservations', 'waitlist') });
    }
    const next = this.reservations().find((reservation) => ['PENDING', 'CONFIRMED'].includes(reservation.status));
    if (next) items.push({ key: 'reservation', tone: 'ok', icon: '📅', title: `Reserva a las ${next.reservedAt.slice(11, 16)} · ${next.customerName}`,
      detail: `${next.partySize} personas${next.tableLabel ? ' · ' + next.tableLabel : ''}`, action: 'Ver', go: () => this.go('/reservations', 'reservations') });
    const ratings = this.summary()?.ratingsToday || 0;
    if (ratings) items.push({ key: 'ratings', tone: 'ok', icon: '💬', title: `${ratings} ${ratings === 1 ? 'calificación nueva' : 'calificaciones nuevas'} hoy`,
      detail: this.summary()?.lastComment ? `«${this.summary()!.lastComment}»` : 'Mira lo que dicen tus clientes', action: 'Leer', go: () => this.go('/reports') });
    const order = { danger: 0, warn: 1, info: 2, ok: 3 };
    return items.sort((a, b) => order[a.tone] - order[b.tone]).slice(0, 5);
  });
  readonly urgentCount = computed(() => this.attention().filter((item) => item.tone === 'danger' || item.tone === 'warn').length);

  // ——— Salón, cocina y equipo ———
  readonly floor = computed(() => {
    const live = new Map((this.summary()?.live || []).map((item) => [item.tableId, item]));
    return this.tables().map((table) => {
      const info = live.get(table.id);
      const state = info?.calling ? 'call' : info?.bill ? 'bill' : info?.ready ? 'ready' : table.status === 'OPEN' ? 'busy' : 'free';
      return {
        id: table.id, label: this.shortLabel(table.label), state, servedName: table.servedByName || '',
        servedInitials: table.status === 'OPEN' ? (table.servedById ? initialsOf(table.servedByName) : '?') : '',
        hue: waiterHue(table.servedById),
      };
    });
  });
  readonly floorSummary = computed(() => {
    const list = this.floor();
    const count = (state: string) => list.filter((table) => table.state === state).length;
    const parts = [
      count('call') && `${count('call')} llama`, count('bill') && `${count('bill')} pide cuenta`, count('ready') && `${count('ready')} con plato listo`,
    ].filter(Boolean);
    return parts.length ? parts.join(' · ') : `${list.filter((table) => table.state !== 'free').length} ocupadas de ${list.length}`;
  });
  readonly lateStation = computed(() => this.alerts().find((alert) => alert.key.startsWith('STATION'))?.title || null);
  readonly maxTop = computed(() => Math.max(1, ...(this.summary()?.topToday || []).map((item) => item.quantity)));
  readonly podium = computed(() => {
    const list = this.team()?.waiters || [];
    return [list[1], list[0], list[2]].map((member, index) => ({ member, place: [2, 1, 3][index] })).filter((item) => item.member);
  });

  restaurantName = 'RestOS';

  ngOnInit(): void {
    this.refresh();
    this.loadName();
    this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => this.refresh(false));
    const branch = this.auth.branchId();
    if (branch)
      this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, () => {
        window.clearTimeout(this.refreshTimer);
        this.refreshTimer = window.setTimeout(() => this.refresh(false), 900);
      });
    this.pollTimer = window.setInterval(() => this.refresh(false), 60000);
  }

  ngOnDestroy(): void { this.stopResync?.();
    this.unwatch?.();
    window.clearTimeout(this.refreshTimer);
    if (this.pollTimer) window.clearInterval(this.pollTimer);
  }

  /** Todo el tablero se arma con datos reales de la sucursal; cada bloque falla de forma independiente. */
  refresh(showLoading = true): void {
    if (showLoading) this.loading.set(true);
    const quiet = { error: () => undefined };
    this.api.get<DashboardView>('/admin/dashboard').subscribe({ next: (value) => { this.dashboard.set(value); this.loading.set(false); }, error: () => this.loading.set(false) });
    this.api.get<LiveSummary>('/admin/live/summary').subscribe({ next: (value) => this.summary.set(value), ...quiet });
    this.api.get<GoalProgress>('/staff/team/goal').subscribe({ next: (value) => this.goal.set(value), ...quiet });
    this.api.get<{ alerts: OpsAlert[] }>('/staff/ops/stations').subscribe({ next: (value) => this.alerts.set(value.alerts), ...quiet });
    this.api.get<ServiceRequestView[]>('/staff/service-requests').subscribe({ next: (value) => this.requests.set(value), ...quiet });
    this.api.get<WaitlistBoard>('/staff/waitlist').subscribe({ next: (value) => this.waitlist.set(value), ...quiet });
    this.api.get<Reservation[]>('/staff/reservations/upcoming?hours=3').subscribe({ next: (value) => this.reservations.set(value), ...quiet });
    this.api.get<TeamRanking>('/staff/team/leaderboard?period=week').subscribe({ next: (value) => this.team.set(value), ...quiet });
    this.api.get<TableView[]>('/staff/tables').subscribe({ next: (value) => this.tables.set(value), ...quiet });
  }

  setPeriod(period: Period): void {
    this.period.set(period);
    if (period === 'month' && !this.month()) {
      const now = new Date();
      const pad = (value: number) => String(value).padStart(2, '0');
      const from = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-01`;
      const to = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
      this.api.get<{ totals: { collected: number }; days: { day: string; amount: number }[] }>(`/admin/reports/sales?from=${from}&to=${to}&page=0`).subscribe({
        next: (value) => this.month.set({ total: Number(value.totals.collected), days: value.days }),
        error: () => this.month.set({ total: 0, days: [] }),
      });
    }
  }

  go(path: string, hostTab?: 'waitlist' | 'reservations'): void {
    if (hostTab) {
      try { localStorage.setItem('restos_host_tab', hostTab); } catch { /* sin almacenamiento */ }
    }
    void this.router.navigateByUrl(path);
  }

  // ——— Utilidades ———
  private normalize(list: { label: string; value: number }[], current: number) {
    const max = Math.max(1, ...list.map((item) => item.value));
    return list.map((item, index) => ({ ...item, ratio: item.value / max, current: index === current }));
  }
  money(value: number): string {
    return `S/ ${Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
  }
  shortDay(value: string): string {
    return new Intl.DateTimeFormat('es-PE', { weekday: 'short' }).format(new Date(value + 'T12:00:00')).replace('.', '');
  }
  shortLabel(label: string): string {
    const number = label.replace(/[^0-9]/g, '');
    return label.toLowerCase().startsWith('terraza') ? `T${number}` : number || label.slice(0, 3);
  }
  initials(name: string): string {
    return name.split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase();
  }
  minutesSince(value: string): number {
    return Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000));
  }

  // ——— Personalización ———
  private loadName(): void { this.api.get<Settings>('/admin/settings').subscribe({ next: (settings) => (this.restaurantName = settings.name), error: () => undefined }); }
}
