import { CurrencyPipe } from '@angular/common';
import {
  Component,
  computed,
  effect,
  ElementRef,
  input,
  OnDestroy,
  OnInit,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { TableView, ZoneView } from '../../core/api/models';
import { FloorElementsComponent } from '../../shared/floor/floor-elements.component';
import {
  autoPlace,
  CANVAS,
  chairPositions,
  FloorPlan,
  Point,
  svgPoint,
  tableSize,
} from '../../shared/floor/floor.models';

export type FloorTone = 'free' | 'busy' | 'ready' | 'call' | 'bill' | 'review';

/** Estado operativo de una mesa, ya calculado por la pantalla del mesero. */
export interface FloorTableState {
  table: TableView;
  tone: FloorTone;
  guests: number;
  total: number;
  readyCount: number;
  cooking: boolean;
  reservedAt: string | null;
  dimmed: boolean;
}

export interface TableMove {
  from: TableView;
  to: TableView;
}

type Box = { x: number; y: number; width: number; height: number };

interface PressState {
  id: string;
  pointerId: number;
  start: { x: number; y: number };
  dragging: boolean;
}

const TAB_KEY = 'restos_floor_tab';

/**
 * Plano en vivo del salón: cada mesa muestra su estado con animaciones y una mesa ocupada se puede
 * arrastrar sobre otra para cambiar a los comensales de mesa o unir ambas cuentas.
 */
import { initialsOf, waiterHue } from './served-by';
@Component({
  selector: 'app-floor-view',
  host: { '[class.fit]': 'fit()' },
  imports: [FloorElementsComponent, CurrencyPipe],
  templateUrl: './floor-view.component.html',
  styleUrls: [
    '../../shared/floor/floor-canvas.scss',
    './floor-view.component.scss',
    './floor-view-states.scss',
    './floor-view-outside.scss',
    './floor-view-heat.scss',
    './floor-view-fit.scss',
    './floor-view-zoom.scss',
    './floor-view-mobile.scss',
    './floor-view-waiter.scss',
  ],
})
export class FloorViewComponent implements OnInit, OnDestroy {
  readonly tables = input.required<FloorTableState[]>();
  /** Id del usuario actual: sus mesas se resaltan. */
  readonly me = input<string>('');
  readonly initials = initialsOf;
  readonly hue = waiterHue;
  /** Ajusta el plano al alto disponible (tablero del mesero) para no tener que hacer scroll. */
  readonly fit = input(false);
  /** Encuadra solo lo dibujado sin cambiar el diseño (reservas online). */
  readonly zoom = input(false);
  readonly zones = input<ZoneView[]>([]);
  readonly plan = input<FloorPlan | null>(null);
  /** live: salón en vivo; heat: mapa de calor; replay: ocupación en un momento pasado del día. */
  readonly mode = input<'live' | 'heat' | 'replay'>('live');
  /** Valor de cada mesa en el mapa de calor (ratio 0–1 respecto de la mejor mesa). */
  readonly heat = input<Record<string, { ratio: number; label: string; detail: string }>>({});
  /** Momento que se está mostrando en la repetición (por defecto, ahora). */
  readonly at = input<number | null>(null);
  /** En el Modo TV el plano solo se mira: no se abren ni se arrastran mesas. */
  readonly interactive = input(true);
  /** Modo «elegir mesa» (reservas online): solo se tocan mesas, no se arrastran. */
  readonly picker = input(false);
  readonly open = output<TableView>();
  readonly move = output<TableMove>();
  private readonly svg = viewChild<ElementRef<SVGSVGElement>>('canvas');
  readonly area = CANVAS;
  readonly clock = signal(Date.now());
  private timer?: number;
  readonly selectedTab = signal(this.savedTab());
  readonly ghost = signal<Point | null>(null);
  readonly dragId = signal<string | null>(null);
  readonly targetId = signal<string | null>(null);
  /** Aviso al intentar arrastrar una mesa que no se puede mover desde el salón. */
  readonly blockedHint = signal('');
  private press: PressState | null = null;

  readonly tabs = computed(() => {
    const present = new Set(this.tables().map((item) => item.table.zoneId || ''));
    const tabs = this.zones()
      .filter((zone) => present.has(zone.id))
      .map((zone) => ({ id: zone.id, name: zone.name, color: zone.color, open: zone.open }));
    if (present.has(''))
      tabs.push({ id: '', name: tabs.length ? 'Sin zona' : 'Salón', color: '#7b8794', open: true });
    return tabs.map((tab) => ({ ...tab, ...this.tabCounts(tab.id) }));
  });
  readonly activeTab = computed(() => {
    const tabs = this.tabs();
    return tabs.find((tab) => tab.id === this.selectedTab()) || tabs[0] || null;
  });
  readonly elements = computed(() => {
    const tab = this.activeTab();
    return (this.plan()?.elements || []).filter((item) => (item.zoneId || '') === (tab?.id ?? ''));
  });
  /** Mesas de la pestaña activa con su posición; sin ningún diseño guardado se acomodan solas. */
  private readonly layout = computed(() => {
    const tab = this.activeTab();
    const placements = new Map((this.plan()?.tables || []).map((item) => [item.id, item]));
    const states = this.tables().filter((item) => (item.table.zoneId || '') === (tab?.id ?? ''));
    const placed = states.filter((item) => placements.get(item.table.id)?.x != null);
    const designed = placed.length > 0;
    const auto = designed
      ? {}
      : autoPlace(
          states.map((item) => item.table.id),
          [],
          this.elements(),
        );
    const items = (designed ? placed : states).map((state) => {
      const place = placements.get(state.table.id);
      const shape = place?.shape || 'SQUARE';
      const point =
        place?.x != null && place.y != null
          ? { x: place.x, y: place.y }
          : (auto as Record<string, Point>)[state.table.id];
      const size = tableSize(shape, state.table.capacity);
      return {
        state,
        shape,
        rotation: place?.rotation || 0,
        x: point.x,
        y: point.y,
        size,
        radius: Math.max(size.width, size.height) / 2,
        chairs: chairPositions(shape, state.table.capacity),
        color: this.zones().find((zone) => zone.id === state.table.zoneId)?.color || '#55758a',
      };
    });
    // Respeta el diseño: las mesas que no se ubicaron en el plano van aparte, no encima de nada.
    const outside = designed
      ? states.filter((item) => placements.get(item.table.id)?.x == null)
      : [];
    return { items, outside };
  });
  readonly items = computed(() => this.layout().items);
  /** En el tablero, encuadra solo lo dibujado (mesas y elementos) para que se vea más grande. */
  readonly view = computed(() => {
    const full = { x: 0, y: 0, width: CANVAS.width, height: CANVAS.height };
    if (!this.fit() && !this.zoom()) return full;
    const boxes = [
      ...this.items().map((item) => ({ x1: item.x - item.radius - 26, y1: item.y - item.radius - 26, x2: item.x + item.radius + 26, y2: item.y + item.radius + 26 })),
      ...this.elements().map((el) => { const side = Math.max(el.width, el.height); const cx = el.x + el.width / 2, cy = el.y + el.height / 2; const half = el.rotation ? side / 2 : 0;
        return half ? { x1: cx - half, y1: cy - half, x2: cx + half, y2: cy + half } : { x1: el.x, y1: el.y, x2: el.x + el.width, y2: el.y + el.height }; }),
    ];
    if (!boxes.length) return full;
    const pad = 40;
    let x1 = Math.max(0, Math.min(...boxes.map((b) => b.x1)) - pad), y1 = Math.max(0, Math.min(...boxes.map((b) => b.y1)) - pad);
    let x2 = Math.min(CANVAS.width, Math.max(...boxes.map((b) => b.x2)) + pad), y2 = Math.min(CANVAS.height, Math.max(...boxes.map((b) => b.y2)) + pad);
    // Un mínimo de tamaño evita un zoom exagerado cuando hay pocas mesas.
    const minW = 520, minH = 340;
    if (x2 - x1 < minW) { const c = (x1 + x2) / 2; x1 = Math.max(0, c - minW / 2); x2 = Math.min(CANVAS.width, x1 + minW); }
    if (y2 - y1 < minH) { const c = (y1 + y2) / 2; y1 = Math.max(0, c - minH / 2); y2 = Math.min(CANVAS.height, y1 + minH); }
    return { x: Math.round(x1), y: Math.round(y1), width: Math.round(x2 - x1), height: Math.round(y2 - y1) };
  });
  readonly outside = computed(() => this.layout().outside);
  readonly dragItem = computed(
    () => this.items().find((item) => item.state.table.id === this.dragId()) || null,
  );
  readonly targetItem = computed(
    () => this.items().find((item) => item.state.table.id === this.targetId()) || null,
  );

  ngOnInit(): void {
    this.timer = window.setInterval(() => this.clock.set(Date.now()), 30000);
  }

  ngOnDestroy(): void {
    if (this.timer) window.clearInterval(this.timer);
  }

  selectTab(id: string): void {
    this.selectedTab.set(id);
    try {
      localStorage.setItem(TAB_KEY, id);
    } catch {
      /* sin almacenamiento */
    }
  }

  private savedTab(): string {
    try {
      return localStorage.getItem(TAB_KEY) || '';
    } catch {
      return '';
    }
  }

  private tabCounts(zoneId: string): { busy: number; total: number; alerts: number } {
    const tables = this.tables().filter((item) => (item.table.zoneId || '') === zoneId);
    return {
      total: tables.length,
      busy: tables.filter((item) => item.table.status === 'OPEN').length,
      alerts: tables.filter((item) => ['call', 'bill', 'ready'].includes(item.tone)).length,
    };
  }

  minutes(table: TableView): number | null {
    if (!table.openedAt) return null;
    const now = this.at() ?? this.clock();
    return Math.max(0, Math.floor((now - new Date(table.openedAt).getTime()) / 60000));
  }

  elapsedLabel(table: TableView): string {
    const value = this.minutes(table);
    if (value === null) return '';
    return value < 60 ? `${value} min` : `${Math.floor(value / 60)} h ${value % 60} min`;
  }

  reservationTime(value: string | null): string {
    return value ? value.slice(11, 16) : '';
  }

  ariaLabel(state: FloorTableState): string {
    const tone: Record<FloorTone, string> = {
      free: 'libre',
      busy: 'ocupada',
      ready: 'con platos listos',
      call: 'llamando al mesero',
      bill: 'pide la cuenta',
      review: 'requiere revisión',
    };
    return `${state.table.label}, ${tone[state.tone]}`;
  }

  // ——— Tocar para abrir, arrastrar una mesa ocupada para moverla o unirla ———
  heatColor(tableId: string): string | null {
    if (this.mode() !== 'heat') return null;
    const ratio = this.heat()[tableId]?.ratio ?? 0;
    // De azul (poco) a verde, amarillo y rojo (mucho).
    return `hsl(${Math.round(215 - 215 * ratio)} 80% ${ratio > 0 ? 52 : 40}%)`;
  }

  // ——— Cámara: ver todo el plano y acercar con gestos (pellizcar, arrastrar, doble toque) o botones ———
  readonly camera = signal<Box | null>(null);
  readonly viewBox = computed(() => { const box = this.camera() || this.view(); return `${box.x} ${box.y} ${box.width} ${box.height}`; });
  readonly zoomed = computed(() => !!this.camera());
  /** Solo cambia cuando cambia el encuadre del plano (no con cada actualización de las mesas). */
  private readonly viewKey = computed(() => { const box = this.view(); return `${box.x},${box.y},${box.width},${box.height}`; });
  private readonly resetOnPlanChange = effect(() => { this.viewKey(); untracked(() => this.camera.set(null)); });
  private readonly touches = new Map<number, { x: number; y: number }>();
  private pinch: { distance: number; base: Box; anchor: Point } | null = null;
  private pan: { x: number; y: number; base: Box; moved: boolean } | null = null;
  private lastTap = 0;

  zoomAt(factor: number, anchor?: Point): void {
    const box = this.camera() || this.fullBox();
    const at = anchor || { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    this.camera.set(this.clampCamera({ x: at.x - (at.x - box.x) / factor, y: at.y - (at.y - box.y) / factor, width: box.width / factor, height: box.height / factor }));
  }
  resetCamera(): void { this.camera.set(null); }
  wheelZoom(event: WheelEvent): void {
    if (!this.fit() || !event.ctrlKey) return;
    event.preventDefault();
    this.zoomAt(event.deltaY < 0 ? 1.12 : 1 / 1.12, this.toCanvas(event.clientX, event.clientY));
  }
  gestureDown(event: PointerEvent): void {
    if (!this.fit()) return;
    this.touches.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (this.touches.size === 2) {
      this.cancelDrag(); this.pan = null;
      const [a, b] = [...this.touches.values()];
      this.pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y) || 1, base: this.camera() || this.fullBox(), anchor: this.toCanvas((a.x + b.x) / 2, (a.y + b.y) / 2) };
      return;
    }
    if (!this.press) {
      this.pan = { x: event.clientX, y: event.clientY, base: this.camera() || this.fullBox(), moved: false };
      this.svg()?.nativeElement.setPointerCapture(event.pointerId);
    }
  }
  gestureCancel(): void { this.touches.clear(); this.pinch = null; this.pan = null; this.cancelDrag(); }
  /** Devuelve true si el gesto de cámara consumió el movimiento. */
  private gestureMove(event: PointerEvent): boolean {
    if (this.touches.has(event.pointerId)) this.touches.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (this.pinch && this.touches.size >= 2) {
      const [a, b] = [...this.touches.values()];
      const factor = Math.hypot(a.x - b.x, a.y - b.y) / this.pinch.distance;
      const { base, anchor } = this.pinch;
      this.camera.set(this.clampCamera({ x: anchor.x - (anchor.x - base.x) / factor, y: anchor.y - (anchor.y - base.y) / factor, width: base.width / factor, height: base.height / factor }));
      return true;
    }
    if (!this.pan) return false;
    const dx = event.clientX - this.pan.x, dy = event.clientY - this.pan.y;
    if (Math.hypot(dx, dy) > 6) this.pan.moved = true;
    if (this.pan.moved && this.camera()) {
      const base = this.pan.base, scale = this.screenScale(base).scale;
      this.camera.set(this.clampCamera({ ...base, x: base.x - dx / scale, y: base.y - dy / scale }));
    }
    return true;
  }
  /** Devuelve true si el gesto de cámara consumió el final del toque. */
  private gestureUp(event: PointerEvent): boolean {
    this.touches.delete(event.pointerId);
    if (this.pinch) { if (this.touches.size < 2) this.pinch = null; this.press = null; return true; }
    if (!this.pan) return false;
    const pan = this.pan; this.pan = null;
    if (!pan.moved) {
      const now = Date.now();
      if (now - this.lastTap < 320) { this.lastTap = 0; if (this.camera()) this.resetCamera(); else this.zoomAt(2.2, this.toCanvas(event.clientX, event.clientY)); }
      else this.lastTap = now;
    }
    return true;
  }
  /** Escala de pantalla del plano, contando las franjas vacías si el lienzo no tiene su misma proporción. */
  private screenScale(box: Box): { scale: number; left: number; top: number } {
    const rect = this.svg()?.nativeElement.getBoundingClientRect();
    if (!rect?.width) return { scale: 1, left: 0, top: 0 };
    const scale = Math.min(rect.width / box.width, rect.height / box.height);
    return { scale, left: rect.left + (rect.width - box.width * scale) / 2, top: rect.top + (rect.height - box.height * scale) / 2 };
  }
  private toCanvas(clientX: number, clientY: number): Point {
    const box = this.camera() || this.view();
    const { scale, left, top } = this.screenScale(box);
    return { x: box.x + (clientX - left) / scale, y: box.y + (clientY - top) / scale };
  }
  /** Proporción alto/ancho del lienzo en pantalla: en celular vertical la cámara aprovecha todo el alto. */
  private screenAspect(): number {
    const rect = this.svg()?.nativeElement.getBoundingClientRect();
    const view = this.view();
    return rect?.width && rect.height ? rect.height / rect.width : view.height / view.width;
  }
  /** Todo el plano visible con la proporción de la pantalla (el punto de partida al acercar). */
  private fullBox(): Box {
    const view = this.view(), aspect = this.screenAspect();
    const width = Math.max(view.width, view.height / aspect), height = width * aspect;
    return { x: view.x + (view.width - width) / 2, y: view.y + (view.height - height) / 2, width, height };
  }
  /** Máximo 4× de acercamiento y siempre dentro del plano; al ver todo, la cámara se suelta. */
  private clampCamera(box: Box): Box | null {
    const view = this.view(), full = this.fullBox(), aspect = this.screenAspect();
    const width = Math.min(full.width, Math.max(view.width / 4, box.width));
    if (width >= full.width - 0.5) return null;
    const height = width * aspect;
    const fitAxis = (start: number, size: number, viewStart: number, viewSize: number) =>
      size <= viewSize ? Math.min(viewStart + viewSize - size, Math.max(viewStart, start)) : viewStart + (viewSize - size) / 2;
    return { x: fitAxis(box.x, width, view.x, view.width), y: fitAxis(box.y, height, view.y, view.height), width, height };
  }

  pointerDown(event: PointerEvent, state: FloorTableState): void {
    if (event.button > 0 || !this.interactive()) return;
    this.press = {
      id: state.table.id,
      pointerId: event.pointerId,
      start: { x: event.clientX, y: event.clientY },
      dragging: false,
    };
    this.svg()?.nativeElement.setPointerCapture(event.pointerId);
  }

  pointerMove(event: PointerEvent): void {
    if (this.gestureMove(event)) return;
    const press = this.press;
    const svg = this.svg()?.nativeElement;
    if (!press || !svg || press.pointerId !== event.pointerId || this.picker()) return;
    if (!press.dragging) {
      if (Math.hypot(event.clientX - press.start.x, event.clientY - press.start.y) < 8) return;
      const source = this.items().find((item) => item.state.table.id === press.id);
      if (!source?.state.table.sessionId || source.state.table.requiresReview) {
        this.blockedHint.set(
          source?.state.table.requiresReview
            ? `${source.state.table.label} requiere revisión antes de moverse.`
            : 'Solo se arrastran mesas ocupadas (para cambiarlas de mesa o unir cuentas). Para reubicar mesas en el plano usa «Plano del salón».',
        );
        return;
      }
      press.dragging = true;
      this.dragId.set(press.id);
    }
    const point = svgPoint(svg, event);
    this.ghost.set(point);
    const target = this.items().find(
      (item) =>
        item.state.table.id !== press.id &&
        !item.state.table.requiresReview &&
        Math.hypot(item.x - point.x, item.y - point.y) < item.radius + 16,
    );
    this.targetId.set(target?.state.table.id || null);
  }

  pointerUp(event: PointerEvent): void {
    const press = this.press;
    const svg = this.svg()?.nativeElement;
    if (svg?.hasPointerCapture(event.pointerId)) svg.releasePointerCapture(event.pointerId);
    if (this.gestureUp(event)) return;
    this.press = null;
    if (!press) return;
    if (this.blockedHint()) {
      window.setTimeout(() => this.blockedHint.set(''), 2500);
      this.cancelDrag();
      return;
    }
    const source = this.items().find((item) => item.state.table.id === press.id);
    const target = this.targetItem();
    if (!press.dragging) {
      if (source) this.open.emit(source.state.table);
    } else if (source && target) {
      this.move.emit({ from: source.state.table, to: target.state.table });
    }
    this.cancelDrag();
  }

  cancelDrag(): void {
    this.press = null;
    this.dragId.set(null);
    this.targetId.set(null);
    this.ghost.set(null);
  }

  zoneColor(zoneId: string | null): string {
    return this.zones().find((zone) => zone.id === zoneId)?.color || '#55758a';
  }

  translate(point: Point): string {
    return `translate(${point.x} ${point.y})`;
  }
}
