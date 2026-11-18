import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { businessDate } from './management.models';

interface AuditEvent { id: string; createdAt: string; action: string; summary: string; amount: number | null; userName: string; }
interface AuditResults { events: AuditEvent[]; total: number; page: number; pageSize: number; timezone: string; }
interface ActionCount { action: string; count: number; amount: number; }
interface UserCount { userId: string | null; name: string; count: number; voids: number; voidAmount: number; discounts: number; discountAmount: number; sensitive: number; }
interface AuditSummary { actions: ActionCount[]; users: UserCount[]; timezone: string; }
type Group = 'ALL' | 'MONEY' | 'VOIDS' | 'STOCK' | 'MENU' | 'ACCESS';
type Preset = 'TODAY' | 'YESTERDAY' | 'WEEK' | 'MONTH' | 'CUSTOM';
interface Alert { tone: 'bad' | 'warn' | 'info'; icon: string; title: string; detail: string; userId?: string | null; group?: Group; }

/** Etiquetas legibles para cada tipo de acción registrada por el sistema. */
export const AUDIT_ACTIONS: Record<string, string> = {
  ITEM_VOIDED: 'Anulación', DISCOUNT_APPLIED: 'Descuento', DISCOUNT_REMOVED: 'Descuento retirado',
  PAYMENT_REGISTERED: 'Cobro', SESSION_CLOSED: 'Cuenta cerrada', SESSION_CANCELLED: 'Atención cancelada',
  CASH_OPENED: 'Apertura de caja', CASH_MOVEMENT: 'Movimiento de caja', CASH_CLOSED: 'Cierre de caja',
  AVAILABILITY_CHANGED: 'Agotado / disponible', PRICE_CHANGED: 'Cambio de precio', USER_CREATED: 'Usuario creado',
  USER_UPDATED: 'Usuario modificado', PIN_CHANGED: 'Cambio de PIN', DEVICE_REGISTERED: 'Tablet autorizada',
  DEVICE_REVOKED: 'Tablet desautorizada', PIN_LOGIN: 'Ingreso con PIN', STOCK_PURCHASE: 'Compra de insumos',
  STOCK_WASTE: 'Merma', STOCK_COUNT: 'Conteo de inventario', POINTS_REDEEMED: 'Canje de puntos',
  POINTS_ADJUSTED: 'Ajuste de puntos', RESERVATION_CANCELLED: 'Reserva cancelada',
  ATTENDANCE_ADDED: 'Marca de asistencia agregada', ATTENDANCE_CHANGED: 'Hora de asistencia corregida', ATTENDANCE_VOIDED: 'Marca de asistencia anulada',
  SCHEDULE_CHANGED: 'Horario cambiado', ATTENDANCE_SETTINGS: 'Tolerancia de tardanza'
};
const GROUPS: Record<Exclude<Group, 'ALL'>, string[]> = {
  MONEY: ['PAYMENT_REGISTERED', 'SESSION_CLOSED', 'CASH_OPENED', 'CASH_MOVEMENT', 'CASH_CLOSED', 'POINTS_REDEEMED', 'POINTS_ADJUSTED'],
  VOIDS: ['ITEM_VOIDED', 'DISCOUNT_APPLIED', 'DISCOUNT_REMOVED', 'SESSION_CANCELLED', 'RESERVATION_CANCELLED'],
  STOCK: ['STOCK_PURCHASE', 'STOCK_WASTE', 'STOCK_COUNT'],
  MENU: ['PRICE_CHANGED', 'AVAILABILITY_CHANGED'],
  ACCESS: ['USER_CREATED', 'USER_UPDATED', 'PIN_CHANGED', 'DEVICE_REGISTERED', 'DEVICE_REVOKED', 'PIN_LOGIN', 'ATTENDANCE_ADDED', 'ATTENDANCE_CHANGED', 'ATTENDANCE_VOIDED', 'SCHEDULE_CHANGED', 'ATTENDANCE_SETTINGS'],
};
const META: Record<string, { icon: string; tone: string }> = {
  ITEM_VOIDED: { icon: 'x', tone: 'red' }, DISCOUNT_APPLIED: { icon: 'percent', tone: 'amber' }, DISCOUNT_REMOVED: { icon: 'undo', tone: 'gray' },
  PAYMENT_REGISTERED: { icon: 'money', tone: 'green' }, SESSION_CLOSED: { icon: 'check', tone: 'green' }, SESSION_CANCELLED: { icon: 'x', tone: 'red' },
  CASH_OPENED: { icon: 'unlock', tone: 'green' }, CASH_MOVEMENT: { icon: 'repeat', tone: 'blue' }, CASH_CLOSED: { icon: 'lock', tone: 'violet' },
  AVAILABILITY_CHANGED: { icon: 'package', tone: 'amber' }, PRICE_CHANGED: { icon: 'edit', tone: 'violet' }, USER_CREATED: { icon: 'user-plus', tone: 'blue' },
  USER_UPDATED: { icon: 'users', tone: 'blue' }, PIN_CHANGED: { icon: 'lock', tone: 'blue' }, DEVICE_REGISTERED: { icon: 'phone', tone: 'blue' },
  DEVICE_REVOKED: { icon: 'phone', tone: 'red' }, PIN_LOGIN: { icon: 'phone', tone: 'gray' }, STOCK_PURCHASE: { icon: 'cart', tone: 'green' },
  STOCK_WASTE: { icon: 'trash', tone: 'red' }, STOCK_COUNT: { icon: 'check', tone: 'blue' }, POINTS_REDEEMED: { icon: 'gift', tone: 'pink' },
  POINTS_ADJUSTED: { icon: 'gift', tone: 'pink' }, RESERVATION_CANCELLED: { icon: 'calendar', tone: 'gray' },
  ATTENDANCE_ADDED: { icon: 'clock', tone: 'blue' }, ATTENDANCE_CHANGED: { icon: 'clock', tone: 'amber' }, ATTENDANCE_VOIDED: { icon: 'clock', tone: 'red' },
  SCHEDULE_CHANGED: { icon: 'calendar', tone: 'blue' }, ATTENDANCE_SETTINGS: { icon: 'clock', tone: 'violet' },
};

/** Bitácora para el dueño: quién anuló, descontó, cobró o cambió precios, y cuándo, con señales de alerta. */
@Component({
  imports: [AppShellComponent, FormsModule, IconComponent],
  templateUrl: './audit.page.html',
  styleUrls: ['./audit.scss', './audit-panel.scss', './audit-events.scss'],
})
export class AuditPage implements OnInit {
  private readonly api = inject(ApiService);
  readonly page = signal<AuditResults | null>(null);
  readonly summary = signal<AuditSummary | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly preset = signal<Preset>('WEEK');
  readonly group = signal<Group>('ALL');
  readonly userId = signal<string | null>(null);
  readonly search = signal('');
  readonly presets: { key: Preset; label: string }[] = [{ key: 'TODAY', label: 'Hoy' }, { key: 'YESTERDAY', label: 'Ayer' }, { key: 'WEEK', label: '7 días' }, { key: 'MONTH', label: '30 días' }];
  from = this.shift(-6);
  to = businessDate();
  private searchTimer?: number;

  readonly groups = computed(() => {
    const counts = new Map((this.summary()?.actions || []).map((a) => [a.action, Number(a.count)]));
    const total = [...counts.values()].reduce((sum, value) => sum + value, 0);
    const sum = (key: Exclude<Group, 'ALL'>) => GROUPS[key].reduce((s, action) => s + (counts.get(action) || 0), 0);
    return [
      { key: 'ALL' as Group, label: 'Todo', icon: 'layers', count: total },
      { key: 'VOIDS' as Group, label: 'Anulaciones y descuentos', icon: 'percent', count: sum('VOIDS') },
      { key: 'MONEY' as Group, label: 'Dinero y caja', icon: 'money', count: sum('MONEY') },
      { key: 'MENU' as Group, label: 'Carta y precios', icon: 'utensils', count: sum('MENU') },
      { key: 'STOCK' as Group, label: 'Inventario', icon: 'package', count: sum('STOCK') },
      { key: 'ACCESS' as Group, label: 'Equipo y accesos', icon: 'users', count: sum('ACCESS') },
    ];
  });
  /** Eventos agrupados por día para leer la historia de corrido. */
  readonly days = computed(() => {
    const data = this.page(); if (!data) return [];
    const map = new Map<string, AuditEvent[]>();
    for (const event of data.events) { const day = this.dayKey(event.createdAt, data.timezone); map.set(day, [...(map.get(day) || []), event]); }
    return [...map.entries()].map(([day, events]) => ({ day, events }));
  });
  /** Señales de alerta para el dueño: lo que conviene revisar primero. */
  readonly alerts = computed<Alert[]>(() => {
    const s = this.summary(); if (!s) return [];
    const list: Alert[] = [];
    const action = (key: string) => s.actions.find((a) => a.action === key);
    for (const user of s.users) {
      if (user.voids >= 2 || Number(user.voidAmount) >= 50)
        list.push({ tone: 'bad', icon: 'x', title: `${user.name}: ${user.voids} anulaciones`, detail: `Por ${this.money(user.voidAmount)} en el período. Revisa los motivos.`, userId: user.userId, group: 'VOIDS' });
      if (user.discounts >= 2 || Number(user.discountAmount) >= 40)
        list.push({ tone: 'warn', icon: 'percent', title: `${user.name}: ${user.discounts} descuentos`, detail: `Por ${this.money(user.discountAmount)}. ¿Fueron autorizados?`, userId: user.userId, group: 'VOIDS' });
    }
    const cancelled = action('SESSION_CANCELLED');
    if (cancelled) list.push({ tone: 'warn', icon: 'x', title: `${cancelled.count} ${Number(cancelled.count) === 1 ? 'atención cancelada' : 'atenciones canceladas'}`, detail: 'Mesas que se cerraron sin cobrar.', group: 'VOIDS' });
    const prices = action('PRICE_CHANGED');
    if (prices) list.push({ tone: 'info', icon: 'edit', title: `${prices.count} ${Number(prices.count) === 1 ? 'cambio de precio' : 'cambios de precio'}`, detail: 'Confirma que estén en la carta impresa.', group: 'MENU' });
    const waste = action('STOCK_WASTE');
    if (waste) list.push({ tone: 'info', icon: 'trash', title: `Mermas por ${this.money(waste.amount)}`, detail: `${waste.count} ${Number(waste.count) === 1 ? 'registro' : 'registros'} de insumos perdidos o vencidos.`, group: 'STOCK' });
    return list;
  });
  readonly activeUser = computed(() => this.summary()?.users.find((u) => u.userId === this.userId()) || null);

  ngOnInit(): void { this.reload(); }

  reload(): void { this.loadSummary(); this.load(0); }
  load(page = 0): void {
    if (!this.from || !this.to || this.from > this.to) { this.error.set('Selecciona una fecha inicial anterior o igual a la final.'); return; }
    this.loading.set(true); this.error.set('');
    const group = this.group();
    const params = new URLSearchParams({ from: this.from, to: this.to, action: group === 'ALL' ? 'ALL' : GROUPS[group].join(','), page: String(page), q: this.search() });
    if (this.userId()) params.set('userId', this.userId()!);
    this.api.get<AuditResults>(`/admin/audit?${params}`).subscribe({
      next: (value) => { this.loading.set(false); this.page.set(value); },
      error: (err) => { this.loading.set(false); this.error.set(err.error?.message || 'No se pudo consultar la auditoría.'); },
    });
  }
  private loadSummary(): void { this.api.get<AuditSummary>(`/admin/audit/summary?from=${this.from}&to=${this.to}`).subscribe({ next: (value) => this.summary.set(value), error: () => this.summary.set(null) }); }

  choosePreset(key: Preset): void {
    this.preset.set(key); if (key === 'CUSTOM') return;
    const today = businessDate();
    if (key === 'TODAY') { this.from = today; this.to = today; }
    else if (key === 'YESTERDAY') { this.from = this.shift(-1); this.to = this.shift(-1); }
    else if (key === 'WEEK') { this.from = this.shift(-6); this.to = today; }
    else { this.from = this.shift(-29); this.to = today; }
    this.reload();
  }
  setGroup(group: Group): void { this.group.set(group); this.load(0); }
  setUser(id: string | null): void { this.userId.set(this.userId() === id ? null : id); this.load(0); }
  openAlert(alert: Alert): void { if (alert.group) this.group.set(alert.group); this.userId.set(alert.userId ?? null); this.load(0); }
  searchChanged(value: string): void { this.search.set(value); window.clearTimeout(this.searchTimer); this.searchTimer = window.setTimeout(() => this.load(0), 300); }
  clearFilters(): void { this.group.set('ALL'); this.userId.set(null); this.search.set(''); this.load(0); }

  label(action: string): string { return AUDIT_ACTIONS[action] || action; }
  icon(action: string): string { return META[action]?.icon || 'info'; }
  tone(action: string): string { return META[action]?.tone || 'gray'; }
  time(value: string, zone: string): string { return new Intl.DateTimeFormat('es-PE', { timeZone: zone, hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
  dayKey(value: string, zone: string): string { return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)); }
  dayLabel(day: string): string {
    if (day === businessDate()) return 'Hoy'; if (day === this.shift(-1)) return 'Ayer';
    const text = new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z'));
    return text.charAt(0).toUpperCase() + text.slice(1);
  }
  initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase(); }
  money(value: number | null | undefined): string { return `S/ ${Number(value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`; }
  totalPages(data: AuditResults): number { return Math.max(1, Math.ceil(data.total / data.pageSize)); }
  private shift(days: number): string { const d = new Date(businessDate() + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
}
