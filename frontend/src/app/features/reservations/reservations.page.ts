import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { ReservationView, TableView, ZoneView } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { WaitlistPanelComponent } from './waitlist-panel.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { businessDate } from '../management/management.models';

/** Agenda de reservas del día: alta, confirmación, asignación de mesa y llegada del cliente. */
@Component({
  imports: [AppShellComponent, FormsModule, NgTemplateOutlet, WaitlistPanelComponent, IconComponent],
  templateUrl: './reservations.page.html',
  styleUrls: ['./reservations.page.scss', './reservations-agenda.scss', './reservations-panel.scss', './reservations-form.scss', './reservations-mobile.scss', './reservations-sheet.scss', './reservations-form-v2.scss', './reservations-form-extra.scss', './reservations-pickers.scss', './reservations-seat.scss', './reservations-find.scss'],
})
export class ReservationsPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  readonly realtime = inject(RealtimeService);
  /** Recepción: agenda de reservas o lista de espera de quienes llegan sin reservar. */
  readonly tab = signal<'reservations' | 'waitlist'>(this.savedTab());
  readonly waitingCount = signal(0);
  setTab(tab: 'reservations' | 'waitlist'): void {
    this.tab.set(tab);
    try {
      localStorage.setItem('restos_host_tab', tab);
    } catch {
      /* sin almacenamiento */
    }
  }
  private savedTab(): 'reservations' | 'waitlist' {
    try {
      return localStorage.getItem('restos_host_tab') === 'waitlist' ? 'waitlist' : 'reservations';
    } catch {
      return 'reservations';
    }
  }
  private unwatch?: () => void;
  private stopResync?: () => void;
  readonly date = signal(businessDate());
  readonly reservations = signal<ReservationView[]>([]);
  readonly tables = signal<TableView[]>([]);
  readonly zones = signal<ZoneView[]>([]);
  readonly editing = signal<ReservationView | 'NEW' | null>(null);
  readonly seating = signal<ReservationView | null>(null);
  readonly busy = signal(false);
  readonly clock = signal(Date.now());
  private clockTimer?: number;
  customerName = '';
  phone = '';
  partySize = 2;
  day = businessDate();
  time = '20:00';
  durationMinutes = 90;
  tableId = '';
  notes = '';
  confirmed = true;
  seatTableId = '';
  seatZone = '';
  zoneId = '';

  readonly active = computed(() =>
    this.reservations().filter((item) => ['PENDING', 'CONFIRMED'].includes(item.status)),
  );
  readonly closed = computed(() =>
    this.reservations().filter((item) => !['PENDING', 'CONFIRMED'].includes(item.status)),
  );
  readonly guests = computed(() => this.active().reduce((sum, item) => sum + item.partySize, 0));
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.reservations().find((item) => item.id === this.selectedId()) || null);
  readonly pendingCount = computed(() => this.active().filter((item) => item.status === 'PENDING').length);
  readonly seatedCount = computed(() => this.reservations().filter((item) => item.status === 'SEATED').length);
  readonly showClosed = signal(false);
  /** Resumen bajo el título: el día y cuántas reservas y personas vienen. */
  readonly headerSummary = computed(() => {
    const count = this.active().length;
    const day = this.isToday() ? 'Hoy' : this.dayTitle().replace(/^\w/, (c) => c.toUpperCase());
    return count ? `${day} · ${count} ${count === 1 ? 'reserva' : 'reservas'} · ${this.guests()} personas` : `${day} · sin reservas`;
  });
  /** Los próximos 7 días como chips para cambiar de día con un toque. */
  readonly nextDays = computed(() => {
    const base = new Date(businessDate() + 'T12:00:00Z');
    return Array.from({ length: 7 }, (_, index) => {
      const day = new Date(base); day.setUTCDate(base.getUTCDate() + index);
      const value = day.toISOString().slice(0, 10);
      const top = index === 0 ? 'Hoy' : index === 1 ? 'Mañana' : new Intl.DateTimeFormat('es-PE', { weekday: 'short', timeZone: 'UTC' }).format(day).replace('.', '');
      return { value, top, num: day.getUTCDate() };
    });
  });
  readonly panelOpen = computed(() => !!this.editing() || !!this.seating() || !!this.selected());
  // ——— Panel como hoja en celular: arrastrar hacia abajo cierra, hacia arriba la agranda ———
  readonly sheetY = signal(0);
  readonly sheetHeight = signal(0);
  readonly sheetExpanded = signal(false);
  readonly sheetDragging = signal(false);
  private sheetDrag: { pointerId: number; y: number; time: number; base: number; full: number } | null = null;
  sheetStart(event: PointerEvent): void {
    if (!matchMedia('(max-width: 860px)').matches) return;
    const sheet = (event.currentTarget as HTMLElement).closest('.rv-panel') as HTMLElement;
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
      if (dy > Math.min(140, drag.base * 0.3) || speed > 0.5) { this.closeSheet(); return; }
      if (dy < -50 || speed < -0.4) this.sheetExpanded.set(true);
    } else if (dy > 0) {
      if (height < drag.full * 0.3) { this.closeSheet(); return; }
      if (dy > 90 || speed > 0.4) this.sheetExpanded.set(false);
    }
    this.sheetY.set(0); this.sheetHeight.set(0);
  }
  private closeSheet(): void {
    navigator.vibrate?.(6);
    this.sheetY.set(window.innerHeight); this.sheetHeight.set(0);
    window.setTimeout(() => { this.closePanel(); this.sheetY.set(0); this.sheetExpanded.set(false); }, 260);
  }
  /** Reservas online: el cliente reserva y elige su mesa desde un enlace público. */
  readonly booking = signal<{ branchId: string; enabled: boolean } | null>(null);
  bookingLink(): string { const b = this.booking(); return b ? `${location.origin}/reservar?b=${b.branchId}` : ''; }
  toggleBooking(): void {
    const current = this.booking(); if (!current) return;
    this.api.put<{ branchId: string; enabled: boolean }>('/admin/booking', { enabled: !current.enabled }).subscribe({
      next: (value) => { this.booking.set(value); this.toast.show(value.enabled ? 'Reservas online activadas' : 'Reservas online desactivadas', value.enabled ? 'Comparte el enlace en Instagram, Google o WhatsApp.' : '', 'success', '✓'); },
      error: (err) => this.fail(err, 'No se pudo cambiar.'),
    });
  }
  async copyBookingLink(): Promise<void> {
    try { await navigator.clipboard.writeText(this.bookingLink()); this.toast.show('Enlace copiado', this.bookingLink(), 'success', '✓'); }
    catch { this.toast.show('Copia el enlace', this.bookingLink(), 'info', 'i'); }
  }
  /** Agenda agrupada por hora para leer el día de un vistazo. */
  // ——— Encontrar al cliente que llega: primero «Llegan ahora», luego el buscador ———
  readonly query = signal('');
  private normalize(text: string): string { return text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim(); }
  /** Coincide por nombre (sin tildes) o por los dígitos del celular (desde 3). */
  private matches(item: ReservationView, query: string): boolean {
    const text = this.normalize(query); const digits = query.replace(/\D/g, '');
    return (!!text && this.normalize(item.customerName).includes(text)) || (digits.length >= 3 && (item.phone || '').replace(/\D/g, '').includes(digits));
  }
  readonly found = computed(() => {
    const query = this.query();
    return query.trim() ? [...this.active(), ...this.closed()].filter((item) => this.matches(item, query)) : [];
  });
  /** Hoy: retrasadas y las que llegan entre 30 min antes y 30 min después de ahora. */
  readonly arrivingNow = computed(() => this.isToday()
    ? this.active().filter((item) => this.late(item) || Math.abs(this.minutesTo(item)) <= 30) : []);
  readonly groups = computed(() => {
    const map = new Map<string, ReservationView[]>();
    const now = new Set(this.arrivingNow().map((item) => item.id));
    for (const item of this.active().filter((entry) => !now.has(entry.id))) {
      const hour = item.reservedAt.slice(11, 13) + ':00';
      map.set(hour, [...(map.get(hour) || []), item]);
    }
    return [...map.entries()].map(([hour, items]) => ({ hour, items, guests: items.reduce((sum, item) => sum + item.partySize, 0) }));
  });
  /** Próxima llegada que todavía no pasó (solo hoy). */
  readonly nextArrival = computed(() => (this.isToday() ? this.active().find((item) => !this.late(item) && this.minutesTo(item) >= -15) : this.active()[0]) || null);
  readonly freeTables = computed(() => this.tables().filter((table) => table.status !== 'OPEN'));

  ngOnInit(): void {
    this.load();
    if (['ORGANIZATION_ADMIN', 'BRANCH_MANAGER'].includes(this.auth.role()))
      this.api.get<{ branchId: string; enabled: boolean }>('/admin/booking').subscribe({ next: (value) => this.booking.set(value), error: () => undefined });
    this.clockTimer = window.setInterval(() => this.clock.set(Date.now()), 60000);
    this.api.get<TableView[]>('/staff/tables').subscribe((value) => this.tables.set(value));
    this.api
      .get<{ zones: ZoneView[] }>('/staff/zones')
      .subscribe({ next: (value) => this.zones.set(value.zones), error: () => undefined });
    this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => { this.load(); this.api.get<TableView[]>('/staff/tables').subscribe((value) => this.tables.set(value)); });
    const branch = this.auth.branchId();
    if (branch)
      this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, (event) => {
        if (event.type === 'RESERVATION_UPDATED') this.load();
        if (event.type.startsWith('TABLE_'))
          this.api.get<TableView[]>('/staff/tables').subscribe((value) => this.tables.set(value));
      });
  }

  load(): void {
    this.api.get<ReservationView[]>(`/staff/reservations?date=${this.date()}`).subscribe({
      next: (value) => this.reservations.set(value),
      error: (err) => this.fail(err, 'No se pudieron cargar las reservas.'),
    });
  }

  shiftDay(days: number): void {
    const next = new Date(this.date() + 'T12:00:00Z');
    next.setUTCDate(next.getUTCDate() + days);
    this.date.set(next.toISOString().slice(0, 10));
    this.load();
  }
  setDate(value: string): void {
    if (value) {
      this.date.set(value);
      this.load();
    }
  }
  today(): string {
    return businessDate();
  }
  isToday(): boolean {
    return this.date() === businessDate();
  }

  select(reservation: ReservationView): void {
    this.selectedId.set(reservation.id);
    this.editing.set(null);
    this.seating.set(null);
  }
  closePanel(): void {
    this.selectedId.set(null);
    this.editing.set(null);
    this.seating.set(null);
  }
  /** Minutos que faltan para la reserva (negativo si ya pasó), con la hora de Lima. */
  minutesTo(reservation: ReservationView): number {
    const [hours, minutes] = this.timeLabel(reservation).split(':').map(Number);
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'America/Lima', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(this.clock()));
    const now = Number(parts.find((part) => part.type === 'hour')?.value) * 60 + Number(parts.find((part) => part.type === 'minute')?.value);
    return hours * 60 + minutes - now;
  }
  countdown(reservation: ReservationView): string {
    if (!this.isToday()) return '';
    const diff = this.minutesTo(reservation);
    if (diff > 0) return diff < 60 ? `en ${diff} min` : `en ${Math.floor(diff / 60)}h${diff % 60 ? String(diff % 60).padStart(2, '0') : ''}`;
    return diff >= -15 ? 'ahora' : -diff < 60 ? `hace ${-diff} min` : `hace ${Math.floor(-diff / 60)}h${String(-diff % 60).padStart(2, '0')}`;
  }
  /** Mensaje de WhatsApp listo para confirmar la reserva con el cliente. */
  whatsapp(reservation: ReservationView): string {
    const name = reservation.customerName.split(' ')[0];
    const text = `¡Hola ${name}! Te escribimos para confirmar tu reserva para ${reservation.partySize} ${reservation.partySize === 1 ? 'persona' : 'personas'} el ${this.longDate(reservation.reservedAt.slice(0, 10))} a las ${this.timeLabel(reservation)}. ¿Nos confirmas tu asistencia? 😊`;
    return `https://wa.me/51${reservation.phone.replace(/\D/g, '')}?text=${encodeURIComponent(text)}`;
  }
  longDate(day: string): string {
    return new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z'));
  }
  initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase(); }
  edit(reservation: ReservationView | 'NEW'): void {
    this.seating.set(null);
    if (reservation === 'NEW') this.selectedId.set(null);
    if (reservation === 'NEW') {
      this.customerName = '';
      this.phone = '';
      this.partySize = 2;
      this.day = this.date();
      this.time = '20:00';
      this.durationMinutes = 90;
      this.tableId = '';
      this.notes = '';
      this.confirmed = true;
      this.zoneId = '';
    } else {
      this.customerName = reservation.customerName;
      this.phone = reservation.phone;
      this.partySize = reservation.partySize;
      this.day = reservation.reservedAt.slice(0, 10);
      this.time = reservation.reservedAt.slice(11, 16);
      this.durationMinutes = reservation.durationMinutes;
      this.tableId = reservation.tableId || '';
      this.notes = reservation.notes || '';
      this.confirmed = reservation.status === 'CONFIRMED';
      this.zoneId = reservation.zoneId || '';
    }
    // Hoy, si las 20:00 ya pasaron, propone la siguiente media hora.
    if (reservation === 'NEW' && this.day === businessDate()) {
      const now = new Date(); const next = Math.ceil((now.getHours() * 60 + now.getMinutes() + 1) / 30) * 30;
      if (next > 20 * 60 && next <= 23 * 60) this.time = `${String(Math.floor(next / 60)).padStart(2, '0')}:${String(next % 60).padStart(2, '0')}`;
    }
    this.moreOpen = reservation !== 'NEW' && (!!this.tableId || !!this.zoneId || !!this.notes);
    this.picker = '';
    this.editing.set(reservation);
    this.revealChips();
  }

  /** Lleva a la vista el día y la hora elegidos dentro de las filas que se deslizan. */
  revealChips(): void {
    setTimeout(() => document.querySelectorAll('.rf-scroll .on').forEach((chip) => chip.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })), 60);
  }

  // ——— Formulario de reserva: todo se arma con toques ———
  // Selectores propios de fecha y hora (los nativos del celular no siguen el tema de la app).
  picker: '' | 'day' | 'time' = '';
  calMonth = '';
  readonly weekDays = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];
  draftDay = '';
  readonly wheelsReady = signal(false);
  draftHour = '20';
  draftMinute = '00';
  readonly wheelHours = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'));
  readonly wheelMinutes = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'));
  private static readonly WHEEL_ITEM = 44;
  openPicker(kind: 'day' | 'time'): void {
    this.draftDay = this.day || businessDate();
    this.calMonth = this.draftDay.slice(0, 7);
    [this.draftHour, this.draftMinute] = (this.time || '20:00').split(':');
    this.wheelsReady.set(false);
    this.picker = kind;
    // Las ruedas se muestran recién cuando ya están en la hora actual: sin saltos visibles.
    if (kind === 'time') requestAnimationFrame(() => {
      this.scrollWheel('h', Number(this.draftHour), 'auto');
      this.scrollWheel('m', Number(this.draftMinute), 'auto');
      this.wheelsReady.set(true);
    });
  }
  /** Ruedas tipo iPhone: el número que queda al centro es el elegido. */
  onWheel(event: Event, kind: 'h' | 'm'): void {
    const list = kind === 'h' ? this.wheelHours : this.wheelMinutes;
    if (!this.wheelsReady()) return;
    const index = Math.max(0, Math.min(list.length - 1, Math.round((event.target as HTMLElement).scrollTop / ReservationsPage.WHEEL_ITEM)));
    if (kind === 'h' && this.draftHour !== list[index]) this.draftHour = list[index];
    if (kind === 'm' && this.draftMinute !== list[index]) this.draftMinute = list[index];
  }
  scrollWheel(kind: 'h' | 'm', index: number, behavior: ScrollBehavior = 'smooth'): void {
    document.querySelector(`.pk-wheel[data-wheel="${kind}"]`)?.scrollTo({ top: index * ReservationsPage.WHEEL_ITEM, behavior });
  }
  acceptPicker(): void {
    if (this.picker === 'day') { this.day = this.draftDay; this.revealChips(); }
    else this.time = `${this.draftHour}:${this.draftMinute}`;
    this.picker = '';
  }
  monthLabel(): string {
    const label = new Intl.DateTimeFormat('es-PE', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(this.calMonth + '-01T12:00:00Z'));
    return label[0].toUpperCase() + label.slice(1);
  }
  canPrevMonth(): boolean { return this.calMonth > businessDate().slice(0, 7); }
  shiftMonth(step: number): void {
    const date = new Date(this.calMonth + '-01T12:00:00Z'); date.setUTCMonth(date.getUTCMonth() + step);
    this.calMonth = date.toISOString().slice(0, 7);
  }
  /** Celdas del mes empezando en lunes; null = hueco antes del día 1. */
  monthCells(): ({ value: string; num: number; past: boolean; today: boolean } | null)[] {
    const first = new Date(this.calMonth + '-01T12:00:00Z');
    const today = businessDate();
    const cells: ({ value: string; num: number; past: boolean; today: boolean } | null)[] = Array((first.getUTCDay() + 6) % 7).fill(null);
    for (const date = new Date(first); date.getUTCMonth() === first.getUTCMonth(); date.setUTCDate(date.getUTCDate() + 1)) {
      const value = date.toISOString().slice(0, 10);
      cells.push({ value, num: date.getUTCDate(), past: value < today, today: value === today });
    }
    return cells;
  }
  moreOpen = false;
  readonly partyOptions = Array.from({ length: 10 }, (_, index) => index + 1);
  /** «Más»: grupos grandes se escriben (hasta 200). */
  setParty(event: Event): void {
    const value = Math.round(Number((event.target as HTMLInputElement).value));
    if (value >= 1) this.partySize = Math.min(value, 200);
  }
  otherDayLabel(): string {
    return this.day ? new Intl.DateTimeFormat('es-PE', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(this.day + 'T12:00:00Z')).replace('.', '') : 'Otra';
  }
  /** Solo lo que el mesero cambió en «Más detalles»; sin cambios no muestra nada. */
  detailsSummary(): string {
    const table = this.tables().find((item) => item.id === this.tableId);
    const parts: string[] = [];
    if (table) parts.push(table.label); else if (this.zoneId) parts.push(this.zoneName(this.zoneId));
    if (this.notes.trim()) parts.push('con nota');
    return parts.join(' · ');
  }
  readonly noteChips = ['🎂 Cumpleaños', '👶 Silla de bebé', '⚠️ Alergia', '💕 Aniversario', '🪟 Ventana'];
  /** Horas de 12:00 a 23:00 cada 30 minutos; hoy solo las que aún no pasan (más la elegida). */
  timeSlots(): string[] {
    const today = this.day === businessDate();
    const now = new Date(); const nowMinutes = now.getHours() * 60 + now.getMinutes();
    const list: string[] = [];
    for (let minutes = 12 * 60; minutes <= 23 * 60; minutes += 30) {
      const slot = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
      if (!today || minutes > nowMinutes - 30 || slot === this.time) list.push(slot);
    }
    return list;
  }
  isNextDay(value: string): boolean { return this.nextDays().some((day) => day.value === value); }
  /** Mesas de la zona elegida (o todas), primero las que alcanzan para el grupo. */
  formTables(): TableView[] {
    return this.tables().filter((table) => !this.zoneId || table.zoneId === this.zoneId)
      .sort((a, b) => Number(this.tableFits(b)) - Number(this.tableFits(a)) || a.capacity - b.capacity);
  }
  private noteParts(): string[] { return this.notes.split(',').map((part) => part.trim()).filter(Boolean); }
  hasNote(note: string): boolean { return this.noteParts().includes(note); }
  toggleNote(note: string): void {
    const parts = this.noteParts();
    this.notes = (parts.includes(note) ? parts.filter((part) => part !== note) : [...parts, note]).join(', ');
  }
  formSummary(): string {
    const option = this.nextDays().find((day) => day.value === this.day);
    const dayLabel = option ? (option.top === 'Hoy' || option.top === 'Mañana' ? option.top : `${option.top[0].toUpperCase()}${option.top.slice(1)} ${option.num}`)
      : new Intl.DateTimeFormat('es-PE', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(this.day + 'T12:00:00Z'));
    return `${dayLabel} ${this.time} · ${this.partySize} pers.`;
  }

  tableFits(table: TableView): boolean {
    return table.capacity >= this.partySize;
  }

  save(): void {
    const editing = this.editing();
    if (!editing || !this.customerName.trim() || !this.phone.trim() || this.busy()) return;
    const body = {
      customerName: this.customerName,
      phone: this.phone,
      partySize: this.partySize,
      reservedAt: `${this.day}T${this.time}:00`,
      durationMinutes: this.durationMinutes,
      tableId: this.tableId || null,
      notes: this.notes || null,
      confirmed: this.confirmed,
      zoneId: this.zoneId || null,
    };
    const request =
      editing === 'NEW'
        ? this.api.post<ReservationView>('/staff/reservations', body)
        : this.api.patch<ReservationView>(`/staff/reservations/${editing.id}`, body);
    this.busy.set(true);
    request.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.editing.set(null);
        this.selectedId.set(saved.id);
        this.toast.show(
          'Reserva guardada',
          `${saved.customerName} · ${this.timeLabel(saved)}`,
          'success',
          '✓',
        );
        if (this.day !== this.date()) this.setDate(this.day);
        else this.load();
      },
      error: (err) => {
        this.busy.set(false);
        this.fail(err, 'No se pudo guardar la reserva.');
      },
    });
  }

  async changeStatus(
    reservation: ReservationView,
    status: 'CONFIRMED' | 'CANCELLED' | 'NO_SHOW',
  ): Promise<void> {
    if (status !== 'CONFIRMED') {
      const accepted = await this.dialog.confirm({
        title: status === 'CANCELLED' ? 'Cancelar reserva' : 'Marcar que no llegó',
        message: `${reservation.customerName} · ${this.timeLabel(reservation)} · ${reservation.partySize} personas. La mesa quedará libre en ese horario.`,
        confirmLabel: status === 'CANCELLED' ? 'Cancelar reserva' : 'No llegó',
        tone: 'warning',
        icon: '!',
      });
      if (!accepted) return;
    }
    this.api.post(`/staff/reservations/${reservation.id}/status`, { status }).subscribe({
      next: () => this.load(),
      error: (err) => this.fail(err, 'No se pudo actualizar la reserva.'),
    });
  }

  startSeat(reservation: ReservationView): void {
    // Con mesa asignada y libre se sienta directo; si no, se elige la mesa.
    const assigned = this.tables().find((table) => table.id === reservation.tableId);
    if (assigned && assigned.status !== 'OPEN') {
      this.seat(reservation, assigned.id);
      return;
    }
    this.selectedId.set(reservation.id);
    this.editing.set(null);
    // Empieza en la zona que pidió (si hay mesa libre ahí) y con la mesa ideal marcada.
    this.seatZone = reservation.zoneId && this.freeTables().some((table) => table.zoneId === reservation.zoneId) ? reservation.zoneId : '';
    this.seatTableId = this.idealSeat(reservation);
    this.seating.set(reservation);
  }

  seat(reservation: ReservationView, tableId: string): void {
    if (!tableId || this.busy()) return;
    this.busy.set(true);
    this.api
      .post<ReservationView>(`/staff/reservations/${reservation.id}/seat`, { tableId })
      .subscribe({
        next: (value) => {
          this.busy.set(false);
          this.seating.set(null);
          this.toast.show(
            'Cliente en mesa',
            `${value.customerName} está en ${value.tableLabel}. La cuenta quedará en su ficha.`,
            'success',
            '✓',
          );
          this.load();
          this.api.get<TableView[]>('/staff/tables').subscribe((tables) => this.tables.set(tables));
        },
        error: (err) => {
          this.busy.set(false);
          this.fail(err, 'No se pudo sentar al cliente.');
        },
      });
  }

  zoneName(zoneId: string | null): string {
    return this.zones().find((zone) => zone.id === zoneId)?.name || '';
  }
  /** Mesas que se ofrecen al reservar: primero las de la zona preferida. */
  tableOptions(): TableView[] {
    return [...this.tables()].sort(
      (a, b) =>
        Number(b.zoneId === this.zoneId && !!this.zoneId) -
        Number(a.zoneId === this.zoneId && !!this.zoneId),
    );
  }
  /** Mesas libres para sentar al cliente, empezando por la zona que pidió. */
  seatOptions(reservation: ReservationView): TableView[] {
    return [...this.freeTables()].sort(
      (a, b) =>
        Number(b.zoneId === reservation.zoneId && !!reservation.zoneId) -
        Number(a.zoneId === reservation.zoneId && !!reservation.zoneId),
    );
  }
  /** Zonas que tienen al menos una mesa libre. */
  seatZones(reservation: ReservationView): ZoneView[] {
    return this.zones().filter((zone) => this.seatOptions(reservation).some((table) => table.zoneId === zone.id));
  }
  /** Mesas libres de la zona elegida: primero las que alcanzan, de la más chica a la más grande. */
  seatList(reservation: ReservationView): TableView[] {
    const fits = (table: TableView) => table.capacity >= reservation.partySize;
    return this.seatOptions(reservation).filter((table) => !this.seatZone || table.zoneId === this.seatZone)
      .sort((a, b) => Number(fits(b)) - Number(fits(a)) || a.capacity - b.capacity || a.label.localeCompare(b.label));
  }
  /** La mesa más chica donde entra todo el grupo. */
  idealSeat(reservation: ReservationView): string {
    return this.seatList(reservation).find((table) => table.capacity >= reservation.partySize)?.id || '';
  }
  tableLabel(tableId: string): string { return this.tables().find((table) => table.id === tableId)?.label || 'la mesa'; }
  timeLabel(reservation: ReservationView): string {
    return reservation.reservedAt.slice(11, 16);
  }
  statusLabel(status: string): string {
    return (
      (
        {
          PENDING: 'Por confirmar',
          CONFIRMED: 'Confirmada',
          SEATED: 'En mesa',
          CANCELLED: 'Cancelada',
          NO_SHOW: 'No llegó',
        } as Record<string, string>
      )[status] || status
    );
  }
  statusClass(status: string): string {
    return (
      (
        {
          PENDING: 'preparing',
          CONFIRMED: 'ready',
          SEATED: 'delivered',
          CANCELLED: 'neutral',
          NO_SHOW: 'review',
        } as Record<string, string>
      )[status] || 'neutral'
    );
  }
  dayTitle(): string {
    return new Intl.DateTimeFormat('es-PE', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      timeZone: 'UTC',
    }).format(new Date(this.date() + 'T12:00:00Z'));
  }
  /** Llega tarde: pasaron más de 15 minutos de la hora reservada (hora de Lima) sin sentarse. */
  late(reservation: ReservationView): boolean {
    if (!this.isToday()) return false;
    const [hours, minutes] = this.timeLabel(reservation).split(':').map(Number);
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: 'America/Lima',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(new Date(this.clock()));
    const now =
      Number(parts.find((part) => part.type === 'hour')?.value) * 60 +
      Number(parts.find((part) => part.type === 'minute')?.value);
    return now > hours * 60 + minutes + 15;
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.toast.show('No se pudo completar', err.error?.message || fallback, 'error', '!');
  }

  ngOnDestroy(): void { this.stopResync?.();
    this.unwatch?.();
    if (this.clockTimer) window.clearInterval(this.clockTimer);
  }
}
