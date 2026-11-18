import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ToastService } from '../../shared/ui/toast.service';
import { hhmm, initials, PersonSchedule, Schedules, weekSummary, WEEKDAY_LETTERS } from './attendance.models';
import { ScheduleSheetComponent } from './schedule-sheet.component';

/** Horario semanal de cada persona y la tolerancia de tardanza del restaurante. */
@Component({
  selector: 'app-schedules',
  imports: [IconComponent, ScheduleSheetComponent],
  templateUrl: './schedules.component.html',
  styleUrl: './schedules.component.scss',
})
export class SchedulesComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly data = signal<Schedules | null>(null);
  readonly failed = signal(false);
  readonly selectedId = signal<string | null>(null);
  readonly savingTolerance = signal(false);
  readonly toleranceOptions = [0, 5, 10, 15, 20];
  readonly letters = WEEKDAY_LETTERS;
  readonly initials = initials;

  readonly people = computed(() => (this.data()?.people || []).map((person) => ({
    person,
    summary: weekSummary(person.days),
    working: person.days.map((day) => !!hhmm(day.start)),
  })));
  readonly selected = computed(() => this.data()?.people.find((person) => person.id === this.selectedId()) || null);
  readonly withoutSchedule = computed(() => this.people().filter((item) => !item.summary.length).length);

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.get<Schedules>('/admin/attendance/schedules').subscribe({
      next: (value) => { this.data.set(value); this.failed.set(false); },
      error: () => this.failed.set(true),
    });
  }

  setTolerance(minutes: number): void {
    const data = this.data();
    if (!data || data.toleranceMinutes === minutes || this.savingTolerance()) return;
    this.savingTolerance.set(true);
    this.api.put<{ toleranceMinutes: number }>('/admin/attendance/settings', { toleranceMinutes: minutes }).subscribe({
      next: (value) => {
        this.savingTolerance.set(false);
        this.data.set({ ...data, toleranceMinutes: value.toleranceMinutes });
        this.toast.show('Listo', minutes ? `Tolerancia de ${minutes} minutos.` : 'Sin tolerancia: cualquier minuto cuenta como tardanza.', 'success', '✓');
      },
      error: (err) => {
        this.savingTolerance.set(false);
        this.toast.show('No se pudo completar', err.error?.message || 'No se pudo guardar la tolerancia.', 'error', '!');
      },
    });
  }

  saved(person: PersonSchedule): void {
    const data = this.data();
    if (data) this.data.set({ ...data, people: data.people.map((item) => (item.id === person.id ? person : item)) });
  }
}
