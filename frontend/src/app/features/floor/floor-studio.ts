import { CANVAS, FloorElement, Point } from '../../shared/floor/floor.models';

/** Mesa ubicada con su tamaño, tal como la dibuja el editor. */
export interface PlacedTable {
  id: string;
  label: string;
  capacity: number;
  x: number;
  y: number;
  width: number;
  height: number;
  round?: boolean;
}

/** Hasta dónde llegan las sillas desde el borde de la mesa (unidades del lienzo). */
const CHAIR_DEPTH = 20;

/** Centímetros reales por unidad del lienzo según el ancho del salón en metros. */
export const cmPerUnit = (widthMeters: number): number => (widthMeters * 100) / CANVAS.width;

const radius = (table: PlacedTable): number => Math.max(table.width, table.height) / 2 + CHAIR_DEPTH;
/** Alcance de la mesa con sus sillas en una dirección: el rectángulo real, no un círculo. */
const extent = (table: PlacedTable, ux: number, uy: number): number => table.round
  ? table.width / 2 + CHAIR_DEPTH
  : Math.abs(ux) * (table.width / 2 + CHAIR_DEPTH) + Math.abs(uy) * (table.height / 2 + CHAIR_DEPTH);

export interface AisleIssue {
  key: string;
  a: string;
  b: string;
  from: Point;
  to: Point;
  cm: number;
}

/** Pasillos angostos entre mesas (con sus sillas) y frente a barras, paredes o cocina. */
export function aisleIssues(tables: PlacedTable[], elements: FloorElement[], widthMeters: number, minCm: number): AisleIssue[] {
  const scale = cmPerUnit(widthMeters);
  const issues: AisleIssue[] = [];
  for (let i = 0; i < tables.length; i++) {
    for (let j = i + 1; j < tables.length; j++) {
      const a = tables[i], b = tables[j];
      const distance = Math.hypot(b.x - a.x, b.y - a.y);
      if (distance > radius(a) + radius(b) + minCm / scale) continue;
      const ux = (b.x - a.x) / (distance || 1), uy = (b.y - a.y) / (distance || 1);
      const ea = extent(a, ux, uy), eb = extent(b, ux, uy);
      const cm = Math.round((distance - ea - eb) * scale);
      if (cm < minCm) {
        issues.push({ key: a.id + b.id, a: a.label, b: b.label, cm: Math.max(0, cm),
          from: { x: a.x + ux * ea, y: a.y + uy * ea }, to: { x: b.x - ux * eb, y: b.y - uy * eb } });
      }
    }
  }
  const solid = elements.filter((el) => ['BAR', 'WALL', 'KITCHEN', 'CASHIER', 'WC', 'STAIRS'].includes(el.kind));
  for (const table of tables) {
    for (const el of solid) {
      const nx = Math.max(el.x, Math.min(table.x, el.x + el.width));
      const ny = Math.max(el.y, Math.min(table.y, el.y + el.height));
      const distance = Math.hypot(table.x - nx, table.y - ny);
      if (distance <= 0) continue;
      const ux = (nx - table.x) / distance, uy = (ny - table.y) / distance;
      const reach = extent(table, ux, uy);
      const cm = Math.round((distance - reach) * scale);
      if (cm < minCm) {
        issues.push({ key: table.id + el.id, a: table.label, b: el.label || 'Elemento', cm: Math.max(0, cm),
          from: { x: table.x + ux * reach, y: table.y + uy * reach }, to: { x: nx, y: ny } });
      }
    }
  }
  return issues.sort((x, y) => x.cm - y.cm);
}

export interface RouteLine { id: string; label: string; meters: number; to: Point; }

/** Punto de partida de los meseros: la cocina, si no la barra o la caja. */
export function routeOrigin(elements: FloorElement[]): { point: Point; label: string } | null {
  const origin = ['KITCHEN', 'BAR', 'CASHIER'].map((kind) => elements.find((el) => el.kind === kind)).find(Boolean);
  return origin ? { point: { x: origin.x + origin.width / 2, y: origin.y + origin.height / 2 }, label: origin.label || 'Cocina' } : null;
}

/** Distancia caminando (con un 25 % extra por esquivar mesas) desde el origen hasta cada mesa. */
export function routes(tables: PlacedTable[], origin: Point, widthMeters: number): RouteLine[] {
  const scale = cmPerUnit(widthMeters);
  return tables.map((table) => ({ id: table.id, label: table.label, to: { x: table.x, y: table.y },
    meters: Math.round((Math.hypot(table.x - origin.x, table.y - origin.y) * 1.25 * scale) / 10) / 10 }))
    .sort((a, b) => b.meters - a.meters);
}

export interface Guide { axis: 'x' | 'y'; value: number; }

/** Imanes: si la mesa queda casi alineada con otra, se pega a su eje y se dibuja la guía. */
export function snapToGuides(point: Point, others: Point[], threshold = 12): { point: Point; guides: Guide[] } {
  let { x, y } = point;
  const guides: Guide[] = [];
  const closeX = others.map((o) => o.x).filter((v) => Math.abs(v - x) <= threshold).sort((a, b) => Math.abs(a - x) - Math.abs(b - x))[0];
  const closeY = others.map((o) => o.y).filter((v) => Math.abs(v - y) <= threshold).sort((a, b) => Math.abs(a - y) - Math.abs(b - y))[0];
  if (closeX !== undefined) { x = closeX; guides.push({ axis: 'x', value: closeX }); }
  if (closeY !== undefined) { y = closeY; guides.push({ axis: 'y', value: closeY }); }
  const centerX = CANVAS.width / 2;
  if (closeX === undefined && Math.abs(x - centerX) <= threshold) { x = centerX; guides.push({ axis: 'x', value: centerX }); }
  return { point: { x, y }, guides };
}

/** Alinear en fila o columna y repartir con la misma distancia. */
export function arrange(points: (Point & { id: string })[], action: 'row' | 'column' | 'spread-x' | 'spread-y'): Record<string, Point> {
  const result: Record<string, Point> = {};
  if (action === 'row') {
    const y = Math.round(points.reduce((sum, p) => sum + p.y, 0) / points.length / 10) * 10;
    points.forEach((p) => (result[p.id] = { x: p.x, y }));
  } else if (action === 'column') {
    const x = Math.round(points.reduce((sum, p) => sum + p.x, 0) / points.length / 10) * 10;
    points.forEach((p) => (result[p.id] = { x, y: p.y }));
  } else {
    const key = action === 'spread-x' ? 'x' : 'y';
    const sorted = [...points].sort((a, b) => a[key] - b[key]);
    const first = sorted[0][key], last = sorted[sorted.length - 1][key];
    const step = sorted.length > 1 ? (last - first) / (sorted.length - 1) : 0;
    sorted.forEach((p, index) => (result[p.id] = key === 'x' ? { x: Math.round(first + step * index), y: p.y } : { x: p.x, y: Math.round(first + step * index) }));
  }
  return result;
}

/** Color fijo por mesero para pintar sus mesas en el plano. */
export const WAITER_COLORS = ['#4f8cff', '#f08a3c', '#a46bff', '#2db8a5', '#ec4899', '#f5b301', '#3fbf2a', '#ef5d55'];
