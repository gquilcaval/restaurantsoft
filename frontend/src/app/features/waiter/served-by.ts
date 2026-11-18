/** Mesero a cargo de una mesa: opciones para pasarla y textos cortos. */
export interface WaiterOption {
  id: string;
  fullName: string;
  role: string;
  /** En turno según la asistencia (si el local no la usa, todos cuentan como en turno). */
  onShift: boolean;
  openTables: number;
}

export function firstName(fullName: string | null | undefined): string {
  return (fullName || '').trim().split(/\s+/)[0] || '';
}

export function initialsOf(fullName: string | null | undefined): string {
  return (fullName || '').trim().split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0]).join('').toUpperCase();
}

/** Color estable por persona, para reconocer a cada mesero de un vistazo en el salón. */
export function waiterHue(id: string | null | undefined): number {
  let hash = 0;
  for (const char of id || '') hash = (hash * 31 + char.charCodeAt(0)) % 360;
  return hash;
}
