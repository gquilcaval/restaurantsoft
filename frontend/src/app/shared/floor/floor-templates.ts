import { ElementKind, FloorElement, TableShape } from './floor.models';

/** Lugar sugerido para una mesa dentro de una plantilla (x/y = centro). */
export interface TemplateSlot {
  x: number;
  y: number;
  shape: TableShape;
  rotation: number;
}

export interface FloorTemplate {
  id: string;
  name: string;
  icon: string;
  description: string;
  elements: FloorElement[];
  slots: TemplateSlot[];
}

type Piece = [ElementKind, string | null, number, number, number, number, number?];

const build = (pieces: Piece[]): FloorElement[] =>
  pieces.map(([kind, label, x, y, width, height, rotation], index) => ({
    id: `tpl-${index}`,
    zoneId: null,
    kind,
    label,
    x,
    y,
    width,
    height,
    rotation: rotation || 0,
  }));

const walls = (doorX: number, doorWidth: number): Piece[] => [
  ['WALL', null, 0, 0, 1200, 14],
  ['WALL', null, 0, 0, 14, 800],
  ['WALL', null, 1186, 0, 14, 800],
  ['WALL', null, 0, 786, doorX, 14],
  ['WALL', null, doorX + doorWidth, 786, 1200 - doorX - doorWidth, 14],
  ['DOOR', 'Entrada', doorX, 776, doorWidth, 20],
];

const grid = (xs: number[], ys: number[], shape: TableShape, rotation = 0): TemplateSlot[] =>
  ys.flatMap((y) => xs.map((x) => ({ x, y, shape, rotation })));

/** Diseños de partida: se aplican al plano actual y luego se ajustan arrastrando. */
export const FLOOR_TEMPLATES: FloorTemplate[] = [
  {
    id: 'classic',
    name: 'Restaurante clásico',
    icon: '🍽️',
    description:
      'Salón con mesas en cuadrícula, cocina al fondo, baños, caja junto a la entrada y mesas grandes para grupos.',
    elements: build([
      ...walls(520, 160),
      ['KITCHEN', 'Cocina', 860, 30, 310, 150],
      ['WC', 'Baños', 30, 30, 150, 120],
      ['CASHIER', 'Caja', 740, 700, 160, 60],
      ['PLANT', null, 40, 726, 44, 44],
      ['PLANT', null, 1116, 726, 44, 44],
      ['PLANT', null, 560, 40, 44, 44],
    ]),
    slots: [
      { x: 1020, y: 320, shape: 'RECT', rotation: 0 },
      { x: 1020, y: 500, shape: 'RECT', rotation: 0 },
      ...grid([280, 450, 620, 790], [270, 430, 590], 'SQUARE'),
      { x: 120, y: 330, shape: 'SQUARE', rotation: 0 },
      { x: 120, y: 500, shape: 'SQUARE', rotation: 0 },
    ],
  },
  {
    id: 'cafe',
    name: 'Cafetería',
    icon: '☕',
    description:
      'Barra de atención al frente, mesas redondas junto al ventanal y mesas para trabajar o conversar.',
    elements: build([
      ...walls(540, 140),
      ['BAR', 'Barra', 300, 40, 560, 70],
      ['CASHIER', 'Caja', 900, 40, 150, 70],
      ['WINDOW', 'Ventanal', 14, 150, 14, 520],
      ['WC', 'Baños', 1060, 640, 120, 130],
      ['PLANT', null, 40, 40, 44, 44],
      ['PLANT', null, 230, 50, 44, 44],
      ['PLANT', null, 470, 726, 44, 44],
    ]),
    slots: [
      ...grid([110], [210, 340, 470, 600], 'ROUND'),
      ...grid([370, 560, 750], [300, 480], 'ROUND'),
      ...grid([980], [250, 410], 'SQUARE'),
      { x: 560, y: 650, shape: 'RECT', rotation: 0 },
    ],
  },
  {
    id: 'terrace',
    name: 'Terraza',
    icon: '🌿',
    description: 'Espacio al aire libre con mesas redondas intercaladas y plantas en el perímetro.',
    elements: build([
      ['WALL', 'Fachada', 0, 0, 1200, 14],
      ['DOOR', 'Acceso al salón', 520, 4, 160, 20],
      ...[60, 220, 380, 540, 700, 860, 1020].map((x): Piece => ['PLANT', null, x, 740, 44, 44]),
      ...[180, 360, 540].map((y): Piece => ['PLANT', null, 20, y, 44, 44]),
      ...[180, 360, 540].map((y): Piece => ['PLANT', null, 1136, y, 44, 44]),
    ]),
    slots: [
      ...grid([200, 440, 680, 920], [170, 570], 'ROUND'),
      ...grid([320, 560, 800, 1040], [370], 'ROUND'),
      { x: 200, y: 370, shape: 'SQUARE', rotation: 45 },
    ],
  },
  {
    id: 'bar',
    name: 'Bar / lounge',
    icon: '🍸',
    description:
      'Barra larga, mesas altas redondas, mesas grandes para grupos y escalera al segundo piso.',
    elements: build([
      ...walls(540, 140),
      ['BAR', 'Barra', 200, 40, 760, 70],
      ['CASHIER', 'Caja', 990, 40, 160, 70],
      ['STAIRS', 'Escalera', 40, 560, 100, 170],
      ['WC', 'Baños', 1040, 640, 140, 130],
      ['PLANT', null, 40, 40, 44, 44],
    ]),
    slots: [
      ...grid([280, 600, 920], [330], 'RECT'),
      ...grid([260, 440, 620, 800, 980], [520], 'ROUND'),
      ...grid([330, 760], [660], 'SQUARE'),
    ],
  },
];
