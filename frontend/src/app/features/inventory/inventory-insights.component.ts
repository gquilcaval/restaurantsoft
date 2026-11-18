import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { IngredientUnit, UNIT_LABELS } from '../../core/api/models';
import { ToastService } from '../../shared/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';

interface StockForecast {
  ingredientId: string;
  name: string;
  unit: IngredientUnit;
  stock: number;
  consumedToday: number;
  perHour: number;
  runsOutAt: string | null;
  level: 'OK' | 'TODAY' | 'OUT';
  minStock: number;
}
interface ShoppingItem {
  ingredientId: string;
  name: string;
  unit: IngredientUnit;
  stock: number;
  minStock: number;
  dailyUse: number;
  suggested: number;
  estimatedCost: number;
  reason: string;
  supplierName: string | null;
  supplierPhone: string | null;
}
interface SupplierGroup {
  key: string;
  name: string;
  phone: string | null;
  items: (ShoppingItem & { quantity: number; selected: boolean })[];
}

/**
 * Inventario predictivo: a qué hora se acaba cada insumo al ritmo de hoy, y la lista de compras
 * sugerida agrupada por proveedor, lista para enviar por WhatsApp.
 */
@Component({
  selector: 'app-inventory-insights',
  imports: [FormsModule, CurrencyPipe, DatePipe, DecimalPipe, IconComponent],
  templateUrl: './inventory-insights.component.html',
  styleUrls: ['./inventory-insights.component.scss', './inventory-glass.scss'],
})
export class InventoryInsightsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly changed = output<void>();
  readonly units = UNIT_LABELS;
  readonly forecast = signal<StockForecast[]>([]);
  readonly groups = signal<SupplierGroup[]>([]);
  readonly editingSupplier = signal<string | null>(null);
  supplierName = '';
  supplierPhone = '';
  readonly urgent = computed(() => this.forecast().filter((item) => item.level !== 'OK'));
  readonly calm = computed(() =>
    this.forecast()
      .filter((item) => item.level === 'OK')
      .slice(0, 4),
  );
  readonly totalItems = computed(() =>
    this.groups().reduce((sum, group) => sum + group.items.length, 0),
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api
      .get<StockForecast[]>('/admin/inventory/forecast')
      .subscribe({ next: (value) => this.forecast.set(value), error: () => undefined });
    this.api.get<ShoppingItem[]>('/admin/inventory/shopping-list').subscribe({
      next: (value) => {
        const map = new Map<string, SupplierGroup>();
        for (const item of value) {
          const key = item.supplierName || '';
          const group = map.get(key) || {
            key,
            name: item.supplierName || 'Sin proveedor asignado',
            phone: item.supplierPhone,
            items: [],
          };
          if (!group.phone && item.supplierPhone) group.phone = item.supplierPhone;
          group.items.push({ ...item, quantity: Number(item.suggested), selected: true });
          map.set(key, group);
        }
        this.groups.set([...map.values()].sort((a, b) => (a.key ? 0 : 1) - (b.key ? 0 : 1)));
      },
      error: () => undefined,
    });
  }

  /** Porcentaje de stock restante respecto de lo que se ha consumido hoy (para la barra visual). */
  remaining(item: StockForecast): number {
    const base = Number(item.stock) + Number(item.consumedToday);
    return base > 0 ? Math.max(0, Math.min(100, (Number(item.stock) / base) * 100)) : 0;
  }

  hoursLeft(item: StockForecast): string {
    if (!item.runsOutAt) return '';
    const minutes = Math.max(
      0,
      Math.round((new Date(item.runsOutAt).getTime() - Date.now()) / 60000),
    );
    if (minutes < 60) return `en ${minutes} min`;
    const hours = Math.floor(minutes / 60);
    return hours >= 24 ? `en ${Math.round(hours / 24)} días` : `en ${hours} h ${minutes % 60} min`;
  }

  groupCost(group: SupplierGroup): number {
    return group.items
      .filter((item) => item.selected)
      .reduce(
        (sum, item) =>
          sum +
          (Number(item.estimatedCost) / Math.max(0.001, Number(item.suggested))) * item.quantity,
        0,
      );
  }

  message(group: SupplierGroup): string {
    const lines = group.items
      .filter((item) => item.selected && item.quantity > 0)
      .map((item) => `• ${item.name}: ${item.quantity} ${this.units[item.unit]}`);
    const date = new Intl.DateTimeFormat('es-PE', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    }).format(new Date());
    return `Hola${group.key ? ' ' + group.name : ''} 👋, quisiera hacer el siguiente pedido (${date}):\n\n${lines.join('\n')}\n\n¿Me confirma disponibilidad y hora de entrega? ¡Gracias!`;
  }

  whatsapp(group: SupplierGroup): void {
    const text = encodeURIComponent(this.message(group));
    const phone = this.normalizePhone(group.phone);
    window.open(
      phone ? `https://wa.me/${phone}?text=${text}` : `https://wa.me/?text=${text}`,
      '_blank',
      'noopener',
    );
  }

  copy(group: SupplierGroup): void {
    navigator.clipboard
      .writeText(this.message(group))
      .then(() =>
        this.toast.show(
          'Pedido copiado',
          'Pégalo en WhatsApp, correo o donde lo necesites.',
          'success',
          '📋',
        ),
      )
      .catch(() =>
        this.toast.show('No se pudo copiar', 'Selecciona el texto manualmente.', 'error', '!'),
      );
  }

  editSupplier(group: SupplierGroup): void {
    this.editingSupplier.set(group.key || '__none__');
    this.supplierName = group.key ? group.name : '';
    this.supplierPhone = group.phone || '';
  }

  /** Asigna el proveedor a todos los insumos del grupo. */
  saveSupplier(group: SupplierGroup): void {
    const requests = group.items.map((item) =>
      this.api.put(`/admin/inventory/ingredients/${item.ingredientId}/supplier`, {
        name: this.supplierName,
        phone: this.supplierPhone,
      }),
    );
    let pending = requests.length;
    requests.forEach((request) =>
      request.subscribe({
        next: () => {
          if (--pending === 0) {
            this.editingSupplier.set(null);
            this.toast.show(
              'Proveedor guardado',
              'La próxima lista ya saldrá agrupada con su número.',
              'success',
              '✓',
            );
            this.load();
          }
        },
        error: (err) =>
          this.toast.show(
            'No se pudo guardar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          ),
      }),
    );
  }

  /** Números peruanos de 9 dígitos reciben el código de país 51 para que WhatsApp los reconozca. */
  private normalizePhone(phone: string | null): string {
    const digits = (phone || '').replace(/\D/g, '');
    return digits.length === 9 ? `51${digits}` : digits;
  }
}
