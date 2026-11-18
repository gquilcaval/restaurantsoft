import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { TimeWheelComponent } from '../../shared/ui/time-wheel.component';
import { ToastService } from '../../shared/ui/toast.service';
import {
  DayRow, duration, initials, KIND_LABELS, KIND_NOUNS, limaDate, limaHourMinute, limaTime, MarkKind, MarkView, rowBadge, shiftLabel, twelveHour,
} from './attendance.models';

type Mode = 'list' | 'add' | 'change' | 'void';
const REASONS: Record<Exclude<Mode, 'list'>, string[]> = {
  add: ['Olvidó marcar', 'Tablet sin conexión', 'Error al marcar', 'Otro'],
  change: ['Marcó tarde', 'Error al marcar', 'Tablet sin conexión', 'Otro'],
  void: ['Marcó por error', 'Marcó dos veces', 'Otro'],
};
/** Hora sugerida para una marca olvidada de un día pasado, según su tipo. */
const DEFAULT_TIMES: Record<MarkKind, string> = { IN: '08:00', BREAK_START: '13:00', BREAK_END: '14:00', OUT: '17:00' };

/**
 * Panel de una persona en un día: sus marcas (las anuladas quedan tachadas) y las correcciones
 * del encargado — agregar una marca olvidada, cambiar la hora o anular —, siempre con motivo.
 * En el celular es una hoja inferior que se cierra deslizándola hacia abajo.
 */
@Component({
  selector: 'app-attendance-sheet',
  imports: [FormsModule, IconComponent, TimeWheelComponent],
  templateUrl: './attendance-sheet.component.html',
  styleUrls: ['./attendance-sheet.component.scss', './attendance-sheet-form.scss'],
})
export class AttendanceSheetComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly row = input.required<DayRow>();
  readonly date = input.required<string>();
  readonly canCorrect = input(false);
  readonly isToday = input(false);
  readonly changed = output<void>();
  readonly closed = output<void>();

  readonly mode = signal<Mode>('list');
  readonly openId = signal<string | null>(null);
  readonly target = signal<MarkView | null>(null);
  readonly kind = signal<MarkKind>('IN');
  readonly time = signal('08:00');
  readonly reason = signal('');
  readonly otherReason = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  // Gesto para cerrar deslizando hacia abajo.
  readonly dragY = signal(0);
  readonly dragging = signal(false);
  readonly closing = signal(false);
  private startY = 0;

  readonly kinds: MarkKind[] = ['IN', 'BREAK_START', 'BREAK_END', 'OUT'];
  readonly kindLabels = KIND_LABELS;
  readonly initials = initials;
  readonly limaTime = limaTime;
  readonly duration = duration;
  readonly twelveHour = twelveHour;

  readonly reasons = computed(() => { const mode = this.mode(); return mode === 'list' ? [] : REASONS[mode]; });
  readonly validMarks = computed(() => this.row().marks.filter((mark) => !mark.voidedAt));
  /** Cambiar la hora a la misma hora no corrige nada. */
  readonly unchanged = computed(() => this.mode() === 'change' && !!this.target() && limaHourMinute(this.target()!.at) === this.time());
  readonly finalReason = computed(() => (this.reason() === 'Otro' ? this.otherReason() : this.reason()).trim());
  readonly stateLine = computed(() => [this.row().jobTitle, rowBadge(this.row()).text].filter(Boolean).join(' · '));
  readonly shift = computed(() => shiftLabel(this.row()));
  /** Una salida, refrigerio o regreso antes de la primera entrada cuenta en la madrugada siguiente. */
  readonly nextDay = computed(() => {
    if (this.mode() === 'void' || this.kind() === 'IN') return false;
    const replaced = this.target()?.id;
    const firstIn = this.validMarks().find((mark) => mark.kind === 'IN' && mark.id !== replaced);
    return !!firstIn && this.time() < limaHourMinute(firstIn.at);
  });
  readonly formTitle = computed(() => {
    const target = this.target();
    if (this.mode() === 'add') return 'Agregar marca';
    if (!target) return '';
    const noun = KIND_NOUNS[target.kind];
    return this.mode() === 'change' ? `Cambiar hora de ${noun}`.replace('de el ', 'del ') : `Anular ${noun}`;
  });

  // ——— Lista ———
  toggle(mark: MarkView): void {
    if (mark.voidedAt || !this.canCorrect()) return;
    this.openId.set(this.openId() === mark.id ? null : mark.id);
  }
  /** La marca es de la madrugada del día siguiente (turno de noche). */
  isNextDay(mark: MarkView): boolean { return limaDate(mark.at) !== mark.workDate; }
  sourceLine(mark: MarkView): string {
    if (mark.voidedAt) return `Anulada por ${mark.voidedBy || 'alguien'} · ${mark.voidReason}`;
    if (mark.source === 'MANUAL') return `Agregada por ${mark.createdBy || 'alguien'} · ${mark.note}`;
    return 'Marcó en la tablet';
  }

  // ——— Formularios ———
  startAdd(): void {
    const kind = this.suggestedKind();
    this.openForm('add', null, kind, this.isToday() ? limaHourMinute(Date.now()) : DEFAULT_TIMES[kind]);
  }
  startChange(mark: MarkView): void { this.openForm('change', mark, mark.kind, limaHourMinute(mark.at)); }
  startVoid(mark: MarkView): void { this.openForm('void', mark, mark.kind, limaHourMinute(mark.at)); }
  back(): void { this.mode.set('list'); this.error.set(''); }

  save(): void {
    const reason = this.finalReason();
    if (reason.length < 3) { this.error.set('Elige o escribe el motivo.'); return; }
    const row = this.row(); const target = this.target(); const mode = this.mode();
    this.saving.set(true); this.error.set('');
    const request = mode === 'void' && target
      ? this.api.post(`/admin/attendance/marks/${target.id}/void`, { note: reason })
      : this.api.post('/admin/attendance/marks', {
        userId: row.id, date: target?.workDate || row.workDate || this.date(), time: this.time(), kind: this.kind(), note: reason,
        replaces: mode === 'change' ? target?.id : null,
      });
    request.subscribe({
      next: () => {
        this.saving.set(false);
        const noun = KIND_NOUNS[this.kind()];
        const text = mode === 'void' ? `Se anuló ${noun}.` : mode === 'change' ? `Se cambió la hora de ${noun}.`.replace('de el ', 'del ') : `Se agregó ${noun}.`;
        this.toast.show('Listo', `${text} Queda en Auditoría.`, 'success', '✓');
        this.mode.set('list'); this.openId.set(null);
        this.changed.emit();
      },
      error: (err) => { this.saving.set(false); this.error.set(err.error?.message || 'No se pudo guardar. Revisa tu conexión.'); },
    });
  }

  // ——— Cierre y gesto ———
  close(): void {
    if (this.closing()) return;
    this.closing.set(true);
    setTimeout(() => this.closed.emit(), 220);
  }
  onTouchStart(event: TouchEvent): void { this.startY = event.touches[0].clientY; this.dragging.set(true); }
  onTouchMove(event: TouchEvent): void {
    if (!this.dragging()) return;
    const delta = event.touches[0].clientY - this.startY;
    if (delta > 0) event.preventDefault();
    this.dragY.set(Math.max(0, delta));
  }
  onTouchEnd(): void {
    this.dragging.set(false);
    if (this.dragY() > 110) this.close(); else this.dragY.set(0);
  }

  private openForm(mode: Exclude<Mode, 'list'>, target: MarkView | null, kind: MarkKind, time: string): void {
    this.target.set(target); this.kind.set(kind); this.time.set(time);
    this.reason.set(''); this.otherReason.set(''); this.error.set('');
    this.openId.set(null);
    this.mode.set(mode);
  }
  /** Lo más probable que falte: tras la entrada o el regreso, la salida; tras el refrigerio, el regreso. */
  private suggestedKind(): MarkKind {
    const last = this.validMarks().at(-1)?.kind;
    if (last === 'IN' || last === 'BREAK_END') return 'OUT';
    if (last === 'BREAK_START') return 'BREAK_END';
    return 'IN';
  }
}
