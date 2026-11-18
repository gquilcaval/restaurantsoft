import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { TableView, ZoneView } from '../../core/api/models';
import { FloorElement, FloorPlan, TableShape } from '../../shared/floor/floor.models';
import { IconComponent } from '../../shared/ui/icon.component';
import { FloorTableState, FloorViewComponent } from '../waiter/floor-view.component';

interface BookingTable { id: string; label: string; capacity: number; zoneId: string | null; x: number | null; y: number | null; shape: TableShape; rotation: number; }
interface BookingInfo {
  branchName: string; address: string | null; organizationName: string; logoUrl: string | null; primaryColor: string | null;
  zones: { id: string; name: string; color: string }[]; tables: BookingTable[]; elements: FloorElement[];
}
interface Confirmation { id: string; date: string; time: string; partySize: number; }

/** Reserva online: el cliente elige personas, fecha, hora y su mesa en el plano del local. */
@Component({
  imports: [FormsModule, IconComponent, FloorViewComponent],
  templateUrl: './booking.page.html',
  styleUrls: ['./booking.page.scss', './booking-form.scss'],
})
export class BookingPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  readonly branchId = signal('');
  readonly info = signal<BookingInfo | null>(null);
  readonly error = signal('');
  readonly busy = signal<string[]>([]);
  readonly loadingBusy = signal(false);
  readonly sending = signal(false);
  readonly done = signal<Confirmation | null>(null);
  readonly party = signal(2);
  readonly date = signal(this.today());
  readonly time = signal('');
  readonly tableId = signal<string | null>(null);
  name = ''; phone = ''; notes = '';

  /** Horarios cada 30 minutos de 12:00 a 22:30; hoy, solo con 30 minutos de anticipación. */
  readonly times = computed(() => {
    const list: string[] = [];
    const now = new Date(); const isToday = this.date() === this.today();
    const minimum = now.getHours() * 60 + now.getMinutes() + 30;
    for (let minutes = 12 * 60; minutes <= 22 * 60 + 30; minutes += 30) {
      if (isToday && minutes < minimum) continue;
      list.push(`${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`);
    }
    return list;
  });
  readonly zones = computed<ZoneView[]>(() => (this.info()?.zones || []).map((zone, index) => ({ ...zone, displayOrder: index, open: true, active: true, tables: 0, occupied: 0, waiterIds: [] })));
  readonly plan = computed<FloorPlan>(() => ({
    tables: (this.info()?.tables || []).map((t) => ({ id: t.id, x: t.x, y: t.y, shape: t.shape || 'SQUARE', rotation: t.rotation })),
    elements: this.info()?.elements || [],
  }));
  /** Estado de cada mesa para el plano: elegida, ocupada en ese horario o pequeña para el grupo. */
  readonly states = computed<FloorTableState[]>(() => (this.info()?.tables || []).map((t) => {
    const busy = this.busy().includes(t.id); const small = t.capacity < this.party(); const chosen = this.tableId() === t.id;
    const table: TableView = { id: t.id, label: t.label, capacity: t.capacity, qrPublicId: '', status: busy ? 'OPEN' : 'FREE', sessionId: null,
      requiresReview: false, lastActivityAt: null, zoneId: t.zoneId, openedAt: null };
    return { table, tone: chosen ? 'ready' : busy ? 'busy' : 'free', guests: 0, total: 0, readyCount: 0, cooking: false, reservedAt: null, dimmed: (busy || small) && !chosen };
  }));
  readonly chosen = computed(() => this.info()?.tables.find((t) => t.id === this.tableId()) || null);
  readonly freeCount = computed(() => (this.info()?.tables || []).filter((t) => t.capacity >= this.party() && !this.busy().includes(t.id)).length);
  readonly hint = signal('');

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('b') || '';
    this.branchId.set(id);
    if (!id) { this.error.set('El enlace de reservas está incompleto.'); return; }
    this.api.get<BookingInfo>(`/public/booking/${id}`).subscribe({
      next: (value) => {
        this.info.set(value);
        if (value.primaryColor) document.documentElement.style.setProperty('--brand', value.primaryColor);
        if (!this.time()) this.time.set(this.times()[Math.min(this.times().length - 1, 14)] || '');
        this.loadBusy();
        window.setTimeout(() => document.querySelector(`.bk-times [data-slot="${this.time()}"]`)?.scrollIntoView({ inline: 'center', block: 'nearest' }), 120);
      },
      error: (err) => this.error.set(err.error?.message || 'No pudimos abrir las reservas de este local.'),
    });
  }

  setParty(value: number): void { this.party.set(Math.max(1, Math.min(30, value))); this.validateTable(); }
  setDate(value: string): void { if (!value) return; this.date.set(value); if (!this.times().includes(this.time())) this.time.set(this.times()[0] || ''); this.loadBusy(); }
  setTime(value: string): void { this.time.set(value); this.loadBusy(); }
  private loadBusy(): void {
    if (!this.time()) { this.busy.set([]); return; }
    this.loadingBusy.set(true);
    this.api.get<{ busyTableIds: string[] }>(`/public/booking/${this.branchId()}/availability?date=${this.date()}&time=${this.time()}`).subscribe({
      next: (value) => { this.busy.set(value.busyTableIds); this.loadingBusy.set(false); this.validateTable(); },
      error: () => this.loadingBusy.set(false),
    });
  }
  /** Al tocar una mesa en el plano: se elige si está libre y alcanza para el grupo. */
  pick(table: TableView): void {
    const info = this.info()?.tables.find((t) => t.id === table.id);
    if (!info) return;
    if (this.busy().includes(info.id)) { this.flash(`${info.label} ya está reservada a esa hora.`); return; }
    if (info.capacity < this.party()) { this.flash(`${info.label} es para ${info.capacity} personas.`); return; }
    this.tableId.set(this.tableId() === info.id ? null : info.id);
  }
  private validateTable(): void {
    const chosen = this.chosen();
    if (chosen && (chosen.capacity < this.party() || this.busy().includes(chosen.id))) this.tableId.set(null);
  }
  private flash(text: string): void { this.hint.set(text); window.setTimeout(() => this.hint.set(''), 2600); }
  canSend(): boolean { return !!this.name.trim() && this.phone.replace(/\D/g, '').length >= 7 && !!this.time() && !this.sending(); }
  send(): void {
    if (!this.canSend()) return;
    this.sending.set(true); this.error.set('');
    this.api.post<Confirmation>(`/public/booking/${this.branchId()}`, { name: this.name, phone: this.phone, partySize: this.party(), date: this.date(),
      time: this.time(), tableId: this.tableId(), notes: this.notes || null }).subscribe({
      next: (value) => { this.sending.set(false); this.done.set(value); },
      error: (err) => { this.sending.set(false); this.error.set(err.error?.message || 'No se pudo enviar tu reserva. Inténtalo otra vez.'); this.loadBusy(); },
    });
  }
  longDate(day: string): string { return new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z')); }
  today(): string { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
  maxDate(): string { const d = new Date(Date.now() + 60 * 86400000); return d.toISOString().slice(0, 10); }
}
