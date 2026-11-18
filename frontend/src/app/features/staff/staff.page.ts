import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { AttendanceDayComponent } from './attendance-day.component';
import { AttendanceReportComponent } from './attendance-report.component';
import { AttendanceDay } from './attendance.models';
import { SchedulesComponent } from './schedules.component';
import { TeamComponent } from './team.component';

type Tab = 'TEAM' | 'ATTENDANCE' | 'SCHEDULES';

/** Personal del local: el equipo, su asistencia y sus horarios. Cada pestaña es un componente propio. */
@Component({
  imports: [AppShellComponent, IconComponent, TeamComponent, AttendanceDayComponent, AttendanceReportComponent, SchedulesComponent],
  templateUrl: './staff.page.html',
  styleUrl: './staff.page.scss',
})
export class StaffPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  readonly tab = signal<Tab>('TEAM');
  readonly activeCount = signal(0);
  readonly today = signal<AttendanceDay | null>(null);
  /** Asistencia: el día a día o el reporte del mes para la planilla. */
  readonly attendanceView = signal<'DAY' | 'PERIOD'>('DAY');

  readonly presentCount = computed(() => (this.today()?.people || []).filter((row) => row.state === 'WORKING' || row.state === 'ON_BREAK').length);
  readonly headerSummary = computed(() => {
    const people = `${this.activeCount()} ${this.activeCount() === 1 ? 'persona' : 'personas'}`;
    return this.today()?.enabled ? `${people} · ${this.presentCount()} en el local ahora` : people;
  });

  ngOnInit(): void {
    // Se carga el día de hoy para saber si el módulo de asistencia está activo y cuántos hay en el local.
    this.api.get<AttendanceDay>('/admin/attendance/day').subscribe({ next: (value) => this.today.set(value), error: () => { /* la pestaña avisa */ } });
    const wanted = this.route.snapshot.queryParamMap.get('tab');
    if (wanted === 'asistencia') this.tab.set('ATTENDANCE');
    if (wanted === 'horarios') this.tab.set('SCHEDULES');
    if (wanted === 'reporte') { this.tab.set('ATTENDANCE'); this.attendanceView.set('PERIOD'); }
  }
}
