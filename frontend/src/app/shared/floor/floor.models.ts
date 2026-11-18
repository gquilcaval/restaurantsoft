/** Geometría compartida del plano del salón (editor de la administración y vista animada del mesero). */
export type TableShape = 'SQUARE' | 'ROUND' | 'RECT';
export type ElementKind =
  'BAR' | 'WALL' | 'DOOR' | 'KITCHEN' | 'WC' | 'CASHIER' | 'PLANT' | 'WINDOW' | 'STAIRS';

/** Posición de una mesa: x/y es el centro; sin x/y la mesa aún no está ubicada en el plano. */
export interface TablePlacement {
  id: string;
  x: number | null;
  y: number | null;
  shape: TableShape;
  rotation: number;
  waiterId?: string | null;
}

/** Elemento decorativo: x/y es la esquina superior izquierda; gira sobre su centro. */
export interface FloorElement {
  id: string;
  zoneId: string | null;
  kind: ElementKind;
  label: string | null;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
}

export interface FloorPlan {
  tables: TablePlacement[];
  elements: FloorElement[];
  settings?: ZoneSettings[];
}

/** Ajustes del plano de una zona: foto de fondo, ancho real del salón (para medir pasillos) y aforo. */
export interface ZoneSettings {
  zoneId: string;
  backgroundUrl: string | null;
  backgroundOpacity: number;
  widthMeters: number;
  maxCapacity: number | null;
  activeLayoutId: string | null;
}

export interface FloorLayout { id: string; zoneId: string; name: string; updatedAt: string; tables: number; seats: number; }

export interface Point {
  x: number;
  y: number;
}

export const CANVAS = { width: 1200, height: 800 };
export const GRID = 10;

export const ELEMENT_KINDS: {
  kind: ElementKind;
  label: string;
  icon: string;
  width: number;
  height: number;
}[] = [
  { kind: 'BAR', label: 'Barra', icon: '🍸', width: 320, height: 60 },
  { kind: 'WALL', label: 'Pared', icon: '▬', width: 300, height: 14 },
  { kind: 'DOOR', label: 'Puerta', icon: '🚪', width: 80, height: 20 },
  { kind: 'WINDOW', label: 'Ventana', icon: '🪟', width: 200, height: 12 },
  { kind: 'KITCHEN', label: 'Cocina', icon: '🍳', width: 240, height: 140 },
  { kind: 'CASHIER', label: 'Caja', icon: '💵', width: 120, height: 60 },
  { kind: 'WC', label: 'Baños', icon: '🚻', width: 120, height: 100 },
  { kind: 'STAIRS', label: 'Escalera', icon: '🪜', width: 100, height: 140 },
  { kind: 'PLANT', label: 'Planta', icon: '🪴', width: 44, height: 44 },
];

export const elementMeta = (kind: ElementKind) =>
  ELEMENT_KINDS.find((item) => item.kind === kind) || ELEMENT_KINDS[0];

export const snap = (value: number): number => Math.round(value / GRID) * GRID;

export const clampPoint = (point: Point, margin = 20): Point => ({
  x: Math.min(CANVAS.width - margin, Math.max(margin, point.x)),
  y: Math.min(CANVAS.height - margin, Math.max(margin, point.y)),
});

/** Tamaño de la mesa según su forma y la cantidad de personas. */
export function tableSize(shape: TableShape, capacity: number): { width: number; height: number } {
  const seats = Math.max(1, capacity);
  if (shape === 'ROUND') {
    const size = seats <= 2 ? 64 : seats <= 4 ? 84 : seats <= 6 ? 104 : 124;
    return { width: size, height: size };
  }
  if (shape === 'RECT') return { width: Math.max(112, 40 + Math.ceil(seats / 2) * 36), height: 72 };
  const size = seats <= 2 ? 64 : seats <= 4 ? 80 : seats <= 6 ? 100 : 120;
  return { width: size, height: size };
}

/** Sillas alrededor de la mesa, en coordenadas locales (centro de la mesa = 0,0). */
export function chairPositions(shape: TableShape, capacity: number): (Point & { angle: number })[] {
  const seats = Math.min(Math.max(1, capacity), 14);
  const { width, height } = tableSize(shape, capacity);
  const gap = 13;
  if (shape === 'ROUND') {
    const radius = width / 2 + gap;
    return Array.from({ length: seats }, (_, index) => {
      const angle = (index * 360) / seats - 90;
      const rad = (angle * Math.PI) / 180;
      return { x: Math.cos(rad) * radius, y: Math.sin(rad) * radius, angle: angle + 90 };
    });
  }
  const sides = [0, 0, 0, 0]; // arriba, derecha, abajo, izquierda
  if (shape === 'RECT') {
    const ends = seats >= 6 ? 2 : 0;
    sides[1] = ends / 2;
    sides[3] = ends / 2;
    sides[0] = Math.ceil((seats - ends) / 2);
    sides[2] = seats - ends - sides[0];
  } else {
    const order = [0, 2, 3, 1];
    for (let index = 0; index < seats; index++) sides[order[index % 4]]++;
  }
  const result: (Point & { angle: number })[] = [];
  const spread = (count: number, length: number) =>
    Array.from({ length: count }, (_, index) => -length / 2 + (length * (index + 1)) / (count + 1));
  spread(sides[0], width).forEach((x) => result.push({ x, y: -height / 2 - gap, angle: 0 }));
  spread(sides[1], height).forEach((y) => result.push({ x: width / 2 + gap, y, angle: 90 }));
  spread(sides[2], width).forEach((x) => result.push({ x, y: height / 2 + gap, angle: 180 }));
  spread(sides[3], height).forEach((y) => result.push({ x: -width / 2 - gap, y, angle: 270 }));
  return result;
}

/** Ubica en cuadrícula las mesas sin posición, evitando otras mesas y los elementos del local. */
export function autoPlace(
  ids: string[],
  occupied: Point[],
  obstacles: FloorElement[] = [],
): Record<string, Point> {
  const result: Record<string, Point> = {};
  const taken = [...occupied];
  const blocked = (point: Point) =>
    taken.some((other) => Math.hypot(other.x - point.x, other.y - point.y) < 125) ||
    obstacles.some(
      (item) =>
        point.x > item.x - 62 &&
        point.x < item.x + item.width + 62 &&
        point.y > item.y - 62 &&
        point.y < item.y + item.height + 62,
    );
  const candidates: Point[] = [];
  for (let y = 100; y <= 720; y += 130)
    for (let x = 100; x <= 1100; x += 125) candidates.push({ x, y });
  let fallback = 0;
  for (const id of ids) {
    const free = candidates.find((point) => !blocked(point));
    const point = free || {
      x: 100 + (fallback % 9) * 125,
      y: 100 + (Math.floor(fallback++ / 9) % 5) * 130,
    };
    result[id] = clampPoint(point, 60);
    taken.push(point);
  }
  return result;
}

export const svgPoint = (svg: SVGSVGElement, event: PointerEvent): Point => {
  const matrix = svg.getScreenCTM();
  if (!matrix) return { x: 0, y: 0 };
  const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
  return { x: point.x, y: point.y };
};

export const defaultPlacement = (id: string): TablePlacement => ({
  id,
  x: null,
  y: null,
  shape: 'SQUARE',
  rotation: 0,
});

/** Arco de apertura de una puerta (bisagra a la izquierda del vano). */
export const doorArc = (element: FloorElement): string => {
  const hingeY = element.y + element.height / 2;
  const leaf = element.width;
  return `M ${element.x} ${hingeY} L ${element.x} ${hingeY - leaf} A ${leaf} ${leaf} 0 0 1 ${element.x + leaf} ${hingeY}`;
};

/** Peldaños de una escalera, como líneas horizontales cada 20 unidades. */
export const stairSteps = (element: FloorElement): number[] =>
  Array.from(
    { length: Math.max(1, Math.floor(element.height / 20) - 1) },
    (_, index) => element.y + (index + 1) * 20,
  );
