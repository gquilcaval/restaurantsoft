import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ToastService } from '../../shared/ui/toast.service';
import { AttendanceReport, dayTone, duration, initials, limaDate, limaTime, ReportDay } from './attendance.models';

type Part = 'MONTH' | 'FIRST' | 'SECOND';

/** Reporte para la planilla: por persona, días y horas trabajadas, tardanzas y faltas del mes o la quincena. */
@Component({
  selector: 'app-attendance-report',
  imports: [IconComponent],
  templateUrl: './attendance-report.component.html',
  styleUrls: ['./attendance-list.scss', './attendance-report.component.scss'],
})
export class AttendanceReportComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  /** Mes elegido (YYYY-MM) y qué parte: todo el mes o una quincena. */
  readonly month = signal(limaDate(Date.now()).slice(0, 7));
  readonly part = signal<Part>('MONTH');
  readonly report = signal<AttendanceReport | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly downloading = signal(false);
  readonly openId = signal<string | null>(null);
  readonly initials = initials;
  readonly duration = duration;
  readonly tone = dayTone;
  readonly parts: { key: Part; label: string }[] = [
    { key: 'MONTH', label: 'Mes' }, { key: 'FIRST', label: '1.ª quincena' }, { key: 'SECOND', label: '2.ª quincena' },
  ];

  readonly range = computed(() => {
    const [year, month] = this.month().split('-').map(Number);
    const last = new Date(Date.UTC(year, month, 0)).getUTCDate();
    const day = (value: number) => `${this.month()}-${String(value).padStart(2, '0')}`;
    if (this.part() === 'FIRST') return { from: day(1), to: day(15) };
    if (this.part() === 'SECOND') return { from: day(16), to: day(last) };
    return { from: day(1), to: day(last) };
  });
  readonly monthLabel = computed(() => {
    const label = new Intl.DateTimeFormat('es-PE', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(this.month() + '-15T12:00:00Z'));
    return label[0].toUpperCase() + label.slice(1);
  });
  /** Días del período que aún no llegan: se muestran tenues en la tira para que se vea el mes completo. */
  readonly pending = computed(() => {
    const { from, to } = this.range();
    const total = (Date.parse(to) - Date.parse(from)) / 86400000 + 1;
    const evaluated = this.report()?.people[0]?.days.length ?? total;
    return Array.from({ length: Math.max(0, total - evaluated) }, (_, index) => index);
  });
  readonly isCurrentMonth = computed(() => this.month() === limaDate(Date.now()).slice(0, 7));
  /** La segunda quincena del mes en curso aún no empieza hasta el 16. */
  readonly futurePart = computed(() => this.range().from > limaDate(Date.now()));
  readonly totals = computed(() => {
    const people = this.report()?.people || [];
    const sum = (pick: (row: (typeof people)[number]) => number) => people.reduce((total, row) => total + pick(row), 0);
    return [
      { value: String(Math.round(sum((row) => row.workedMinutes) / 6) / 10).replace('.', ','), label: 'Horas trabajadas', tone: 'muted' },
      { value: String(sum((row) => row.lateCount)), label: 'Tardanzas', tone: 'warn' },
      { value: String(sum((row) => row.absences)), label: 'Faltas', tone: 'bad' },
      { value: String(sum((row) => row.noOut + row.toReview)), label: 'Por revisar', tone: 'warn' },
    ];
  });
  readonly evaluatedNote = computed(() => {
    const report = this.report();
    if (!report || report.to <= report.today) return '';
    return `Evaluado hasta hoy, ${new Intl.DateTimeFormat('es-PE', { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(report.today + 'T12:00:00Z'))}.`;
  });

  ngOnInit(): void { this.load(); }

  load(): void {
    if (this.futurePart()) { this.report.set(null); this.failed.set(false); return; }
    const { from, to } = this.range();
    this.loading.set(true);
    this.api.get<AttendanceReport>(`/admin/attendance/report?from=${from}&to=${to}`).subscribe({
      next: (value) => {
        this.loading.set(false); this.failed.set(false);
        if (value.from === this.range().from && value.to === this.range().to) this.report.set(value);
      },
      error: () => { this.loading.set(false); this.failed.set(true); },
    });
  }

  shiftMonth(step: number): void {
    const [year, month] = this.month().split('-').map(Number);
    const next = new Date(Date.UTC(year, month - 1 + step, 1)).toISOString().slice(0, 7);
    if (next > limaDate(Date.now()).slice(0, 7)) return;
    this.month.set(next); this.openId.set(null); this.load();
  }
  setPart(part: Part): void { this.part.set(part); this.openId.set(null); this.load(); }
  toggle(id: string): void { this.openId.set(this.openId() === id ? null : id); }

  download(): void {
    const { from, to } = this.range();
    this.downloading.set(true);
    this.http.get(`/api/admin/attendance/report.xlsx?from=${from}&to=${to}`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.downloading.set(false);
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url; link.download = `asistencia-${from}-al-${to}.xlsx`; link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: () => { this.downloading.set(false); this.toast.show('No se pudo completar', 'No se pudo descargar el Excel.', 'error', '!'); },
    });
  }

  /** Texto de un día en el detalle de una persona. */
  dayLine(day: ReportDay): string {
    const times = day.firstIn ? `${limaTime(day.firstIn)} – ${day.lastOut ? limaTime(day.lastOut) : 'sin salida'} · ${duration(day.workedMinutes)}` : '';
    const status = day.needsReview ? 'Por revisar' : day.verdict === 'ABSENT' ? 'Falta' : day.verdict === 'NOT_YET' ? 'No llega'
      : day.verdict === 'LATE' ? `Tarde ${day.lateMinutes} min` : day.verdict === 'REST' && !day.firstIn ? 'Descanso' : '';
    return [times, status].filter(Boolean).join(' · ') || 'Sin marcas';
  }
  dayLabel(date: string): string {
    return new Intl.DateTimeFormat('es-PE', { weekday: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(date + 'T12:00:00Z'));
  }
  dayNumber(date: string): number { return Number(date.slice(8)); }
}
