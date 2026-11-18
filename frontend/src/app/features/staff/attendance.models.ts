/** Tipos y formatos compartidos de la asistencia (vista del día y panel de cada persona). */
export type MarkKind = 'IN' | 'BREAK_START' | 'BREAK_END' | 'OUT';
export type ShiftState = 'ABSENT' | 'WORKING' | 'ON_BREAK' | 'OUT' | 'NO_OUT';
/** Comparación con el horario del día. */
export type Verdict = 'NO_SCHEDULE' | 'REST' | 'ON_TIME' | 'LATE' | 'PENDING' | 'NOT_YET' | 'ABSENT';

export interface MarkView {
  id: string; userId: string; kind: MarkKind; at: string; workDate: string; source: 'DEVICE' | 'MANUAL';
  note: string | null; createdBy: string | null; voidedAt: string | null; voidReason: string | null; voidedBy: string | null;
}
export interface DayRow {
  id: string; fullName: string; jobTitle: string | null; role: string; hasPin: boolean; state: ShiftState;
  firstIn: string | null; lastOut: string | null; workedMinutes: number; needsReview: boolean; workDate: string;
  verdict: Verdict; lateMinutes: number; scheduledStart: string | null; scheduledEnd: string | null; marks: MarkView[];
}
export interface AttendanceDay { enabled: boolean; date: string; today: string; canCorrect: boolean; toleranceMinutes: number; people: DayRow[]; }

/** Cómo se muestra una persona en el día: etiqueta y tono. */
export interface Badge { text: string; tone: 'ok' | 'warn' | 'bad' | 'muted' | 'info'; }

export const LIMA = 'America/Lima';
export const KIND_LABELS: Record<MarkKind, string> = { IN: 'Entrada', BREAK_START: 'Refrigerio', BREAK_END: 'Regreso', OUT: 'Salida' };
/** Con artículo, para armar frases: «la entrada», «el refrigerio». */
export const KIND_NOUNS: Record<MarkKind, string> = { IN: 'la entrada', BREAK_START: 'el refrigerio', BREAK_END: 'el regreso', OUT: 'la salida' };
export const STATE_LABELS: Record<ShiftState, string> = {
  WORKING: 'Trabajando', ON_BREAK: 'En refrigerio', OUT: 'Ya salió', ABSENT: 'Sin marcar', NO_OUT: 'Sin salida',
};

/** Hora del local en formato corto: «5:10 p. m.». */
export function limaTime(iso: string): string {
  return new Intl.DateTimeFormat('es-PE', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: LIMA }).format(new Date(iso));
}
/** Fecha del local (YYYY-MM-DD) de un instante. */
export function limaDate(iso: string | number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: LIMA }).format(new Date(iso));
}
/** Hora del local en 24 h (HH:mm) de un instante. */
export function limaHourMinute(iso: string | number): string {
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: LIMA }).format(new Date(iso));
}
/** «HH:mm» de 24 h a «5:10 p. m.». */
export function twelveHour(value: string): string {
  const [hour, minute] = value.split(':').map(Number);
  return `${hour % 12 || 12}:${String(minute).padStart(2, '0')} ${hour < 12 ? 'a. m.' : 'p. m.'}`;
}
export function duration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  return hours ? `${hours} h ${String(minutes % 60).padStart(2, '0')} min` : `${minutes} min`;
}
export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0]).join('').toUpperCase();
}
export function shiftDate(date: string, days: number): string {
  const value = new Date(date + 'T12:00:00Z');
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** Etiqueta principal de una persona en el día, combinando lo que marcó con su horario. */
export function rowBadge(row: DayRow): Badge {
  if (row.needsReview) return { text: 'Revisar', tone: 'warn' };
  if (row.verdict === 'ABSENT') return { text: 'Falta', tone: 'bad' };
  if (row.verdict === 'NOT_YET') return { text: 'No llega', tone: 'bad' };
  if (row.state === 'ABSENT') {
    if (row.verdict === 'PENDING' && row.scheduledStart) return { text: `Entra ${limaTime(row.scheduledStart)}`, tone: 'muted' };
    if (row.verdict === 'REST') return { text: 'Descanso', tone: 'muted' };
    return { text: 'Sin marcar', tone: 'muted' };
  }
  const tones: Record<ShiftState, Badge['tone']> = { WORKING: 'ok', ON_BREAK: 'warn', OUT: 'muted', NO_OUT: 'warn', ABSENT: 'muted' };
  return { text: STATE_LABELS[row.state], tone: tones[row.state] };
}
/** Orden de la lista: lo que requiere atención primero; descansos y sin horario al final. */
export function rowRank(row: DayRow): number {
  if (row.needsReview) return 0;
  const byState: Partial<Record<ShiftState, number>> = { WORKING: 1, ON_BREAK: 2, NO_OUT: 3, OUT: 7 };
  if (byState[row.state] !== undefined) return byState[row.state]!;
  return ({ NOT_YET: 4, ABSENT: 5, PENDING: 6 } as Partial<Record<Verdict, number>>)[row.verdict] ?? 8;
}
/** Turno del horario: «4:50 p. m. – 1:00 a. m.». */
export function shiftLabel(row: DayRow): string {
  return row.scheduledStart && row.scheduledEnd ? `${limaTime(row.scheduledStart)} – ${limaTime(row.scheduledEnd)}` : '';
}

// ——— Horarios ———
export interface DayShift { weekday: number; start: string | null; end: string | null; }
export interface PersonSchedule {
  id: string; fullName: string; jobTitle: string | null; role: string; hasPin: boolean; validFrom: string | null; days: DayShift[];
}
export interface Schedules { toleranceMinutes: number; canEditTolerance: boolean; people: PersonSchedule[]; }

export const WEEKDAY_LETTERS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];
export const WEEKDAY_NAMES = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'];
const WEEKDAY_SHORT = ['lun', 'mar', 'mié', 'jue', 'vie', 'sáb', 'dom'];

/** «17:50:00» → «17:50». */
export function hhmm(value: string | null): string | null { return value ? value.slice(0, 5) : null; }

/** Resumen de la semana: «Lun a sáb · 5:50 p. m. – 11:00 p. m.»; un renglón por cada horario distinto. */
export function weekSummary(days: DayShift[]): string[] {
  const groups: { first: number; last: number; start: string; end: string }[] = [];
  for (const day of days) {
    const start = hhmm(day.start); const end = hhmm(day.end);
    if (!start || !end) continue;
    const previous = groups.at(-1);
    if (previous && previous.last === day.weekday - 1 && previous.start === start && previous.end === end) previous.last = day.weekday;
    else groups.push({ first: day.weekday, last: day.weekday, start, end });
  }
  return groups.map((group) => {
    const span = group.first === group.last ? WEEKDAY_SHORT[group.first - 1]
      : `${WEEKDAY_SHORT[group.first - 1]} a ${WEEKDAY_SHORT[group.last - 1]}`;
    return `${span[0].toUpperCase()}${span.slice(1)} · ${twelveHour(group.start)} – ${twelveHour(group.end)}`;
  });
}

// ——— Reporte del período ———
export interface ReportDay {
  date: string; scheduledStart: string | null; scheduledEnd: string | null; firstIn: string | null; lastOut: string | null;
  workedMinutes: number; state: ShiftState; verdict: Verdict; lateMinutes: number; needsReview: boolean;
}
export interface ReportRow {
  id: string; fullName: string; jobTitle: string | null; role: string; documentNumber: string | null; scheduledDays: number;
  daysWorked: number; workedMinutes: number; lateCount: number; lateMinutes: number; absences: number; noOut: number;
  toReview: number; days: ReportDay[];
}
export interface AttendanceReport {
  enabled: boolean; from: string; to: string; today: string; toleranceMinutes: number; branchName: string; people: ReportRow[];
}
/** Color de un día en la tira del reporte. */
export function dayTone(day: ReportDay): 'ok' | 'warn' | 'bad' | 'rest' | 'none' {
  if (day.needsReview || day.state === 'NO_OUT') return 'warn';
  if (day.verdict === 'ABSENT' || day.verdict === 'NOT_YET') return 'bad';
  if (day.verdict === 'LATE') return 'warn';
  if (day.state !== 'ABSENT') return 'ok';
  return day.verdict === 'REST' ? 'rest' : 'none';
}
