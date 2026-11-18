import { Component, computed, input } from '@angular/core';
import { TableView } from '../../core/api/models';
import { firstName, waiterHue } from './served-by';

/** Cuántas mesas lleva cada mesero ahora, y cuántas no tienen mesero (para repartir la carga). */
@Component({
  selector: 'app-waiter-load',
  template: `
    @if (load().waiters.length || load().unassigned) {
      <div class="wl" aria-label="Mesas por mesero">
        @for (waiter of load().waiters; track waiter.id) {
          <span [style.--hue]="waiter.hue" [class.away]="waiter.away"><i></i>{{ waiter.name }} <b>{{ waiter.tables }}</b></span>
        }
        @if (load().unassigned) { <span class="none"><i></i>Sin mesero <b>{{ load().unassigned }}</b></span> }
      </div>
    }
  `,
  styles: [`
    .wl { display: flex; flex-wrap: wrap; gap: 6px; }
    .wl span { --hue: 210; display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 999px; background: color-mix(in srgb, var(--text) 6%, transparent); font-size: .76rem; font-weight: 700; }
    .wl i { width: 8px; height: 8px; border-radius: 50%; background: hsl(var(--hue) 55% 48%); }
    .wl span.none i { background: var(--warn); }
    .wl span.away { opacity: .6; }
    .wl b { font-weight: 800; }
  `],
})
export class WaiterLoadComponent {
  readonly tables = input.required<TableView[]>();
  readonly load = computed(() => {
    const load = new Map<string, { id: string; name: string; tables: number; hue: number; away: boolean }>();
    let unassigned = 0;
    for (const table of this.tables().filter((item) => item.status === 'OPEN')) {
      if (!table.servedById) { unassigned++; continue; }
      const current = load.get(table.servedById)
        || { id: table.servedById, name: firstName(table.servedByName), tables: 0, hue: waiterHue(table.servedById), away: !!table.servedByAway };
      load.set(table.servedById, { ...current, tables: current.tables + 1 });
    }
    return { waiters: [...load.values()].sort((a, b) => b.tables - a.tables), unassigned };
  });
}
