import { Component, computed, HostListener, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ThemeToggleComponent } from '../../shared/ui/theme-toggle.component';

type MarkKind = 'IN' | 'BREAK_START' | 'BREAK_END' | 'OUT';
type WorkState = 'OUT' | 'WORKING' | 'ON_BREAK';
interface Person { id: string; fullName: string; jobTitle: string | null; role: string; hasPin: boolean; state: WorkState; lastAt: string | null; }
interface Board { deviceName: string; branchName: string; enabled: boolean; serverTime: string; staff: Person[]; }
interface Receipt { fullName: string; kind: MarkKind; markedAt: string; state: WorkState; workedMinutes: number; }
interface Action { kind: MarkKind; label: string; icon: string; }

const DEVICE_TOKEN_KEY = 'restos_device_token';
const LIMA = 'America/Lima';

/**
 * Pantalla de marcación de asistencia para la tablet del local.
 * No requiere iniciar sesión: funciona con la autorización del equipo y el PIN de cada persona.
 */
@Component({
  imports: [IconComponent, RouterLink, ThemeToggleComponent],
  templateUrl: './clock.page.html',
  styleUrls: ['./clock.page.scss', './clock-pad.scss'],
})
export class ClockPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);

  readonly board = signal<Board | null>(null);
  /** 'loading' mientras carga; 'no-device' si este equipo no está autorizado; 'offline' si no hay conexión. */
  readonly status = signal<'loading' | 'ready' | 'no-device' | 'offline'>('loading');
  readonly selected = signal<Person | null>(null);
  readonly action = signal<Action | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly error = signal('');
  readonly sending = signal(false);
  readonly now = signal(Date.now());
  readonly query = signal('');
  readonly digits = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
  readonly slots = [0, 1, 2, 3, 4, 5];
  readonly pin = signal('');

  /** Diferencia con la hora del servidor: la marca vale con la hora del servidor, no con la de la tablet. */
  private serverOffset = 0;
  private clockTimer = 0;
  private boardTimer = 0;
  private idleTimer = 0;
  private receiptTimer = 0;

  readonly people = computed(() => {
    const text = this.query().trim().toLowerCase();
    return (this.board()?.staff || []).filter((person) => !text || person.fullName.toLowerCase().includes(text));
  });
  readonly clock = computed(() => {
    const parts = new Intl.DateTimeFormat('es-PE', { hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true, timeZone: LIMA })
      .formatToParts(new Date(this.now() + this.serverOffset));
    const part = (type: string) => parts.find((item) => item.type === type)?.value || '';
    return { time: `${part('hour')}:${part('minute')}`, seconds: part('second'), period: part('dayPeriod') };
  });
  readonly dateLabel = computed(() =>
    new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: LIMA }).format(new Date(this.now() + this.serverOffset)));

  ngOnInit(): void {
    this.load();
    this.clockTimer = window.setInterval(() => this.now.set(Date.now()), 1000);
    this.boardTimer = window.setInterval(() => { if (!this.selected() && !this.receipt()) this.load(); }, 60000);
  }
  ngOnDestroy(): void {
    [this.clockTimer, this.boardTimer].forEach((timer) => window.clearInterval(timer));
    [this.idleTimer, this.receiptTimer].forEach((timer) => window.clearTimeout(timer));
  }

  load(): void {
    const token = this.deviceToken();
    if (!token) { this.status.set('no-device'); return; }
    this.api.post<Board>('/auth/device/attendance', { deviceToken: token }).subscribe({
      next: (value) => {
        this.serverOffset = new Date(value.serverTime).getTime() - Date.now();
        this.board.set(value);
        this.status.set('ready');
      },
      error: (err) => { if (!this.board() || err.status === 401) this.status.set(err.status === 401 ? 'no-device' : 'offline'); },
    });
  }

  // ——— Flujo: persona → acción → PIN → comprobante ———
  actionsFor(person: Person): Action[] {
    const out: Action = { kind: 'OUT', label: 'Marcar salida', icon: 'log-out' };
    if (person.state === 'WORKING') return [{ kind: 'BREAK_START', label: 'Salir a refrigerio', icon: 'utensils' }, out];
    if (person.state === 'ON_BREAK') return [{ kind: 'BREAK_END', label: 'Volver del refrigerio', icon: 'undo' }, out];
    return [{ kind: 'IN', label: 'Marcar entrada', icon: 'check' }];
  }
  choose(person: Person): void {
    if (!person.hasPin) return;
    const actions = this.actionsFor(person);
    this.selected.set(person);
    // Con una sola acción posible (la entrada) se pasa directo al PIN.
    this.action.set(actions.length === 1 ? actions[0] : null);
    this.pin.set(''); this.error.set('');
    this.touch();
  }
  pick(action: Action): void { this.action.set(action); this.pin.set(''); this.error.set(''); this.touch(); }
  back(): void {
    const person = this.selected();
    if (person && this.action() && this.actionsFor(person).length > 1) { this.action.set(null); this.pin.set(''); this.error.set(''); this.touch(); }
    else this.close();
  }
  close(): void {
    window.clearTimeout(this.idleTimer); window.clearTimeout(this.receiptTimer);
    this.selected.set(null); this.action.set(null); this.receipt.set(null); this.pin.set(''); this.error.set(''); this.query.set('');
  }
  press(digit: string): void { if (this.pin().length < 6 && !this.sending()) { this.pin.update((value) => value + digit); this.error.set(''); this.touch(); } }
  backspace(): void { this.pin.update((value) => value.slice(0, -1)); this.touch(); }

  submit(): void {
    const person = this.selected(); const action = this.action(); const token = this.deviceToken();
    if (!person || !action || !token || this.sending() || this.pin().length < 4) return;
    this.sending.set(true); this.error.set('');
    this.api.post<Receipt>('/auth/device/attendance/mark', { deviceToken: token, userId: person.id, pin: this.pin(), kind: action.kind }).subscribe({
      next: (value) => {
        this.sending.set(false); this.pin.set('');
        this.receipt.set(value);
        this.load();
        window.clearTimeout(this.idleTimer);
        this.receiptTimer = window.setTimeout(() => this.close(), 5000);
      },
      error: (err) => {
        this.sending.set(false); this.pin.set('');
        this.error.set(err.status === 0 ? 'Sin conexión. Inténtalo otra vez.' : (err.error?.message || 'PIN incorrecto'));
        // Si el estado cambió en otro equipo, se vuelve a cargar para ofrecer la acción correcta.
        if (err.status === 409) this.load();
        this.touch();
      },
    });
  }

  /** Teclado físico: útil si la marcación se hace en la PC de caja. */
  @HostListener('document:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.key === 'Escape' && (this.selected() || this.receipt())) { event.preventDefault(); this.close(); return; }
    if (!this.action() || this.receipt()) return;
    if (/^\d$/.test(event.key)) { event.preventDefault(); this.press(event.key); }
    else if (event.key === 'Backspace') { event.preventDefault(); this.backspace(); }
    else if (event.key === 'Enter') { event.preventDefault(); this.submit(); }
  }

  // ——— Textos ———
  initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0]).join('').toUpperCase(); }
  firstName(name: string): string { return name.split(/\s+/)[0]; }
  stateLine(person: Person): string {
    if (!person.hasPin) return 'Sin PIN';
    if (person.state === 'WORKING') return 'Trabajando';
    if (person.state === 'ON_BREAK') return 'En refrigerio';
    return person.jobTitle || this.roleLabel(person.role);
  }
  roleLabel(role: string): string {
    return ({ WAITER: 'Mesero', KITCHEN: 'Cocina', BRANCH_MANAGER: 'Encargado' } as Record<string, string>)[role] || '';
  }
  receiptTitle(kind: MarkKind): string {
    return ({ IN: 'Entrada registrada', BREAK_START: 'Refrigerio iniciado', BREAK_END: 'Regreso registrado', OUT: 'Salida registrada' } as Record<MarkKind, string>)[kind];
  }
  receiptNote(receipt: Receipt): string {
    const name = this.firstName(receipt.fullName);
    if (receipt.kind === 'IN') return `¡Buen turno, ${name}!`;
    if (receipt.kind === 'BREAK_START') return `¡Buen provecho, ${name}!`;
    if (receipt.kind === 'BREAK_END') return `De vuelta al trabajo, ${name}.`;
    return `Trabajaste ${this.duration(receipt.workedMinutes)}. ¡Hasta la próxima, ${name}!`;
  }
  time(iso: string): string {
    return new Intl.DateTimeFormat('es-PE', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: LIMA }).format(new Date(iso));
  }
  duration(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    return hours ? `${hours} h ${String(minutes % 60).padStart(2, '0')} min` : `${minutes} min`;
  }

  /** Si alguien deja la pantalla a medias, vuelve sola a la lista para que nadie marque por otro. */
  private touch(): void {
    window.clearTimeout(this.idleTimer);
    this.idleTimer = window.setTimeout(() => this.close(), 30000);
  }
  private deviceToken(): string | null {
    try { return localStorage.getItem(DEVICE_TOKEN_KEY); } catch { return null; }
  }
}
