import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { CustomerDetail, CustomerView, LoyaltyProgram } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { InfoTipComponent } from '../../shared/ui/info-tip.component';
import { ToastService } from '../../shared/ui/toast.service';

type Segment = 'ALL' | 'FREQUENT' | 'AT_RISK' | 'BIRTHDAY' | 'NEW';
interface CustomerSummary { total: number; frequent: number; atRisk: number; birthdays: number; newCustomers: number; active: number; points: number; avgSpent: number; }
interface Tag { label: string; tone: string; }
const AVATAR_TONES = ['#4f8cff', '#a46bff', '#2dd4bf', '#f59e0b', '#ef6f6c', '#44c26b', '#ec4899', '#0ea5e9'];

/** Clientes: quién viene, quién dejó de venir, qué pide y sus puntos; con acciones para hacerlos volver. */
@Component({
  imports: [AppShellComponent, IconComponent, InfoTipComponent, FormsModule],
  templateUrl: './customers.page.html',
  styleUrls: ['./customers.scss', './customers-list.scss', './customers-detail.scss', './customers-program.scss']
})
export class CustomersPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly auth = inject(AuthService);
  readonly customers = signal<CustomerView[]>([]);
  readonly summary = signal<CustomerSummary | null>(null);
  readonly detail = signal<CustomerDetail | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly program = signal<LoyaltyProgram | null>(null);
  readonly tab = signal<'CUSTOMERS' | 'PROGRAM'>('CUSTOMERS');
  readonly segment = signal<Segment>('ALL');
  readonly mode = signal<'VIEW' | 'EDIT' | 'NEW'>('VIEW');
  readonly loading = signal(true);
  readonly busy = signal(false);
  query = '';
  sort = 'recent';
  fullName = ''; phone = ''; email = ''; birthday = ''; notes = '';
  adjustPoints: number | null = null; adjustReason = '';
  programDraft: LoyaltyProgram = { enabled: true, spendPerPoint: 10, pointValue: 0.1, minRedeem: 100 };
  readonly sorts = [{ key: 'recent', label: 'Recientes' }, { key: 'spent', label: 'Más gasto' }, { key: 'visits', label: 'Más visitas' }, { key: 'points', label: 'Más puntos' }];
  readonly pointChips = [{ points: 50, reason: 'Regalo de cumpleaños' }, { points: 20, reason: 'Compensación por demora' }, { points: -100, reason: 'Canje de premio' }];
  private searchTimer?: number;

  readonly segments = computed(() => {
    const s = this.summary();
    return [
      { key: 'ALL' as Segment, label: 'Todos', count: s?.total },
      { key: 'FREQUENT' as Segment, label: 'Frecuentes', count: s?.frequent },
      { key: 'AT_RISK' as Segment, label: 'Para recuperar', count: s?.atRisk },
      { key: 'BIRTHDAY' as Segment, label: 'Cumpleaños', count: s?.birthdays },
      { key: 'NEW' as Segment, label: 'Nuevos', count: s?.newCustomers },
    ];
  });
  /** Explica qué muestra el grupo elegido para que el dueño sepa qué hacer con él. */
  readonly segmentHint = computed(() => ({
    ALL: 'Toda tu cartera, ordenada como prefieras.',
    FREQUENT: 'Vinieron 3 veces o más: cuídalos, son la base de tus ventas.',
    AT_RISK: 'Vinieron varias veces pero no vuelven hace más de 30 días. Escríbeles una invitación.',
    BIRTHDAY: 'Cumplen años este mes: un saludo y un detalle los traen a celebrar.',
    NEW: 'Se registraron en los últimos 30 días: lograr su segunda visita es la clave.',
  } as Record<Segment, string>)[this.segment()]);
  readonly topSpend = computed(() => {
    const values = this.customers().map(c => Number(c.totalSpent)).sort((a, b) => b - a);
    return values.length >= 5 ? values[Math.floor(values.length * 0.15)] : Infinity;
  });
  readonly pointsValue = computed(() => {
    const p = this.program(), s = this.summary();
    return p && s ? s.points * Number(p.pointValue) : 0;
  });

  ngOnInit(): void {
    this.load();
    this.loadSummary();
    this.api.get<LoyaltyProgram>('/admin/loyalty').subscribe(value => { this.program.set(value); this.programDraft = { ...value }; });
  }

  load(): void {
    this.api.get<CustomerView[]>(`/staff/customers?q=${encodeURIComponent(this.query)}&sort=${this.sort}&segment=${this.segment()}`).subscribe({
      next: value => {
        this.customers.set(value); this.loading.set(false);
        // Mantiene la ficha abierta si sigue en la lista; si no, abre el primero.
        const keep = value.find(c => c.id === this.selectedId());
        if (this.mode() === 'VIEW') { if (keep) this.refreshDetail(keep.id); else if (value[0]) this.open(value[0]); else { this.selectedId.set(null); this.detail.set(null); } }
      },
      error: err => { this.loading.set(false); this.fail(err, 'No se pudieron cargar los clientes.'); }
    });
  }
  loadSummary(): void { this.api.get<CustomerSummary>('/admin/customers/summary').subscribe({ next: value => this.summary.set(value), error: () => this.summary.set(null) }); }
  searchChanged(): void { window.clearTimeout(this.searchTimer); this.searchTimer = window.setTimeout(() => this.load(), 250); }
  setSegment(segment: Segment): void { this.tab.set('CUSTOMERS'); this.segment.set(segment); this.mode.set('VIEW'); this.load(); }
  setSort(sort: string): void { this.sort = sort; this.load(); }

  open(customer: CustomerView): void {
    this.mode.set('VIEW'); this.selectedId.set(customer.id); this.adjustPoints = null; this.adjustReason = '';
    if (this.detail()?.customer.id !== customer.id) this.detail.set(null);
    this.refreshDetail(customer.id);
  }
  private refreshDetail(id: string): void {
    this.api.get<CustomerDetail>(`/admin/customers/${id}`).subscribe({
      next: value => { if (this.selectedId() === id) this.detail.set(value); }, error: err => this.fail(err, 'No se pudo abrir la ficha.')
    });
  }

  edit(customer: CustomerView | 'NEW'): void {
    this.tab.set('CUSTOMERS');
    if (customer === 'NEW') { this.fullName = ''; this.phone = ''; this.email = ''; this.birthday = ''; this.notes = ''; this.mode.set('NEW'); }
    else { this.fullName = customer.fullName; this.phone = customer.phone; this.email = customer.email || ''; this.birthday = customer.birthday || ''; this.notes = customer.notes || ''; this.mode.set('EDIT'); }
    setTimeout(() => (document.querySelector('.ct-form input') as HTMLElement | null)?.focus(), 60);
  }
  cancelEdit(): void { this.mode.set('VIEW'); }

  save(): void {
    const mode = this.mode(), current = this.detail()?.customer;
    if (mode === 'VIEW' || !this.fullName.trim() || !this.phone.trim() || this.busy()) return;
    const body = { fullName: this.fullName, phone: this.phone, email: this.email || null, birthday: this.birthday || null, notes: this.notes || null };
    const request = mode === 'NEW' || !current ? this.api.post<CustomerView>('/staff/customers', body) : this.api.patch<CustomerView>(`/admin/customers/${current.id}`, body);
    this.busy.set(true);
    request.subscribe({
      next: saved => { this.busy.set(false); this.toast.show('Cliente guardado', saved.fullName, 'success', '✓'); this.open(saved); this.load(); this.loadSummary(); },
      error: err => { this.busy.set(false); this.fail(err, 'No se pudo guardar el cliente.'); }
    });
  }

  quickPoints(chip: { points: number; reason: string }): void { this.adjustPoints = chip.points; this.adjustReason = chip.reason; }
  adjust(): void {
    const current = this.detail();
    if (!current || !this.adjustPoints || !this.adjustReason.trim() || this.busy()) return;
    this.busy.set(true);
    this.api.post<CustomerView>(`/admin/customers/${current.customer.id}/points`, { points: this.adjustPoints, reason: this.adjustReason }).subscribe({
      next: value => { this.busy.set(false); this.toast.show('Puntos actualizados', `${value.fullName}: ${value.points} puntos`, 'success', '✓'); this.open(value); this.load(); this.loadSummary(); },
      error: err => { this.busy.set(false); this.fail(err, 'No se pudieron ajustar los puntos.'); }
    });
  }

  saveProgram(): void {
    this.api.put<LoyaltyProgram>('/admin/loyalty', this.programDraft).subscribe({
      next: value => { this.program.set(value); this.programDraft = { ...value }; this.toast.show('Programa de puntos actualizado', this.programSummary(value), 'success', '✓'); },
      error: err => this.fail(err, 'No se pudo guardar el programa.')
    });
  }

  /** Grupos del cliente, calculados con las mismas reglas que los filtros del servidor. */
  tags(customer: CustomerView): Tag[] {
    const list: Tag[] = [];
    if (Number(customer.totalSpent) >= this.topSpend() && customer.visits >= 3) list.push({ label: 'VIP', tone: 'gold' });
    else if (customer.visits >= 3) list.push({ label: 'Frecuente', tone: 'green' });
    if (this.isAtRisk(customer)) list.push({ label: 'Para recuperar', tone: 'red' });
    if (this.isBirthdayMonth(customer)) list.push({ label: 'Cumpleaños', tone: 'pink' });
    if (customer.visits <= 1 && !this.isAtRisk(customer)) list.push({ label: 'Nuevo', tone: 'blue' });
    return list;
  }
  isAtRisk(customer: CustomerView): boolean { return customer.visits >= 2 && this.daysSince(customer.lastVisitAt) > 30; }
  isBirthdayMonth(customer: CustomerView): boolean { return !!customer.birthday && Number(customer.birthday.slice(5, 7)) === new Date().getMonth() + 1; }
  birthdayPassed(customer: CustomerView): boolean { return this.isBirthdayMonth(customer) && Number(customer.birthday!.slice(8, 10)) < new Date().getDate(); }
  canRedeem(customer: CustomerView): boolean { const p = this.program(); return !!p && p.enabled && customer.points >= p.minRedeem; }
  daysSince(value: string | null): number { return value ? Math.floor((Date.now() - new Date(value).getTime()) / 86400000) : Infinity; }
  ago(value: string | null): string {
    const days = this.daysSince(value);
    if (days === Infinity) return 'Sin visitas';
    if (days === 0) return 'Hoy'; if (days === 1) return 'Ayer';
    if (days < 30) return `Hace ${days} días`;
    const months = Math.floor(days / 30); return months === 1 ? 'Hace 1 mes' : `Hace ${months} meses`;
  }
  initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase(); }
  avatarTone(name: string): string { let hash = 0; for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0; return AVATAR_TONES[hash % AVATAR_TONES.length]; }
  ticket(customer: CustomerView): number { return customer.visits ? Number(customer.totalSpent) / customer.visits : 0; }
  pointsMoney(points: number): number { return points * Number(this.program()?.pointValue || 0); }
  programSummary(program: LoyaltyProgram): string {
    if (!program.enabled) return 'Programa desactivado';
    return `1 punto por cada S/ ${Number(program.spendPerPoint).toFixed(2)} · ${program.minRedeem} puntos = S/ ${(program.minRedeem * program.pointValue).toFixed(2)}`;
  }
  cashback(program: LoyaltyProgram): string { return (Number(program.pointValue) / Number(program.spendPerPoint) * 100).toLocaleString('es-PE', { maximumFractionDigits: 1 }); }
  /** Ejemplo concreto: cuántos puntos y soles gana alguien que gasta S/ 100. */
  example(program: LoyaltyProgram): { points: number; value: number } { const points = Math.floor(100 / Number(program.spendPerPoint || 1)); return { points, value: points * Number(program.pointValue) }; }
  money(value: number | null | undefined): string { return `S/ ${Number(value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`; }
  date(value: string | null): string { return value ? new Intl.DateTimeFormat('es-PE', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(value)) : 'Sin visitas'; }
  birthdayLabel(value: string): string { return new Intl.DateTimeFormat('es-PE', { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(value + 'T00:00:00Z')); }
  /** Enlace de WhatsApp con un mensaje listo según la situación del cliente. */
  whatsapp(customer: CustomerView, kind: 'plain' | 'miss' | 'birthday' | 'points' = 'plain'): string {
    const name = customer.fullName.split(' ')[0];
    const text = {
      plain: `¡Hola ${name}!`,
      miss: `¡Hola ${name}! Te extrañamos 😊 Hace tiempo que no te vemos por aquí. Te esperamos esta semana, ¡tu mesa está lista!`,
      birthday: `¡Feliz cumpleaños, ${name}! 🎉 Ven a celebrar con nosotros este mes: tenemos un detalle especial para ti.`,
      points: `¡Hola ${name}! Tienes ${customer.points} puntos acumulados. En tu próxima visita puedes canjearlos por un descuento 🎁`,
    }[kind];
    return `https://wa.me/51${customer.phone}?text=${encodeURIComponent(text)}`;
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.toast.show('No se pudo completar', err.error?.message || fallback, 'error', '!');
  }
}
