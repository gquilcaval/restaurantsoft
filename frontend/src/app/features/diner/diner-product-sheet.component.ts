import { CurrencyPipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { tagMeta } from '../../shared/ordering/menu-tags';
import { FormsModule } from '@angular/forms';
import { MenuItemView, OrderItemView } from '../../core/api/models';
import { ModifierPickerComponent } from '../../shared/ordering/modifier-picker.component';
import { modifiersExtra, modifiersValid, selectedInGroup } from '../../shared/ordering/modifiers';

export interface DishSelection {
  quantity: number;
  notes: string | null;
  optionIds: string[];
}

/** Ficha del plato para el comensal: opciones, cantidad y nota para cocina, o edición de un producto ya elegido. */
@Component({
  selector: 'app-diner-product-sheet',
  imports: [CurrencyPipe, FormsModule, ModifierPickerComponent],
  templateUrl: './diner-product-sheet.component.html',
  styleUrls: ['./diner-product-sheet.component.scss', './diner-product-sheet-options.scss', './diner-product-sheet-foot.scss'],
})
export class DinerProductSheetComponent {
  readonly dish = input<MenuItemView | null>(null);
  readonly editing = input<OrderItemView | null>(null);
  readonly busy = input(false);
  readonly closed = output<void>();
  readonly submitted = output<DishSelection>();
  readonly options = signal<string[]>([]);
  readonly quantity = signal(1);
  // ——— Indicaciones para cocina: atajos de un toque + texto libre solo para «otra cosa» ———
  readonly notesPanel = signal(false);
  readonly chosenNotes = signal<string[]>([]);
  customNote = '';
  /** Atajos según el plato: bebidas y comidas piden cosas distintas. */
  readonly noteChips = computed(() => {
    const category = (this.dish()?.categoryName || '').toLowerCase();
    return /bebida|jugo|refresco|trago|c[oó]ctel|cerveza|vino|caf[eé]|infusi/.test(category)
      ? ['Sin hielo', 'Poco hielo', 'Sin azúcar', 'Bien helada']
      : /postre|dulce|helado/.test(category)
        ? ['Para compartir', 'Sin azúcar', 'Con vela 🎂']
        : ['Sin cebolla', 'Sin picante', 'Picante aparte', 'Poca sal', 'Sin culantro'];
  });
  // ——— Gesto de la hoja ———
  readonly sheetY = signal(0);
  readonly expanded = signal(false);
  readonly dragging = signal(false);
  readonly closing = signal(false);
  private drag: { y: number; time: number; mode: '' | 'down' | 'up'; dy: number } | null = null;
  notes = '';

  /** Primer grupo obligatorio que aún no se completa (para decir qué falta). */
  readonly missingGroup = computed(() => {
    const selected = this.options();
    return (this.dish()?.modifierGroups || []).find((group) => selectedInGroup(group, selected) < group.minSelect)?.name || '';
  });
  readonly optionsValid = computed(() =>
    modifiersValid(this.dish()?.modifierGroups || [], this.options()),
  );
  readonly unitPrice = computed(() => {
    const dish = this.dish();
    return dish ? Number(dish.price) + modifiersExtra(dish.modifierGroups, this.options()) : 0;
  });

  constructor() {
    // Marca en verde los grupos obligatorios ya completos (el selector es compartido con el mesero).
    effect(() => {
      const groups = this.dish()?.modifierGroups || [];
      const selected = this.options();
      setTimeout(() => document.querySelectorAll('.ps .modifier-group').forEach((element, index) => {
        const group = groups[index];
        element.classList.toggle('dn-done', !!group && group.minSelect > 0 && selectedInGroup(group, selected) >= group.minSelect);
      }));
    });
    // Con la hoja abierta, el navegador no recarga la página ni rebota al arrastrar.
    document.documentElement.classList.add('dn-sheet-open');
    inject(DestroyRef).onDestroy(() => document.documentElement.classList.remove('dn-sheet-open'));
    // Cada apertura empieza limpia, o con los valores del producto que se está editando.
    effect(() => {
      const editing = this.editing();
      this.dish();
      this.options.set([]);
      this.sheetY.set(0); this.expanded.set(false); this.closing.set(false);
      this.quantity.set(editing?.quantity || 1);
      this.notes = editing?.notes || '';
      // Al editar, separa los atajos conocidos del texto libre.
      const parts = this.notes.split(',').map((part) => part.trim()).filter(Boolean);
      const chips = untracked(() => this.noteChips()).map((chip) => chip.toLowerCase());
      this.chosenNotes.set(parts.filter((part) => chips.includes(part.toLowerCase())));
      this.customNote = parts.filter((part) => !chips.includes(part.toLowerCase())).join(', ');
      this.notesPanel.set(false);
    });
  }

  close(): void {
    if (this.closing()) return;
    this.closing.set(true);
    this.dragging.set(false);
    this.sheetY.set(window.innerHeight);
    setTimeout(() => this.closed.emit(), 260);
  }
  dragStart(event: TouchEvent): void {
    if (event.touches.length !== 1) return;
    this.drag = { y: event.touches[0].clientY, time: Date.now(), mode: '', dy: 0 };
  }
  dragMove(event: TouchEvent): void {
    const drag = this.drag;
    if (!drag) return;
    const sheet = event.currentTarget as HTMLElement;
    const dy = event.touches[0].clientY - drag.y;
    if (!drag.mode) {
      // Solo se toma el gesto con el contenido arriba del todo; si no, es scroll normal.
      if (sheet.scrollTop > 0) return;
      // Arriba del todo y bajando: el gesto es de la hoja, no del navegador (evita «deslizar para recargar»).
      if (dy > 0) event.preventDefault();
      if (Math.abs(dy) < 6) return;
      if (dy > 0) drag.mode = 'down';
      else if (!this.expanded()) drag.mode = 'up';
      else { this.drag = null; return; }
      drag.y = event.touches[0].clientY; drag.time = Date.now();
      this.dragging.set(true);
      return;
    }
    event.preventDefault();
    drag.dy = event.touches[0].clientY - drag.y;
    if (drag.mode === 'down') this.sheetY.set(Math.max(0, drag.dy));
    else this.sheetY.set(Math.max(-40, Math.min(0, drag.dy / 3)));
  }
  dragEnd(): void {
    const drag = this.drag;
    this.drag = null;
    if (!drag?.mode) return;
    this.dragging.set(false);
    const speed = drag.dy / Math.max(1, Date.now() - drag.time);
    if (drag.mode === 'up') {
      if (drag.dy < -40 || speed < -0.4) this.expanded.set(true);
      this.sheetY.set(0);
      return;
    }
    const height = (document.querySelector('.diner-product-sheet') as HTMLElement | null)?.offsetHeight || window.innerHeight;
    if (this.expanded() && drag.dy < height * 0.45 && speed < 0.9) {
      if (drag.dy > 90 || speed > 0.4) this.expanded.set(false);
      this.sheetY.set(0);
      return;
    }
    if (drag.dy > Math.min(140, height * 0.3) || speed > 0.5) this.close();
    else this.sheetY.set(0);
  }
  hasNote(chip: string): boolean { return this.chosenNotes().includes(chip); }
  toggleNote(chip: string): void {
    this.chosenNotes.update((list) => (list.includes(chip) ? list.filter((item) => item !== chip) : [...list, chip]));
    this.composeNotes();
  }
  composeNotes(): void {
    this.notes = [...this.chosenNotes(), this.customNote.trim()].filter(Boolean).join(', ').slice(0, 300);
  }
  adjust(delta: number): void {
    this.quantity.update((value) => Math.max(1, Math.min(20, value + delta)));
  }

  submit(): void {
    if (this.dish() && !this.optionsValid()) {
      // Lleva al grupo que falta y lo resalta un instante.
      const group = document.querySelector('.ps .modifier-group.incomplete');
      group?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      group?.classList.remove('dn-attn');
      void (group as HTMLElement | null)?.offsetWidth;
      group?.classList.add('dn-attn');
      navigator.vibrate?.(20);
      return;
    }
    this.submitted.emit({
      quantity: this.quantity(),
      notes: this.notes.trim() || null,
      optionIds: this.options(),
    });
  }
  tagText(tag: string): string {
    const meta = tagMeta(tag);
    return meta ? `${meta.icon} ${meta.label}` : tag;
  }
}
