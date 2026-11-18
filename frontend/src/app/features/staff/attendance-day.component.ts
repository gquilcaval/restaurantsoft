import { Component, computed, inject, OnDestroy, OnInit, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { AttendanceSheetComponent } from './attendance-sheet.component';
import { AttendanceDay, duration, initials, limaTime, rowBadge, rowRank, ShiftState, shiftDate } from './attendance.models';

/** Asistencia de un día en la sucursal: resumen, una fila por persona y el panel para corregir. */
@Component({
  selector: 'app-attendance-day',
  imports: [IconComponent, RouterLink, AttendanceSheetComponent],
  templateUrl: './attendance-day.component.html',
  styleUrls: ['./attendance-list.scss', './attendance-day.component.scss'],
})
export class AttendanceDayComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  /** Avisa cada carga del día de hoy (para el contador de la pestaña). */
  readonly loadedToday = output<AttendanceDay>();

  readonly day = signal<AttendanceDay | null>(null);
  /** null = hoy. */
  readonly date = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly failed = signal(false);
  private timer = 0;

  readonly isToday = computed(() => { const day = this.day(); return !!day && day.date === day.today; });
  readonly selected = computed(() => this.day()?.people.find((row) => row.id === this.selectedId()) || null);
  /** Lo que requiere atención primero; descansos y personas sin horario al final. */
  readonly rows = computed(() => [...(this.day()?.people || [])]
    .sort((a, b) => rowRank(a) - rowRank(b) || a.fullName.localeCompare(b.fullName))
    .map((row) => ({ row, badge: rowBadge(row) })));
  readonly stats = computed(() => {
    const people = this.day()?.people || [];
    const count = (test: (state: ShiftState, verdict: string) => boolean) => people.filter((row) => test(row.state, row.verdict)).length;
    const late = count((_, verdict) => verdict === 'LATE');
    if (this.isToday()) {
      return [
        { value: String(count((state) => state === 'WORKING' || state === 'ON_BREAK')), label: 'En el local', tone: 'ok' },
        { value: String(late), label: late === 1 ? 'Tardanza' : 'Tardanzas', tone: 'warn' },
        { value: String(count((_, verdict) => verdict === 'NOT_YET' || verdict === 'ABSENT')), label: 'No llegaron', tone: 'bad' },
        { value: String(count((state) => state === 'OUT')), label: 'Ya salieron', tone: 'muted' },
      ];
    }
    const total = people.reduce((sum, row) => sum + row.workedMinutes, 0);
    const absent = count((_, verdict) => verdict === 'ABSENT');
    return [
      { value: String(count((state) => state !== 'ABSENT')), label: 'Asistieron', tone: 'ok' },
      { value: String(late), label: late === 1 ? 'Tardanza' : 'Tardanzas', tone: 'warn' },
      { value: String(absent), label: absent === 1 ? 'Falta' : 'Faltas', tone: 'bad' },
      { value: String(Math.round(total / 6) / 10).replace('.', ','), label: 'Horas en total', tone: 'muted' },
    ];
  });
  readonly dayTitle = computed(() => {
    const day = this.day();
    if (!day) return '';
    const label = new Intl.DateTimeFormat('es-PE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' })
      .format(new Date(day.date + 'T12:00:00Z'));
    if (day.date === day.today) return `Hoy, ${label}`;
    if (day.date === shiftDate(day.today, -1)) return `Ayer, ${label}`;
    return label[0].toUpperCase() + label.slice(1);
  });

  readonly initials = initials;
  readonly time = limaTime;
  readonly duration = duration;

  ngOnInit(): void {
    this.load();
    // Hoy cambia solo a lo largo del día: se actualiza cada 30 segundos mientras se mira.
    this.timer = window.setInterval(() => { if (this.isToday()) this.load(); }, 30000);
  }
  ngOnDestroy(): void { window.clearInterval(this.timer); }

  load(): void {
    const date = this.date();
    this.api.get<AttendanceDay>('/admin/attendance/day' + (date ? `?date=${date}` : '')).subscribe({
      next: (value) => {
        this.failed.set(false);
        // Si mientras cargaba se cambió de día, esta respuesta ya no sirve.
        if ((this.date() || value.today) !== value.date) return;
        this.day.set(value);
        if (value.date === value.today) this.loadedToday.emit(value);
      },
      error: () => this.failed.set(true),
    });
  }

  go(step: number): void {
    const day = this.day();
    if (!day) return;
    const next = shiftDate(day.date, step);
    if (next > day.today) return;
    this.date.set(next === day.today ? null : next);
    this.selectedId.set(null);
    this.load();
  }
  goToday(): void { if (!this.isToday()) { this.date.set(null); this.selectedId.set(null); this.load(); } }

  roleLabel(role: string): string {
    return ({ WAITER: 'Mesero', KITCHEN: 'Cocina', BRANCH_MANAGER: 'Encargado', STAFF: 'Personal' } as Record<string, string>)[role] || role;
  }
}
