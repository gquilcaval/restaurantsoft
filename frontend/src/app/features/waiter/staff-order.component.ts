import { CurrencyPipe } from '@angular/common';
import { Component, computed, ElementRef, HostListener, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { MenuItemView, ModifierGroupView, OrderSnapshot } from '../../core/api/models';
import { modifierRule, modifiersExtra, modifiersSummary, modifiersValid, selectedInGroup } from '../../shared/ordering/modifiers';

interface DraftLine {
  key: string;
  item: MenuItemView;
  quantity: number;
  notes: string;
  optionIds: string[];
  summary: string;
  unitPrice: number;
}

/** Toma de pedidos del mesero: arma la ronda en la tablet y la envía a cocina en un solo paso. */
@Component({
  selector: 'app-staff-order',
  imports: [CurrencyPipe, FormsModule],
  templateUrl: './staff-order.component.html',
  styleUrls: ['./staff-order.scss', './staff-order-cards.scss', './staff-order-cart.scss', './staff-order-cart-extra.scss', './staff-order-mobile.scss', './staff-order-cartbar.scss', './staff-order-hint.scss', './staff-order-options.scss', './staff-order-options2.scss', './staff-order-sheet.scss', './staff-order-notes.scss', './staff-order-swipe.scss']
})
export class StaffOrderComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly host = inject(ElementRef<HTMLElement>);
  readonly sessionId = input.required<string>();
  readonly tableLabel = input.required<string>();
  readonly sent = output<OrderSnapshot>();
  readonly closed = output<void>();

  readonly menu = signal<MenuItemView[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly sending = signal(false);
  readonly search = signal('');
  readonly category = signal('');
  readonly lines = signal<DraftLine[]>([]);
  readonly showCart = signal(false);
  readonly editing = signal<MenuItemView | null>(null);
  readonly options = signal<string[]>([]);
  quantity = 1;
  notes = '';

  readonly POPULAR = '__popular__';
  // ——— Deslizar para eliminar (como en iPhone) ———
  private readonly offsets = signal<Record<string, number>>({});
  readonly draggingKey = signal<string | null>(null);
  readonly removingKey = signal<string | null>(null);
  /** Línea recién agregada: entra con un destello. */
  readonly freshKey = signal<string | null>(null);
  private swipe: { key: string; pointerId: number; x: number; y: number; base: number; active: boolean } | null = null;
  swipeOffset(key: string): number { return this.offsets()[key] || 0; }
  private setOffset(key: string, value: number): void { this.offsets.update(current => ({ ...current, [key]: value })); }
  swipeStart(event: PointerEvent, line: DraftLine): void {
    if ((event.target as Element).closest('input, textarea') || event.button > 0) return;
    // Al empezar a deslizar otra línea, la que estaba abierta se cierra.
    for (const key of Object.keys(this.offsets())) if (key !== line.key && this.offsets()[key]) this.setOffset(key, 0);
    this.swipe = { key: line.key, pointerId: event.pointerId, x: event.clientX, y: event.clientY, base: this.swipeOffset(line.key), active: false };
  }
  swipeMove(event: PointerEvent): void {
    const swipe = this.swipe;
    if (!swipe || swipe.pointerId !== event.pointerId) return;
    const dx = event.clientX - swipe.x, dy = event.clientY - swipe.y;
    if (!swipe.active) {
      if (Math.abs(dx) < 8 || Math.abs(dx) < Math.abs(dy)) { if (Math.abs(dy) > 10) this.swipe = null; return; }
      swipe.active = true; this.draggingKey.set(swipe.key);
      (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    }
    const next = Math.min(0, swipe.base + dx);
    this.setOffset(swipe.key, next < -110 ? -110 + (next + 110) * 0.35 : next);
  }
  swipeEnd(event: PointerEvent, line: DraftLine): void {
    const swipe = this.swipe;
    this.swipe = null; this.draggingKey.set(null);
    if (!swipe?.active) return;
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
    const offset = this.swipeOffset(line.key);
    if (offset < -150 || (offset < -60 && event.clientX - swipe.x < -140)) this.removeLine(line);
    else this.setOffset(line.key, offset < -50 ? -96 : 0);
  }
  removeLine(line: DraftLine): void {
    navigator.vibrate?.(14);
    this.setOffset(line.key, -window.innerWidth);
    this.removingKey.set(line.key);
    window.setTimeout(() => {
      this.lines.update(current => current.filter(value => value.key !== line.key));
      this.offsets.update(current => { const next = { ...current }; delete next[line.key]; return next; });
      this.removingKey.set(null);
      if (this.noteEditing() === line.key) this.noteEditing.set(null);
    }, 280);
  }
  /** Tocar fuera de la nota la cierra, igual que «Listo»; también cierra una línea deslizada. */
  @HostListener('document:pointerdown', ['$event'])
  closeOnOutside(event: PointerEvent): void {
    const target = event.target as Element | null;
    if (this.noteEditing() && !target?.closest('.so-note-edit')) this.noteEditing.set(null);
    if (!target?.closest('.so-swipe.open')) {
      const open = Object.entries(this.offsets()).filter(([, value]) => value < 0);
      if (open.length && !this.swipe) for (const [key] of open) if (this.removingKey() !== key) this.setOffset(key, 0);
    }
  }

  // ——— Hoja del carrito (celular): arrastrar hacia abajo cierra, hacia arriba la agranda ———
  readonly sheetY = signal(0);
  readonly sheetHeight = signal(0);
  readonly sheetExpanded = signal(false);
  readonly sheetDragging = signal(false);
  private sheetDrag: { pointerId: number; y: number; time: number; base: number; full: number } | null = null;
  sheetStart(event: PointerEvent): void {
    if (!matchMedia('(max-width: 860px)').matches || (event.target as Element).closest('button')) return;
    const sheet = (event.currentTarget as HTMLElement).closest('.so-cart') as HTMLElement;
    const container = sheet.parentElement as HTMLElement;
    this.sheetDrag = { pointerId: event.pointerId, y: event.clientY, time: performance.now(), base: sheet.getBoundingClientRect().height, full: container.getBoundingClientRect().height };
    this.sheetDragging.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  sheetMove(event: PointerEvent): void {
    const drag = this.sheetDrag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const dy = event.clientY - drag.y;
    if (dy > 0 && !this.sheetExpanded()) { this.sheetHeight.set(0); this.sheetY.set(dy); return; }
    // Hacia arriba (o bajando desde pantalla completa) cambia la altura, con resistencia al pasar el tope.
    const height = drag.base - dy;
    this.sheetY.set(0);
    this.sheetHeight.set(height > drag.full ? drag.full + (height - drag.full) * 0.2 : Math.max(120, height));
  }
  sheetEnd(event: PointerEvent): void {
    const drag = this.sheetDrag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.sheetDrag = null; this.sheetDragging.set(false);
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
    const dy = event.clientY - drag.y;
    const speed = dy / Math.max(1, performance.now() - drag.time);
    const height = this.sheetHeight() || drag.base - Math.max(0, dy);
    if (!this.sheetExpanded()) {
      if (dy > Math.min(140, drag.base * 0.3) || speed > 0.5) { this.closeSheet(); return; }
      if (dy < -50 || speed < -0.4) this.sheetExpanded.set(true);
    } else if (dy > 0) {
      // Desde pantalla completa: un arrastre largo cierra; uno corto la devuelve a su tamaño normal.
      if (height < drag.full * 0.3) { this.closeSheet(); return; }
      if (dy > 90 || speed > 0.4) this.sheetExpanded.set(false);
    }
    this.sheetY.set(0); this.sheetHeight.set(0);
  }
  private closeSheet(): void {
    navigator.vibrate?.(6);
    this.sheetY.set(window.innerHeight); this.sheetHeight.set(0);
    window.setTimeout(() => { this.showCart.set(false); this.sheetY.set(0); this.sheetExpanded.set(false); }, 260);
  }

  /** Línea cuya nota se está editando (solo una a la vez). */
  readonly noteEditing = signal<string | null>(null);
  readonly categories = computed(() => [...new Set(this.menu().map(item => item.categoryName))]);
  readonly hasPopular = computed(() => this.menu().some(item => item.popular));
  /** Lo que la cuenta ya tiene pedido (rondas anteriores), para no repetir platos por error. */
  readonly previous = signal<OrderSnapshot | null>(null);
  readonly previousItems = computed(() => (this.previous()?.items || []).filter(item => item.status !== 'CANCELLED' && item.orderStatus !== 'DRAFT'));
  readonly previousCount = computed(() => this.previousItems().reduce((sum, item) => sum + item.quantity, 0));
  readonly previousTotal = computed(() => Number(this.previous()?.submittedTotal || 0));
  readonly quickNotes = ['Sin cebolla', 'Sin ají', 'Bien cocido', 'Término medio', 'Sin hielo', 'Para llevar'];
  /** Cambia con cada producto agregado para animar el contador del carrito. */
  readonly bump = signal(0);
  /** Platos recién agregados: su tarjeta late y muestra «+1». */
  readonly justAdded = signal(new Set<string>());
  readonly visible = computed(() => {
    const term = this.search().trim().toLocaleLowerCase();
    const category = this.category();
    return this.menu().filter(item => (!category || (category === this.POPULAR ? item.popular : item.categoryName === category))
      && (!term || item.name.toLocaleLowerCase().includes(term)));
  });
  /** Los últimos platos agregados, para las miniaturas de la barra «Ver pedido». */
  /** Primer plato disponible de la vista: su ＋ late para enseñar dónde tocar mientras la ronda está vacía. */
  readonly firstAvailableId = computed(() => this.visible().find(item => item.available)?.id || '');
  readonly recentLines = computed(() => this.lines().slice(-3).reverse());
  readonly count = computed(() => this.lines().reduce((sum, line) => sum + line.quantity, 0));
  readonly total = computed(() => this.lines().reduce((sum, line) => sum + line.unitPrice * line.quantity, 0));
  readonly optionsValid = computed(() => modifiersValid(this.editing()?.modifierGroups || [], this.options()));
  readonly editingUnitPrice = computed(() => {
    const item = this.editing();
    return item ? Number(item.price) + modifiersExtra(item.modifierGroups, this.options()) : 0;
  });

  ngOnInit(): void {
    this.api.get<OrderSnapshot>(`/staff/sessions/${this.sessionId()}/orders`).subscribe({ next: value => this.previous.set(value), error: () => undefined });
    this.api.get<MenuItemView[]>('/staff/menu').subscribe({
      next: items => { this.menu.set(items); this.loading.set(false); },
      error: err => { this.loading.set(false); this.error.set(err.error?.message || 'No se pudo cargar la carta.'); }
    });
  }

  /** Un producto sin opciones se agrega con un toque; con opciones se abre el detalle. */
  choose(item: MenuItemView, event?: Event): void {
    if (!item.available) return;
    if (!item.modifierGroups.length) { this.addLine(item, 1, '', []); this.celebrate(item, event); return; }
    this.editing.set(item);
    this.options.set([]);
    this.quantity = 1;
    this.notes = '';
  }

  confirmOptions(event?: Event): void {
    const item = this.editing();
    if (!item || !this.optionsValid()) return;
    this.addLine(item, this.quantity, this.notes.trim(), this.options());
    this.celebrate(item, event);
    this.editing.set(null);
  }

  // ——— Opciones del plato ———
  groupCount(group: ModifierGroupView): number { return selectedInGroup(group, this.options()); }
  groupRule(group: ModifierGroupView): string { return modifierRule(group); }
  isOpt(optionId: string): boolean { return this.options().includes(optionId); }
  /** Con un máximo de 1 funciona como opción única; con más, como casillas. */
  toggleOpt(group: ModifierGroupView, optionId: string): void {
    const current = this.options();
    if (current.includes(optionId)) { this.options.set(current.filter(id => id !== optionId)); return; }
    if (group.maxSelect === 1) {
      const ids = group.options.map(option => option.id);
      this.options.set([...current.filter(id => !ids.includes(id)), optionId]);
      return;
    }
    if (this.groupCount(group) < group.maxSelect) this.options.set([...current, optionId]);
  }
  /** El primer grupo obligatorio sin completar: el botón lo pide por su nombre. */
  readonly missingGroup = computed(() => (this.editing()?.modifierGroups || []).find(group => selectedInGroup(group, this.options()) < group.minSelect)?.name || '');

  adjustQuantity(delta: number): void { this.quantity = Math.max(1, Math.min(20, this.quantity + delta)); }

  changeLine(line: DraftLine, delta: number): void {
    const next = line.quantity + delta;
    this.lines.update(current => next < 1 ? current.filter(value => value.key !== line.key)
      : current.map(value => value.key === line.key ? { ...value, quantity: Math.min(20, next) } : value));
  }

  updateNotes(line: DraftLine, notes: string): void {
    this.lines.update(current => current.map(value => value.key === line.key ? { ...value, notes } : value));
  }

  /** Quita uno del último agregado de ese producto (desde la tarjeta de la carta). */
  decrease(item: MenuItemView): void {
    const line = [...this.lines()].reverse().find(value => value.item.id === item.id);
    if (line) this.changeLine(line, -1);
  }
  clearAll(): void { this.lines.set([]); this.noteEditing.set(null); }
  /** Las notas rápidas funcionan como interruptores: un toque las agrega y otro las quita. */
  hasNote(notes: string, text: string): boolean { return this.noteParts(notes).some(part => part.toLocaleLowerCase() === text.toLocaleLowerCase()); }
  toggleNoteText(notes: string, text: string): string {
    const parts = this.noteParts(notes);
    const next = this.hasNote(notes, text) ? parts.filter(part => part.toLocaleLowerCase() !== text.toLocaleLowerCase()) : [...parts, text];
    return next.join(', ');
  }
  toggleLineNote(line: DraftLine, text: string): void { this.updateNotes(line, this.toggleNoteText(line.notes, text)); }
  private noteParts(notes: string): string[] { return notes.split(',').map(part => part.trim()).filter(Boolean); }
  itemFor(line: DraftLine): MenuItemView { return line.item; }
  tagLabel(tag: string): string {
    return ({ SPICY: '🌶 Picante', VEGAN: '🌱 Vegano', VEGETARIAN: '🥗 Vegetariano', GLUTEN_FREE: 'Sin gluten', NEW: '✨ Nuevo', CHEF: '⭐ Del chef' } as Record<string, string>)[tag] || tag;
  }
  /** Color estable por nombre para los platos sin foto. */
  hue(name: string): number { let value = 0; for (const char of name) value = (value * 31 + char.charCodeAt(0)) % 360; return value; }

  quantityInCart(item: MenuItemView): number {
    return this.lines().filter(line => line.item.id === item.id).reduce((sum, line) => sum + line.quantity, 0);
  }

  send(): void {
    if (!this.lines().length || this.sending()) return;
    this.sending.set(true);
    this.error.set('');
    const items = this.lines().map(line => ({
      menuItemId: line.item.id, quantity: line.quantity, notes: line.notes || null, optionIds: line.optionIds
    }));
    this.api.post<OrderSnapshot>(`/staff/sessions/${this.sessionId()}/rounds`, { items }).subscribe({
      next: snapshot => { this.sending.set(false); this.sent.emit(snapshot); },
      error: err => {
        this.sending.set(false);
        this.error.set(err.error?.message || 'No se pudo enviar el pedido.');
        this.showCart.set(true);
        // Si algo se agotó mientras se tomaba el pedido, la carta debe reflejarlo.
        if (err.status === 409) this.ngOnInit();
      }
    });
  }

  /** Animación al agregar: la tarjeta late con «+1» y una burbuja del plato vuela hasta el carrito. */
  private celebrate(item: MenuItemView, event?: Event): void {
    navigator.vibrate?.(8);
    this.justAdded.update(current => new Set(current).add(item.id));
    window.setTimeout(() => this.justAdded.update(current => { const next = new Set(current); next.delete(item.id); return next; }), 650);
    const reduced = document.documentElement.classList.contains('reduce-motion') || matchMedia('(prefers-reduced-motion: reduce)').matches;
    const origin = (event?.currentTarget as HTMLElement | null)?.closest('.so-card')?.querySelector('.so-photo') as HTMLElement | null
      ?? event?.currentTarget as HTMLElement | null;
    const root = this.host.nativeElement as HTMLElement;
    const target = [root.querySelector('.so-cartbar'), root.querySelector('.so-cartbar-hint'), root.querySelector('.so-cart-head h3')]
      .find((el): el is HTMLElement => !!el && (el as HTMLElement).getClientRects().length > 0 && (el as HTMLElement).offsetParent !== null);
    if (reduced || !origin || !target || !('animate' in HTMLElement.prototype)) return;
    const from = origin.getBoundingClientRect(), to = target.getBoundingClientRect();
    const size = 46, startX = from.left + from.width / 2 - size / 2, startY = from.top + from.height / 2 - size / 2;
    const endX = to.left + Math.min(to.width / 2, 60) - size / 2, endY = to.top + to.height / 2 - size / 2;
    const ghost = document.createElement('div');
    ghost.setAttribute('aria-hidden', 'true');
    Object.assign(ghost.style, {
      position: 'fixed', left: `${startX}px`, top: `${startY}px`, width: `${size}px`, height: `${size}px`, zIndex: '1000',
      display: 'grid', placeItems: 'center', borderRadius: '50%', pointerEvents: 'none', overflow: 'hidden',
      color: '#fff', font: '800 1.1rem system-ui, sans-serif', boxShadow: '0 10px 24px rgba(0,0,0,.35), 0 0 0 3px rgba(255,255,255,.85)',
      background: item.imageUrl ? `center / cover url("${item.imageUrl}")` : `linear-gradient(135deg, hsl(${this.hue(item.name)} 55% 45%), hsl(${this.hue(item.name) + 40} 60% 32%))`,
    });
    if (!item.imageUrl) ghost.textContent = item.name.slice(0, 1);
    document.body.appendChild(ghost);
    // Respaldo: si el navegador pausa la animación (pestaña oculta), la burbuja no debe quedar pegada.
    window.setTimeout(() => ghost.remove(), 1200);
    const dx = endX - startX, dy = endY - startY, lift = Math.min(140, Math.abs(dx) * 0.35 + 60);
    ghost.animate([
      { transform: 'translate(0, 0) scale(1)', opacity: 1 },
      { transform: `translate(${dx * 0.5}px, ${dy * 0.5 - lift}px) scale(.95)`, opacity: 1, offset: 0.45 },
      { transform: `translate(${dx}px, ${dy}px) scale(.35)`, opacity: .4 },
    ], { duration: 650, easing: 'cubic-bezier(.45, 0, .25, 1)' }).finished.finally(() => {
      ghost.remove();
      target.animate([{ transform: 'scale(1)' }, { transform: 'scale(1.06)' }, { transform: 'scale(1)' }], { duration: 260, easing: 'ease-out' });
    });
  }

  /** Productos idénticos con las mismas opciones y nota se agrupan en una sola línea. */
  private addLine(item: MenuItemView, quantity: number, notes: string, optionIds: string[]): void {
    const key = `${item.id}|${[...optionIds].sort().join(',')}|${notes}`;
    const existing = this.lines().find(line => line.key === key);
    if (existing) { this.changeLine(existing, quantity); this.bump.update(value => value + 1); return; }
    this.bump.update(value => value + 1);
    const unitPrice = Number(item.price) + modifiersExtra(item.modifierGroups, optionIds);
    this.freshKey.set(key);
    window.setTimeout(() => { if (this.freshKey() === key) this.freshKey.set(null); }, 900);
    this.lines.update(current => [...current, {
      key, item, quantity, notes, optionIds, unitPrice, summary: modifiersSummary(item.modifierGroups, optionIds)
    }]);
  }
}
