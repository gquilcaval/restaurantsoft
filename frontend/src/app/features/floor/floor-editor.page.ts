import {
  Component,
  computed,
  ElementRef,
  HostListener,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { ImageUploadService } from '../../core/api/image-upload.service';
import { aisleIssues, arrange, cmPerUnit, Guide, PlacedTable, routeOrigin, routes, snapToGuides, WAITER_COLORS } from './floor-studio';
import { TableView, ZoneView } from '../../core/api/models';
import {
  autoPlace,
  CANVAS,
  chairPositions,
  clampPoint,
  defaultPlacement,
  ElementKind,
  elementMeta,
  ELEMENT_KINDS,
  FloorElement,
  FloorLayout,
  FloorPlan,
  ZoneSettings,
  Point,
  snap,
  svgPoint,
  TablePlacement,
  TableShape,
  tableSize,
} from '../../shared/floor/floor.models';
import {
  ElementPointer,
  FloorElementsComponent,
} from '../../shared/floor/floor-elements.component';
import { FLOOR_TEMPLATES, FloorTemplate } from '../../shared/floor/floor-templates';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { ToastService } from '../../shared/ui/toast.service';

type Selection = { kind: 'table' | 'element'; id: string } | null;
interface Snapshot {
  placements: Record<string, TablePlacement>;
  elements: FloorElement[];
}
type StudioMode = 'DESIGN' | 'AISLES' | 'ROUTES' | 'HEAT' | 'WAITERS';
interface StaffMember { id: string; name: string; role: string; }
interface TableHeat { tableId: string; revenue: number; turns: number; minutes: number; }
interface DragState {
  mode: 'move' | 'resize';
  group?: Record<string, Point>;
  selection: NonNullable<Selection>;
  start: Point;
  origin: { x: number; y: number; width: number; height: number };
  moved: boolean;
}

/** Editor visual del salón: cada zona tiene su plano con mesas arrastrables y elementos del local. */
@Component({
  imports: [AppShellComponent, FormsModule, DecimalPipe, FloorElementsComponent, IconComponent],
  templateUrl: './floor-editor.page.html',
  styleUrls: [
    './floor-editor.page.scss',
    './floor-editor-extras.scss',
    '../../shared/floor/floor-canvas.scss',
    './floor-editor-glass.scss',
    './floor-studio.scss',
    './floor-studio-panel.scss',
    './floor-studio-layers.scss',
  ],
})
export class FloorEditorPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  private readonly uploads = inject(ImageUploadService);
  /** Estudio: diseñar, revisar pasillos, recorridos, rendimiento o mesas por mesero. */
  readonly mode = signal<StudioMode>('DESIGN');
  readonly modes: { key: StudioMode; label: string; icon: string }[] = [
    { key: 'DESIGN', label: 'Diseñar', icon: 'edit' }, { key: 'AISLES', label: 'Pasillos', icon: 'maximize' },
    { key: 'ROUTES', label: 'Recorridos', icon: 'map' }, { key: 'HEAT', label: 'Rendimiento', icon: 'flame' },
    { key: 'WAITERS', label: 'Meseros', icon: 'users' },
  ];
  readonly view3d = signal(false);
  readonly panelTab = signal<'TABLES' | 'SETTINGS' | 'LAYOUTS'>('TABLES');
  /** Mesas seleccionadas a la vez (Shift + clic o arrastrando un recuadro). */
  readonly multi = signal<Set<string>>(new Set());
  readonly guides = signal<Guide[]>([]);
  readonly marquee = signal<{ x: number; y: number; w: number; h: number } | null>(null);
  private marqueeStart: Point | null = null;
  readonly settings = signal<ZoneSettings[]>([]);
  readonly layouts = signal<FloorLayout[]>([]);
  readonly staff = signal<StaffMember[]>([]);
  readonly heatRows = signal<TableHeat[]>([]);
  readonly heatMetric = signal<'revenue' | 'turns'>('revenue');
  readonly minAisle = signal(60);
  readonly uploadingBg = signal(false);
  private settingsTimer?: number;
  private readonly svg = viewChild<ElementRef<SVGSVGElement>>('canvas');
  readonly area = CANVAS;
  readonly kinds = ELEMENT_KINDS;
  readonly templates = FLOOR_TEMPLATES;
  readonly templatesOpen = signal(false);
  private readonly palette = [
    '#2F8F9D',
    '#B66A2F',
    '#765F93',
    '#4F7D56',
    '#B84A45',
    '#C49A2C',
    '#55758A',
  ];
  readonly shapes: { value: TableShape; label: string }[] = [
    { value: 'SQUARE', label: 'Cuadrada' },
    { value: 'ROUND', label: 'Redonda' },
    { value: 'RECT', label: 'Rectangular' },
  ];
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly dirty = signal(false);
  readonly zones = signal<ZoneView[]>([]);
  readonly tables = signal<TableView[]>([]);
  readonly placements = signal<Record<string, TablePlacement>>({});
  readonly elements = signal<FloorElement[]>([]);
  /** Zona del plano que se está editando; cadena vacía = mesas sin zona. */
  readonly zoneId = signal('');
  readonly selection = signal<Selection>(null);
  readonly history = signal<Snapshot[]>([]);
  readonly dragging = signal(false);
  private drag: DragState | null = null;
  private allElements: FloorElement[] = [];

  readonly tabs = computed(() => {
    const tabs = this.zones().map((zone) => ({ id: zone.id, name: zone.name, color: zone.color }));
    if (!tabs.length || this.tables().some((table) => !table.zoneId))
      tabs.push({ id: '', name: tabs.length ? 'Sin plano' : 'Salón', color: '#7b8794' });
    return tabs;
  });
  readonly zoneColor = computed(
    () => this.tabs().find((tab) => tab.id === this.zoneId())?.color || '#7b8794',
  );
  readonly zoneTables = computed(() =>
    this.tables().filter((table) => (table.zoneId || '') === this.zoneId()),
  );
  readonly placedTables = computed(() =>
    this.zoneTables()
      .map((table) => ({ table, place: this.placementOf(table.id) }))
      .filter((item) => item.place.x !== null && item.place.y !== null)
      .map((item) => ({
        ...item,
        size: tableSize(item.place.shape, item.table.capacity),
        chairs: chairPositions(item.place.shape, item.table.capacity),
      })),
  );
  readonly unplacedTables = computed(() =>
    this.zoneTables().filter((table) => this.placementOf(table.id).x === null),
  );
  /** Mesas que pertenecen a otros planos y se pueden traer a este. */
  readonly otherTables = computed(() =>
    this.tables().filter((table) => (table.zoneId || '') !== this.zoneId()),
  );
  readonly activeZone = computed(
    () => this.zones().find((zone) => zone.id === this.zoneId()) || null,
  );
  readonly selectedTable = computed(() => {
    const current = this.selection();
    if (current?.kind !== 'table') return null;
    const table = this.tables().find((item) => item.id === current.id);
    return table ? { table, place: this.placementOf(table.id) } : null;
  });
  readonly selectedElement = computed(() => {
    const current = this.selection();
    return current?.kind === 'element'
      ? this.elements().find((item) => item.id === current.id) || null
      : null;
  });

  readonly zoneSettings = computed<ZoneSettings>(() => this.settings().find((item) => item.zoneId === this.zoneId())
    || { zoneId: this.zoneId(), backgroundUrl: null, backgroundOpacity: 35, widthMeters: 12, maxCapacity: null, activeLayoutId: null });
  /** Mesas ubicadas con su tamaño real (girado si corresponde) para medir pasillos y recorridos. */
  readonly studioTables = computed<PlacedTable[]>(() => this.placedTables().map((item) => {
    const turned = item.place.rotation % 180 === 90;
    return { id: item.table.id, label: item.table.label, capacity: item.table.capacity, x: item.place.x!, y: item.place.y!,
      width: turned ? item.size.height : item.size.width, height: turned ? item.size.width : item.size.height, round: item.place.shape === 'ROUND' };
  }));
  readonly seats = computed(() => this.placedTables().reduce((sum, item) => sum + item.table.capacity, 0));
  readonly overCapacity = computed(() => { const max = this.zoneSettings().maxCapacity; return !!max && this.seats() > max; });
  readonly aisles = computed(() => aisleIssues(this.studioTables(), this.elements(), this.zoneSettings().widthMeters, this.minAisle()));
  readonly origin = computed(() => routeOrigin(this.elements()));
  readonly routeLines = computed(() => { const origin = this.origin(); return origin ? routes(this.studioTables(), origin.point, this.zoneSettings().widthMeters) : []; });
  readonly routeAverage = computed(() => { const list = this.routeLines(); return list.length ? Math.round(list.reduce((sum, r) => sum + r.meters, 0) / list.length * 10) / 10 : 0; });
  /** Visitas por mesa y noche: del historial de 30 días si se cargó, si no se asumen 2. */
  readonly visitsPerNight = computed(() => {
    const rows = this.heatRows(); const ids = new Set(this.studioTables().map((t) => t.id));
    const turns = rows.filter((r) => ids.has(r.tableId)).reduce((sum, r) => sum + r.turns, 0);
    return rows.length && ids.size ? Math.max(1, Math.round(turns / 30 / ids.size * 10) / 10) : 2;
  });
  /** Kilómetros por noche: 3 viajes por visita (pedido, platos y cuenta), ida y vuelta. */
  readonly nightKm = computed(() => Math.round(this.routeLines().reduce((sum, r) => sum + r.meters * 2 * 3 * this.visitsPerNight(), 0) / 100) / 10);
  readonly heatMap = computed(() => {
    const metric = this.heatMetric(); const rows = this.heatRows();
    const ids = new Set(this.zoneTables().map((t) => t.id));
    const values = rows.filter((r) => ids.has(r.tableId)).map((r) => Number(r[metric]) || 0);
    const max = Math.max(0, ...values), min = values.length ? Math.min(...values) : 0, span = Math.max(1, max - min);
    return Object.fromEntries(rows.map((r) => [r.tableId, { ratio: Math.max(0, Math.min(1, ((Number(r[metric]) || 0) - min) / span)), value: Number(r[metric]) || 0, turns: r.turns, revenue: Number(r.revenue) }]));
  });
  readonly heatRanking = computed(() => this.studioTables().map((t) => ({ ...t, heat: this.heatMap()[t.id] || { ratio: 0, value: 0, turns: 0, revenue: 0 } }))
    .sort((a, b) => b.heat.value - a.heat.value));
  readonly multiCount = computed(() => this.multi().size);

  ngOnInit(): void {
    this.reloadAll();
    this.loadLayouts();
  }

  // ——— Estudio: modos, ajustes, configuraciones, meseros y rendimiento ———
  setMode(mode: StudioMode): void {
    this.mode.set(mode);
    this.guides.set([]);
    if (mode === 'HEAT' || mode === 'ROUTES') this.loadHeat();
    if (mode === 'WAITERS' && !this.staff().length) this.api.get<StaffMember[]>('/admin/floor/staff').subscribe((list) => this.staff.set(list));
    if (mode !== 'DESIGN' && mode !== 'WAITERS') { this.selection.set(null); this.multi.set(new Set()); }
  }
  toggle3d(): void { this.view3d.update((value) => !value); this.selection.set(null); }
  private loadHeat(): void {
    if (this.heatRows().length) return;
    const to = new Date(); const from = new Date(Date.now() - 29 * 86400000);
    const iso = (d: Date) => d.toISOString().slice(0, 10);
    this.api.get<TableHeat[]>(`/admin/live/heat?from=${iso(from)}&to=${iso(to)}`).subscribe({ next: (rows) => this.heatRows.set(rows), error: () => undefined });
  }
  heatColor(ratio: number): string {
    const hue = Math.round(210 - ratio * 200);
    return `hsl(${hue} 80% 55%)`;
  }
  money(value: number): string { return `S/ ${Math.round(value).toLocaleString('en-US')}`; }

  /** Guarda los ajustes del plano (con una pequeña espera para no enviar cada tecla). */
  updateSettings(patch: Partial<ZoneSettings>): void {
    const zone = this.activeZone(); if (!zone) return;
    const next = { ...this.zoneSettings(), ...patch, zoneId: zone.id };
    this.settings.update((list) => [...list.filter((item) => item.zoneId !== zone.id), next]);
    window.clearTimeout(this.settingsTimer);
    this.settingsTimer = window.setTimeout(() => {
      this.api.put(`/admin/floor/zones/${zone.id}/settings`, { backgroundUrl: next.backgroundUrl, backgroundOpacity: next.backgroundOpacity,
        widthMeters: Number(next.widthMeters) || 12, maxCapacity: next.maxCapacity ? Number(next.maxCapacity) : null })
        .subscribe({ error: (err) => this.toast.show('No se pudo guardar', err.error?.message || 'Intenta nuevamente.', 'error', '!') });
    }, 500);
  }
  async uploadBackground(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file) return;
    this.uploadingBg.set(true);
    try { this.updateSettings({ backgroundUrl: await this.uploads.upload(file) }); this.toast.show('Foto de fondo lista', 'Dibuja tus mesas encima siguiendo la foto.', 'success', '✓'); }
    catch (err: any) { this.toast.show('No se pudo subir', err?.error?.message || 'Prueba con otra imagen.', 'error', '!'); }
    finally { this.uploadingBg.set(false); }
  }

  loadLayouts(): void { this.api.get<FloorLayout[]>('/admin/floor/layouts').subscribe({ next: (list) => this.layouts.set(list), error: () => undefined }); }
  zoneLayouts(): FloorLayout[] { return this.layouts().filter((item) => item.zoneId === this.zoneId()); }
  /** «Día normal», «Evento 40 personas»…: guarda el plano actual como una configuración. */
  async saveAsLayout(): Promise<void> {
    const zone = this.activeZone(); if (!zone) return;
    if (this.dirty()) { this.toast.show('Primero guarda el plano', 'La configuración se crea con el plano guardado.', 'warning', '!'); return; }
    const name = await this.dialog.prompt({ title: 'Guardar configuración', message: 'Ej. Día normal, Evento 40 personas, Fin de semana con mesas juntas.',
      inputLabel: 'Nombre', maxLength: 60, required: true, confirmLabel: 'Guardar', icon: '＋', tone: 'info' });
    if (!name?.trim()) return;
    this.api.post<{ id: string }>(`/admin/floor/zones/${zone.id}/layouts`, { name: name.trim() }).subscribe({
      next: (created) => { this.markActiveLayout(created.id); this.loadLayouts(); this.toast.show('Configuración guardada', name.trim(), 'success', '✓'); },
      error: (err) => this.toast.show('No se pudo guardar', err.error?.message || 'Intenta nuevamente.', 'error', '!'),
    });
  }
  async applyLayout(layout: FloorLayout): Promise<void> {
    const accepted = await this.dialog.confirm({ title: `Usar «${layout.name}»`, message: 'Las mesas y elementos de este plano se acomodarán como en esta configuración. Los meseros lo verán al instante.',
      confirmLabel: 'Usar configuración', tone: 'info', icon: '⇄' });
    if (!accepted) return;
    this.api.post(`/admin/floor/layouts/${layout.id}/apply`, {}).subscribe({
      next: () => { this.markActiveLayout(layout.id); this.dirty.set(false); this.loadPlan(); this.toast.show('Configuración aplicada', layout.name, 'success', '✓'); },
      error: (err) => this.toast.show('No se pudo aplicar', err.error?.message || 'Intenta nuevamente.', 'error', '!'),
    });
  }
  updateLayout(layout: FloorLayout): void {
    if (this.dirty()) { this.toast.show('Primero guarda el plano', 'Se actualiza con el plano guardado.', 'warning', '!'); return; }
    this.api.put(`/admin/floor/layouts/${layout.id}`, {}).subscribe({ next: () => { this.markActiveLayout(layout.id); this.loadLayouts(); this.toast.show('Configuración actualizada', layout.name, 'success', '✓'); } });
  }
  async deleteLayout(layout: FloorLayout): Promise<void> {
    if (!(await this.dialog.confirm({ title: `Eliminar «${layout.name}»`, message: 'El plano actual no cambia.', confirmLabel: 'Eliminar', tone: 'danger', icon: '!' }))) return;
    this.api.delete(`/admin/floor/layouts/${layout.id}`).subscribe({ next: () => this.loadLayouts() });
  }
  private markActiveLayout(id: string | null): void {
    const zone = this.zoneId();
    this.settings.update((list) => list.map((item) => (item.zoneId === zone ? { ...item, activeLayoutId: id } : item)));
  }

  waiterColor(id: string | null | undefined): string {
    const index = this.staff().findIndex((member) => member.id === id);
    return index < 0 ? '#8b95a7' : WAITER_COLORS[index % WAITER_COLORS.length];
  }
  waiterInitials(id: string | null | undefined): string {
    const member = this.staff().find((item) => item.id === id);
    return member ? member.name.split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase() : '';
  }
  waiterTables(id: string): number { return this.zoneTables().filter((table) => this.placementOf(table.id).waiterId === id).length; }
  /** Asigna las mesas seleccionadas a un mesero (o se las quita). */
  assignWaiter(waiterId: string | null): void {
    const ids = [...this.multi()];
    if (!ids.length) { this.toast.show('Elige mesas primero', 'Toca mesas (o arrastra un recuadro) y luego el mesero.', 'info', 'i'); return; }
    this.api.put('/admin/floor/waiters', { tableIds: ids, waiterId }).subscribe({
      next: () => {
        this.placements.update((current) => { const next = { ...current }; ids.forEach((id) => (next[id] = { ...(next[id] || defaultPlacement(id)), waiterId })); return next; });
        this.toast.show(waiterId ? 'Mesas asignadas' : 'Mesas sin mesero', `${ids.length} ${ids.length === 1 ? 'mesa' : 'mesas'}`, 'success', '✓');
        this.multi.set(new Set());
      },
      error: (err) => this.toast.show('No se pudo asignar', err.error?.message || 'Intenta nuevamente.', 'error', '!'),
    });
  }

  /** Alinea o reparte las mesas seleccionadas. */
  arrangeSelected(action: 'row' | 'column' | 'spread-x' | 'spread-y'): void {
    const points = this.placedTables().filter((item) => this.multi().has(item.table.id)).map((item) => ({ id: item.table.id, x: item.place.x!, y: item.place.y! }));
    if (points.length < 2) return;
    this.remember();
    const next = arrange(points, action);
    this.placements.update((current) => { const copy = { ...current }; Object.entries(next).forEach(([id, p]) => (copy[id] = { ...copy[id], ...clampPoint(p, 30) })); return copy; });
  }
  toggleMulti(id: string): void {
    this.multi.update((current) => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  }

  /** Recuadro de selección al arrastrar sobre el piso vacío. */
  startMarquee(event: PointerEvent): void {
    const svg = this.svg()?.nativeElement;
    if (!svg || event.button > 0 || this.view3d()) return;
    this.selection.set(null);
    if (!event.shiftKey) this.multi.set(new Set());
    this.marqueeStart = svgPoint(svg, event);
    svg.setPointerCapture(event.pointerId);
  }

  /** Recarga planos, mesas y diseño; opcionalmente deja abierto un plano concreto. */
  private reloadAll(focus?: string): void {
    this.api.get<ZoneView[]>('/admin/zones').subscribe({
      next: (zones) => {
        const active = zones.filter((zone) => zone.active);
        this.zones.set(active);
        const keep = focus ?? this.zoneId();
        this.zoneId.set(active.some((zone) => zone.id === keep) ? keep : active[0]?.id || '');
        this.loadPlan();
      },
      error: () => this.loadPlan(),
    });
    this.api.get<TableView[]>('/staff/tables').subscribe((tables) => this.tables.set(tables));
  }

  private async confirmDiscard(): Promise<boolean> {
    if (!this.dirty()) return true;
    return this.dialog.confirm({
      title: 'Cambios sin guardar',
      message: 'Perderás los cambios hechos en este plano que todavía no guardaste.',
      confirmLabel: 'Descartar cambios',
      tone: 'warning',
      icon: '!',
    });
  }

  // ——— Varios planos (salón, terraza, segundo piso…) ———
  async createPlan(): Promise<void> {
    if (!(await this.confirmDiscard())) return;
    const name = await this.dialog.prompt({
      title: 'Nuevo plano',
      message: 'Crea un plano por cada ambiente del local: Terraza, Segundo piso, Barra, VIP…',
      inputLabel: 'Nombre del plano',
      placeholder: 'Ej.: Terraza',
      maxLength: 60,
      required: true,
      confirmLabel: 'Crear plano',
      icon: '＋',
      tone: 'info',
    });
    if (!name?.trim()) return;
    const color = this.palette[this.zones().length % this.palette.length];
    this.api
      .post<{ id: string }>('/admin/floor/plans', {
        name: name.trim(),
        color,
        baseName: 'Salón principal',
      })
      .subscribe({
        next: (created) => {
          this.toast.show(
            'Plano creado',
            `Elige una plantilla o diseña «${name.trim()}» desde cero.`,
            'success',
            '✓',
          );
          this.reloadAll(created.id);
          this.templatesOpen.set(true);
        },
        error: (err) =>
          this.toast.show(
            'No se pudo crear',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          ),
      });
  }

  async renamePlan(): Promise<void> {
    const zone = this.activeZone();
    if (!zone) return;
    const name = await this.dialog.prompt({
      title: 'Renombrar plano',
      message: 'El nombre se verá en la pantalla de mesas y en los reportes por zona.',
      inputLabel: 'Nombre del plano',
      initialValue: zone.name,
      maxLength: 60,
      required: true,
      confirmLabel: 'Guardar nombre',
      icon: '✎',
      tone: 'info',
    });
    if (!name?.trim() || name.trim() === zone.name) return;
    this.updateZone(zone, { name: name.trim() }, 'Plano renombrado');
  }

  async deletePlan(): Promise<void> {
    const zone = this.activeZone();
    if (!zone) return;
    const accepted = await this.dialog.confirm({
      title: `Eliminar «${zone.name}»`,
      message: 'Sus mesas no se borran: quedarán sin plano para que las ubiques en otro.',
      confirmLabel: 'Eliminar plano',
      tone: 'danger',
      icon: '!',
    });
    if (accepted) this.updateZone(zone, { active: false }, 'Plano eliminado');
  }

  private updateZone(zone: ZoneView, patch: Partial<ZoneView>, done: string): void {
    const body = {
      name: zone.name,
      color: zone.color,
      displayOrder: zone.displayOrder,
      open: zone.open,
      active: zone.active,
      ...patch,
    };
    this.api.patch(`/admin/zones/${zone.id}`, body).subscribe({
      next: () => {
        this.toast.show(done, '', 'success', '✓');
        this.reloadAll();
      },
      error: (err) =>
        this.toast.show(
          'No se pudo guardar',
          err.error?.message || 'Intenta nuevamente.',
          'error',
          '!',
        ),
    });
  }

  /** Pasa una mesa de otro plano a este (cambia su zona); luego se ubica arrastrándola. */
  bringTable(table: TableView): void {
    const zone = this.activeZone();
    if (!zone) return;
    const ids = [...this.zoneTables().map((item) => item.id), table.id];
    this.api.put(`/admin/zones/${zone.id}/tables`, { ids }).subscribe({
      next: () => {
        this.tables.update((list) =>
          list.map((item) => (item.id === table.id ? { ...item, zoneId: zone.id } : item)),
        );
        this.placeTable({ ...table, zoneId: zone.id });
      },
      error: (err) =>
        this.toast.show(
          'No se pudo mover',
          err.error?.message || 'Intenta nuevamente.',
          'error',
          '!',
        ),
    });
  }

  zoneLabel(zoneId: string | null): string {
    return this.zones().find((zone) => zone.id === zoneId)?.name || 'Sin plano';
  }

  // ——— Plantillas de diseño ———
  async applyTemplate(template: FloorTemplate): Promise<void> {
    if (this.elements().length || this.placedTables().length) {
      const accepted = await this.dialog.confirm({
        title: `Usar «${template.name}»`,
        message:
          'Se reemplazará el diseño actual de este plano. Podrás deshacerlo con Ctrl+Z antes de guardar.',
        confirmLabel: 'Usar plantilla',
        tone: 'info',
        icon: template.icon,
      });
      if (!accepted) return;
    }
    this.remember();
    const elements = template.elements.map((item) => ({
      ...item,
      id: crypto.randomUUID(),
      zoneId: this.zoneId() || null,
    }));
    // Las mesas más grandes ocupan primero los lugares rectangulares, luego cuadrados y redondos.
    const rank = { RECT: 0, SQUARE: 1, ROUND: 2 } as const;
    const slots = [...template.slots].sort((a, b) => rank[a.shape] - rank[b.shape]);
    const tables = [...this.zoneTables()].sort((a, b) => b.capacity - a.capacity);
    const next = { ...this.placements() };
    const used: Point[] = [];
    tables.slice(0, slots.length).forEach((table, index) => {
      const slot = slots[index];
      next[table.id] = {
        id: table.id,
        x: slot.x,
        y: slot.y,
        shape: slot.shape,
        rotation: slot.rotation,
      };
      used.push(slot);
    });
    const extra = tables.slice(slots.length);
    const spots = autoPlace(
      extra.map((table) => table.id),
      used,
      elements,
    );
    extra.forEach(
      (table) =>
        (next[table.id] = {
          ...(next[table.id] || defaultPlacement(table.id)),
          ...spots[table.id],
        }),
    );
    this.placements.set(next);
    this.elements.set(elements);
    this.selection.set(null);
    this.templatesOpen.set(false);
    this.toast.show(
      'Plantilla aplicada',
      'Ajusta lo que quieras arrastrando y luego guarda el plano.',
      'success',
      template.icon,
    );
  }

  slotSize(slot: { shape: TableShape }) {
    return tableSize(slot.shape, 4);
  }

  private loadPlan(): void {
    this.api.get<FloorPlan>('/staff/floor').subscribe({
      next: (plan) => {
        this.placements.set(Object.fromEntries(plan.tables.map((item) => [item.id, item])));
        this.settings.set(plan.settings || []);
        this.allElements = plan.elements;
        this.showZoneElements();
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private showZoneElements(): void {
    this.elements.set(
      this.allElements
        .filter((item) => (item.zoneId || '') === this.zoneId())
        .map((item) => ({ ...item })),
    );
    this.history.set([]);
    this.dirty.set(false);
    this.selection.set(null);
  }

  async selectZone(id: string): Promise<void> {
    if (id === this.zoneId()) return;
    if (this.dirty()) {
      if (!(await this.confirmDiscard())) return;
      this.zoneId.set(id);
      this.loadPlan();
      return;
    }
    this.zoneId.set(id);
    this.showZoneElements();
  }

  placementOf(id: string): TablePlacement {
    return this.placements()[id] || defaultPlacement(id);
  }

  meta(kind: ElementKind) {
    return elementMeta(kind);
  }

  // ——— Historial para deshacer ———
  private remember(): void {
    this.history.update((list) => [
      ...list.slice(-39),
      {
        placements: structuredClone(this.placements()),
        elements: structuredClone(this.elements()),
      },
    ]);
    this.dirty.set(true);
  }

  undo(): void {
    const list = this.history();
    const last = list.at(-1);
    if (!last) return;
    this.placements.set(last.placements);
    this.elements.set(last.elements);
    this.history.set(list.slice(0, -1));
    this.dirty.set(list.length > 1);
  }

  // ——— Mesas ———
  placeTable(table: TableView): void {
    this.remember();
    const occupied = this.placedTables().map((item) => ({ x: item.place.x!, y: item.place.y! }));
    const point = autoPlace([table.id], occupied, this.elements())[table.id];
    this.updatePlacement(table.id, { x: point.x, y: point.y });
    this.selection.set({ kind: 'table', id: table.id });
  }

  placeAll(): void {
    const pending = this.unplacedTables();
    if (!pending.length) return;
    this.remember();
    const occupied = this.placedTables().map((item) => ({ x: item.place.x!, y: item.place.y! }));
    const points = autoPlace(
      pending.map((table) => table.id),
      occupied,
      this.elements(),
    );
    this.placements.update((current) => {
      const next = { ...current };
      for (const table of pending)
        next[table.id] = { ...(next[table.id] || defaultPlacement(table.id)), ...points[table.id] };
      return next;
    });
  }

  removeTable(id: string): void {
    this.remember();
    this.updatePlacement(id, { x: null, y: null });
    this.selection.set(null);
  }

  setShape(id: string, shape: TableShape): void {
    this.remember();
    this.updatePlacement(id, { shape });
  }

  rotateSelected(step: number): void {
    const current = this.selection();
    if (!current) return;
    this.remember();
    if (current.kind === 'table') {
      const place = this.placementOf(current.id);
      this.updatePlacement(current.id, { rotation: (place.rotation + step + 360) % 360 });
    } else {
      this.updateElement(current.id, (item) => ({
        ...item,
        rotation: (item.rotation + step + 360) % 360,
      }));
    }
  }

  private updatePlacement(id: string, patch: Partial<TablePlacement>): void {
    this.placements.update((current) => ({
      ...current,
      [id]: { ...(current[id] || defaultPlacement(id)), ...patch },
    }));
  }

  // ——— Elementos ———
  addElement(kind: ElementKind): void {
    this.remember();
    const meta = elementMeta(kind);
    const element: FloorElement = {
      id: crypto.randomUUID(),
      zoneId: this.zoneId() || null,
      kind,
      label: meta.label,
      x: snap(CANVAS.width / 2 - meta.width / 2),
      y: snap(CANVAS.height / 2 - meta.height / 2),
      width: meta.width,
      height: meta.height,
      rotation: 0,
    };
    this.elements.update((list) => [...list, element]);
    this.selection.set({ kind: 'element', id: element.id });
  }

  duplicateElement(element: FloorElement): void {
    this.remember();
    const copy = { ...element, id: crypto.randomUUID(), x: element.x + 30, y: element.y + 30 };
    this.elements.update((list) => [...list, copy]);
    this.selection.set({ kind: 'element', id: copy.id });
  }

  deleteElement(id: string): void {
    this.remember();
    this.elements.update((list) => list.filter((item) => item.id !== id));
    this.selection.set(null);
  }

  editElement(id: string, patch: Partial<FloorElement>): void {
    this.remember();
    this.updateElement(id, (item) => ({
      ...item,
      ...patch,
      width: Math.max(10, Math.min(1200, Number(patch.width ?? item.width) || item.width)),
      height: Math.max(10, Math.min(800, Number(patch.height ?? item.height) || item.height)),
    }));
  }

  private updateElement(id: string, change: (item: FloorElement) => FloorElement): void {
    this.elements.update((list) => list.map((item) => (item.id === id ? change(item) : item)));
  }

  // ——— Arrastre con mouse o dedo ———
  startDrag(
    event: PointerEvent,
    kind: 'table' | 'element',
    id: string,
    mode: 'move' | 'resize' = 'move',
  ): void {
    const svg = this.svg()?.nativeElement;
    if (!svg || event.button > 0 || this.view3d()) return;
    event.stopPropagation();
    event.preventDefault();
    // Shift + clic (o el modo Meseros) suma la mesa a la selección múltiple.
    if (kind === 'table' && (event.shiftKey || this.mode() === 'WAITERS')) { this.toggleMulti(id); this.selection.set(null); return; }
    if (kind === 'table' && !this.multi().has(id)) this.multi.set(new Set([id]));
    if (kind === 'element') this.multi.set(new Set());
    this.selection.set({ kind, id });
    const start = svgPoint(svg, event);
    let origin = { x: 0, y: 0, width: 0, height: 0 };
    if (kind === 'table') {
      const place = this.placementOf(id);
      origin = { x: place.x || 0, y: place.y || 0, width: 0, height: 0 };
    } else {
      const element = this.elements().find((item) => item.id === id);
      if (!element) return;
      origin = { x: element.x, y: element.y, width: element.width, height: element.height };
    }
    const group = kind === 'table' && this.multi().size > 1
      ? Object.fromEntries([...this.multi()].map((tableId) => { const p = this.placementOf(tableId); return [tableId, { x: p.x || 0, y: p.y || 0 }]; }))
      : undefined;
    this.drag = { mode, selection: { kind, id }, start, origin, moved: false, group };
    svg.setPointerCapture(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    const drag = this.drag;
    const svg = this.svg()?.nativeElement;
    if (svg && this.marqueeStart) {
      const p = svgPoint(svg, event); const s = this.marqueeStart;
      this.marquee.set({ x: Math.min(p.x, s.x), y: Math.min(p.y, s.y), w: Math.abs(p.x - s.x), h: Math.abs(p.y - s.y) });
      return;
    }
    if (!drag || !svg) return;
    const point = svgPoint(svg, event);
    const dx = point.x - drag.start.x;
    const dy = point.y - drag.start.y;
    if (!drag.moved) {
      if (Math.hypot(dx, dy) < 3) return;
      drag.moved = true;
      this.remember();
      this.dragging.set(true);
    }
    if (drag.selection.kind === 'table') {
      // Imanes: la mesa que se arrastra se alinea con las demás que no se mueven.
      const moving = new Set(drag.group ? Object.keys(drag.group) : [drag.selection.id]);
      const others = this.placedTables().filter((item) => !moving.has(item.table.id)).map((item) => ({ x: item.place.x!, y: item.place.y! }));
      const snapped = event.altKey ? { point: { x: drag.origin.x + dx, y: drag.origin.y + dy }, guides: [] }
        : snapToGuides({ x: snap(drag.origin.x + dx), y: snap(drag.origin.y + dy) }, others);
      this.guides.set(snapped.guides);
      const next = clampPoint(snapped.point, 30);
      if (drag.group) {
        const ox = next.x - drag.origin.x, oy = next.y - drag.origin.y;
        this.placements.update((current) => { const copy = { ...current };
          Object.entries(drag.group!).forEach(([tableId, p]) => (copy[tableId] = { ...(copy[tableId] || defaultPlacement(tableId)), ...clampPoint({ x: p.x + ox, y: p.y + oy }, 30) }));
          return copy; });
      } else this.updatePlacement(drag.selection.id, next);
    } else if (drag.mode === 'resize') {
      this.updateElement(drag.selection.id, (item) => ({
        ...item,
        width: Math.max(10, snap(drag.origin.width + dx)),
        height: Math.max(10, snap(drag.origin.height + dy)),
      }));
    } else {
      this.updateElement(drag.selection.id, (item) => ({
        ...item,
        x: Math.max(0, Math.min(CANVAS.width - 10, snap(drag.origin.x + dx))),
        y: Math.max(0, Math.min(CANVAS.height - 10, snap(drag.origin.y + dy))),
      }));
    }
  }

  endDrag(event: PointerEvent): void {
    const svg = this.svg()?.nativeElement;
    if (svg?.hasPointerCapture(event.pointerId)) svg.releasePointerCapture(event.pointerId);
    const box = this.marquee();
    if (this.marqueeStart) {
      if (box && box.w > 8 && box.h > 8) {
        const inside = this.placedTables().filter((item) => item.place.x! >= box.x && item.place.x! <= box.x + box.w && item.place.y! >= box.y && item.place.y! <= box.y + box.h).map((item) => item.table.id);
        this.multi.update((current) => new Set([...current, ...inside]));
      }
      this.marqueeStart = null;
      this.marquee.set(null);
    }
    this.drag = null;
    this.dragging.set(false);
    this.guides.set([]);
  }

  clearSelection(): void {
    if (!this.drag) this.selection.set(null);
  }

  /** Flechas mueven lo seleccionado, Supr elimina un elemento y Ctrl+Z deshace. */
  @HostListener('document:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
      event.preventDefault();
      this.undo();
      return;
    }
    if (event.key === 'Escape') { this.multi.set(new Set()); this.view3d.set(false); }
    const current = this.selection();
    if (!current) return;
    if (event.key === 'Escape') this.selection.set(null);
    if ((event.key === 'Delete' || event.key === 'Backspace') && current.kind === 'element') {
      event.preventDefault();
      this.deleteElement(current.id);
      return;
    }
    const step = event.shiftKey ? 50 : 10;
    const delta: Record<string, Point> = {
      ArrowLeft: { x: -step, y: 0 },
      ArrowRight: { x: step, y: 0 },
      ArrowUp: { x: 0, y: -step },
      ArrowDown: { x: 0, y: step },
    };
    const move = delta[event.key];
    if (!move) return;
    event.preventDefault();
    this.remember();
    if (current.kind === 'table') {
      const place = this.placementOf(current.id);
      if (place.x === null || place.y === null) return;
      this.updatePlacement(
        current.id,
        clampPoint({ x: place.x + move.x, y: place.y + move.y }, 30),
      );
    } else {
      this.updateElement(current.id, (item) => ({
        ...item,
        x: Math.max(0, item.x + move.x),
        y: Math.max(0, item.y + move.y),
      }));
    }
  }

  isSelected(kind: 'table' | 'element', id: string): boolean {
    const current = this.selection();
    return current?.kind === kind && current.id === id;
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    const zoneId = this.zoneId() || null;
    this.api
      .put<FloorPlan>('/admin/floor', {
        zoneId,
        tables: this.zoneTables().map((table) => this.placementOf(table.id)),
        elements: this.elements(),
      })
      .subscribe({
        next: (plan) => {
          this.saving.set(false);
          this.placements.set(Object.fromEntries(plan.tables.map((item) => [item.id, item])));
          this.settings.set(plan.settings || this.settings());
          this.allElements = plan.elements;
          this.showZoneElements();
          this.toast.show(
            'Plano guardado',
            'Los meseros ya ven el nuevo plano del salón.',
            'success',
            '✓',
          );
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.show(
            'No se pudo guardar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          );
        },
      });
  }

  onElementPointer(value: ElementPointer): void {
    this.startDrag(value.event, 'element', value.element.id, value.mode);
  }

  tableTransform(place: TablePlacement): string {
    return `translate(${place.x} ${place.y})`;
  }
}
