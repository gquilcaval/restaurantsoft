import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { IngredientUnit, IngredientView, StockMovement, UNIT_LABELS } from '../../core/api/models';
import { InventoryInsightsComponent } from './inventory-insights.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { ToastService } from '../../shared/ui/toast.service';
import { businessDate, localTimestamp } from '../management/management.models';

type Action = 'PURCHASE' | 'WASTE' | 'COUNT';

const KIND_LABELS: Record<string, string> = {
  PURCHASE: 'Compra', SALE: 'Venta', VOID_RETURN: 'Devolución por anulación', WASTE: 'Merma', ADJUSTMENT: 'Ajuste por conteo'
};

/** Insumos de la sucursal: stock, compras, mermas, conteos físicos y kardex. */
@Component({
  imports: [AppShellComponent, IconComponent, FormsModule, CurrencyPipe, DecimalPipe, InventoryInsightsComponent],
  templateUrl: './inventory.page.html',
  styleUrls: ['./inventory.scss', './inventory-stock.scss', './inventory-panel.scss', './inventory-forms.scss']
})
export class InventoryPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly units = Object.entries(UNIT_LABELS) as [IngredientUnit, string][];
  readonly ingredients = signal<IngredientView[]>([]);
  readonly movements = signal<StockMovement[]>([]);
  readonly tab = signal<'TODAY' | 'STOCK' | 'MOVEMENTS'>('TODAY');
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.ingredients().find(item => item.id === this.selectedId()) || null);
  readonly history = signal<StockMovement[]>([]);
  readonly search = signal('');
  readonly onlyLow = signal(false);
  readonly editing = signal<IngredientView | 'NEW' | null>(null);
  readonly acting = signal<{ action: Action; ingredient: IngredientView } | null>(null);
  readonly busy = signal(false);
  name = ''; unit: IngredientUnit = 'KG'; costPerUnit = 0; minStock = 0; active = true;
  quantity = 0; unitCost = 0; note = '';
  from = businessDate(); to = businessDate(); movementIngredient = '';

  readonly visible = computed(() => {
    const term = this.search().trim().toLocaleLowerCase();
    return this.ingredients().filter(item => (!term || item.name.toLocaleLowerCase().includes(term)) && (!this.onlyLow() || item.low));
  });
  readonly totalValue = computed(() => this.ingredients().reduce((sum, item) => sum + Number(item.stockValue), 0));
  readonly lowCount = computed(() => this.ingredients().filter(item => item.low && item.active).length);
  readonly negativeCount = computed(() => this.ingredients().filter(item => item.stock < 0).length);

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.get<IngredientView[]>('/admin/inventory/ingredients').subscribe({
      next: value => this.ingredients.set(value), error: err => this.fail(err, 'No se pudo cargar el inventario.')
    });
  }

  loadMovements(): void {
    const filter = this.movementIngredient ? `&ingredientId=${this.movementIngredient}` : '';
    this.api.get<StockMovement[]>(`/admin/inventory/movements?from=${this.from}&to=${this.to}${filter}`).subscribe({
      next: value => this.movements.set(value), error: err => this.fail(err, 'No se pudieron cargar los movimientos.')
    });
  }

  showTab(tab: 'TODAY' | 'STOCK' | 'MOVEMENTS'): void { this.tab.set(tab); if (tab === 'MOVEMENTS') this.loadMovements(); }

  /** Abre la ficha del insumo con sus últimos movimientos. */
  select(item: IngredientView): void {
    this.selectedId.set(item.id); this.editing.set(null); this.acting.set(null); this.history.set([]);
    const since = new Date(Date.now() - 14 * 86400000).toISOString().slice(0, 10);
    this.api.get<StockMovement[]>(`/admin/inventory/movements?from=${since}&to=${businessDate()}&ingredientId=${item.id}`).subscribe({
      next: value => { if (this.selectedId() === item.id) this.history.set(value.slice(0, 8)); }, error: () => undefined
    });
  }
  closePanel(): void { this.selectedId.set(null); this.editing.set(null); this.acting.set(null); }
  /** Nivel del stock frente al mínimo (100 % = el doble del mínimo o más). */
  level(item: IngredientView): number { const min = Number(item.minStock); if (Number(item.stock) <= 0) return 0; if (!min) return 100; return Math.min(100, Number(item.stock) / (min * 2) * 100); }
  edit(item: IngredientView | 'NEW'): void {
    this.tab.set('STOCK'); this.acting.set(null);
    if (item === 'NEW') this.selectedId.set(null);
    if (item === 'NEW') { this.name = ''; this.unit = 'KG'; this.costPerUnit = 0; this.minStock = 0; this.active = true; }
    else { this.name = item.name; this.unit = item.unit; this.costPerUnit = Number(item.costPerUnit); this.minStock = Number(item.minStock); this.active = item.active; }
    this.editing.set(item);
  }

  save(): void {
    const editing = this.editing();
    if (!editing || !this.name.trim() || this.busy()) return;
    const body = { name: this.name, unit: this.unit, costPerUnit: this.costPerUnit || 0, minStock: this.minStock || 0, active: this.active };
    const request = editing === 'NEW' ? this.api.post('/admin/inventory/ingredients', body)
      : this.api.patch(`/admin/inventory/ingredients/${editing.id}`, body);
    this.run(request, () => { this.editing.set(null); this.toast.show('Insumo guardado', this.name, 'success', '✓'); });
  }

  act(action: Action, ingredient: IngredientView): void {
    this.selectedId.set(ingredient.id); this.editing.set(null);
    this.quantity = action === 'COUNT' ? Math.max(0, Number(ingredient.stock)) : 0;
    this.unitCost = Number(ingredient.costPerUnit); this.note = '';
    this.acting.set({ action, ingredient });
  }

  canSubmitAction(): boolean {
    const current = this.acting();
    if (!current || this.busy()) return false;
    if (current.action === 'COUNT') return this.quantity >= 0;
    if (current.action === 'WASTE') return this.quantity > 0 && !!this.note.trim();
    return this.quantity > 0 && this.unitCost >= 0;
  }

  submitAction(): void {
    const current = this.acting();
    if (!current || !this.canSubmitAction()) return;
    const base = `/admin/inventory/ingredients/${current.ingredient.id}`;
    const request = current.action === 'PURCHASE' ? this.api.post(`${base}/purchases`, { quantity: this.quantity, unitCost: this.unitCost, reference: this.note || null })
      : current.action === 'WASTE' ? this.api.post(`${base}/waste`, { quantity: this.quantity, reason: this.note })
      : this.api.post(`${base}/count`, { counted: this.quantity, note: this.note || null });
    const label = { PURCHASE: 'Compra registrada', WASTE: 'Merma registrada', COUNT: 'Conteo registrado' }[current.action];
    this.run(request, () => { this.acting.set(null); this.toast.show(label, current.ingredient.name, 'success', '✓'); if (this.tab() === 'MOVEMENTS') this.loadMovements(); this.select(current.ingredient); });
  }

  unitLabel(unit: IngredientUnit): string { return UNIT_LABELS[unit] || unit; }
  kindLabel(kind: string): string { return KIND_LABELS[kind] || kind; }
  timestamp(value: string): string { return localTimestamp(value, 'America/Lima'); }
  actionTitle(action: Action): string { return { PURCHASE: 'Registrar compra', WASTE: 'Registrar merma', COUNT: 'Conteo físico' }[action]; }

  private run(request: Observable<unknown>, next: () => void): void {
    this.busy.set(true);
    request.subscribe({
      next: () => { this.busy.set(false); next(); this.load(); },
      error: err => { this.busy.set(false); this.fail(err, 'No se pudo completar la operación.'); }
    });
  }

  private fail(err: { error?: { message?: string } }, fallback: string): void {
    this.toast.show('No se pudo completar', err.error?.message || fallback, 'error', '!');
  }
}
