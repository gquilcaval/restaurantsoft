import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { TimeWheelComponent } from '../../shared/ui/time-wheel.component';
import { ToastService } from '../../shared/ui/toast.service';
import { hhmm, initials, PersonSchedule, twelveHour, WEEKDAY_LETTERS, WEEKDAY_NAMES } from './attendance.models';

interface EditableDay { weekday: number; on: boolean; start: string; end: string; }
type Field = 'start' | 'end';

/**
 * Editor del horario semanal de una persona: qué días trabaja y a qué hora entra y sale cada día.
 * En el celular es una hoja inferior que se cierra deslizándola hacia abajo.
 */
@Component({
  selector: 'app-schedule-sheet',
  imports: [IconComponent, TimeWheelComponent],
  templateUrl: './schedule-sheet.component.html',
  styleUrls: ['./attendance-sheet.component.scss', './schedule-sheet.component.scss'],
})
export class ScheduleSheetComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly person = input.required<PersonSchedule>();
  readonly saved = output<PersonSchedule>();
  readonly closed = output<void>();

  readonly days = signal<EditableDay[]>([]);
  /** Hora que se está eligiendo con la rueda. */
  readonly editing = signal<{ weekday: number; field: Field } | null>(null);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly dragY = signal(0);
  readonly dragging = signal(false);
  readonly closing = signal(false);
  private startY = 0;

  readonly letters = WEEKDAY_LETTERS;
  readonly names = WEEKDAY_NAMES;
  readonly initials = initials;
  readonly twelveHour = twelveHour;

  readonly working = computed(() => this.days().filter((day) => day.on));
  /** Hay días con horas distintas: se ofrece igualarlos al primero. */
  readonly mixed = computed(() => {
    const [first, ...rest] = this.working();
    return !!first && rest.some((day) => day.start !== first.start || day.end !== first.end);
  });
  readonly editKey = computed(() => { const edit = this.editing(); return edit ? `${edit.weekday}-${edit.field}` : ''; });
  readonly editValue = computed(() => {
    const edit = this.editing();
    const day = edit && this.days().find((item) => item.weekday === edit.weekday);
    return day ? day[edit!.field] : '09:00';
  });

  ngOnInit(): void {
    const days = this.person().days;
    const firstOn = days.find((day) => day.start);
    // Los días libres guardan una hora sugerida por si se activan: la del primer día que trabaja.
    const start = hhmm(firstOn?.start || null) || '09:00';
    const end = hhmm(firstOn?.end || null) || '18:00';
    this.days.set(days.map((day) => ({ weekday: day.weekday, on: !!day.start, start: hhmm(day.start) || start, end: hhmm(day.end) || end })));
  }

  toggleDay(weekday: number): void {
    this.editing.set(null);
    this.update(weekday, (day) => ({ ...day, on: !day.on }));
  }
  edit(weekday: number, field: Field): void {
    const current = this.editing();
    this.editing.set(current?.weekday === weekday && current.field === field ? null : { weekday, field });
  }
  setTime(value: string): void {
    const edit = this.editing();
    if (edit) this.update(edit.weekday, (day) => ({ ...day, [edit.field]: value }));
  }
  /** Copia las horas del primer día que trabaja a todos los demás días que trabaja. */
  sameForAll(): void {
    const first = this.working()[0];
    if (!first) return;
    this.editing.set(null);
    this.days.update((days) => days.map((day) => (day.on ? { ...day, start: first.start, end: first.end } : day)));
  }
  overnight(day: EditableDay): boolean { return day.end < day.start; }

  save(clear = false): void {
    const days = this.days();
    if (!clear && days.some((day) => day.on && day.start === day.end)) {
      this.error.set('La entrada y la salida no pueden ser a la misma hora.');
      return;
    }
    this.saving.set(true); this.error.set('');
    const body = { days: days.map((day) => ({ weekday: day.weekday, start: day.on && !clear ? day.start : null, end: day.on && !clear ? day.end : null })) };
    this.api.put<PersonSchedule>(`/admin/attendance/schedules/${this.person().id}`, body).subscribe({
      next: (value) => {
        this.saving.set(false);
        this.toast.show('Listo', clear ? `${this.person().fullName} quedó sin horario fijo.` : `Horario de ${this.person().fullName} guardado. Cuenta desde hoy.`, 'success', '✓');
        this.saved.emit(value);
        this.close();
      },
      error: (err) => { this.saving.set(false); this.error.set(err.error?.message || 'No se pudo guardar. Revisa tu conexión.'); },
    });
  }

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

  private update(weekday: number, change: (day: EditableDay) => EditableDay): void {
    this.days.update((days) => days.map((day) => (day.weekday === weekday ? change(day) : day)));
  }
}
