import { CurrencyPipe } from '@angular/common';
import { Component, computed, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrderItemView } from '../../core/api/models';

export interface VoidRequest { quantity: number; reason: string; }

interface Reason { label: string; icon: string; }

/** Confirma una anulación con cantidad y motivo; el motivo queda registrado para el dueño. */
@Component({
  selector: 'app-void-item-dialog',
  imports: [FormsModule, CurrencyPipe],
  templateUrl: './void-item-dialog.component.html',
  styleUrls: ['./void-item-dialog.scss', './void-item-dialog-parts.scss'],
})
export class VoidItemDialogComponent {
  readonly item = input<OrderItemView | null>(null);
  readonly busy = input(false);
  readonly confirmed = output<VoidRequest>();
  readonly cancelled = output<void>();
  readonly quantity = signal(1);
  readonly OTHER = 'Otro motivo';
  readonly reasons: Reason[] = [
    { label: 'Cliente se arrepintió', icon: '🙅' },
    { label: 'Se agotó en cocina', icon: '🚫' },
    { label: 'Error al tomar el pedido', icon: '✏️' },
    { label: 'Demora excesiva', icon: '⏱️' },
    { label: 'Cortesía de la casa', icon: '🎁' },
    { label: 'Otro motivo', icon: '💬' },
  ];
  readonly choice = signal('');
  readonly detail = signal('');
  /** Motivo final: el elegido, o el texto escrito si eligió «Otro motivo». */
  readonly reason = computed(() => this.choice() === this.OTHER ? this.detail().trim() : this.choice());
  readonly amount = computed(() => Number(this.item()?.unitPrice || 0) * this.quantity());

  constructor() {
    // Cada apertura empieza limpia para no arrastrar el motivo de una anulación anterior.
    effect(() => { const current = this.item(); if (current) { this.quantity.set(1); this.choice.set(''); this.detail.set(''); } });
  }

  // ——— Gesto (celular): arrastrar hacia abajo cierra sin anular ———
  readonly dragY = signal(0);
  readonly dragging = signal(false);
  private drag: { pointerId: number; y: number; time: number; height: number } | null = null;
  dragStart(event: PointerEvent): void {
    if (!matchMedia('(max-width: 620px)').matches || (event.target as Element).closest('button')) return;
    const sheet = (event.currentTarget as HTMLElement).closest('.vd') as HTMLElement;
    this.drag = { pointerId: event.pointerId, y: event.clientY, time: performance.now(), height: sheet.getBoundingClientRect().height };
    this.dragging.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  dragMove(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const dy = event.clientY - drag.y;
    this.dragY.set(dy > 0 ? dy : dy * 0.15);
  }
  dragEnd(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.drag = null; this.dragging.set(false);
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
    const dy = event.clientY - drag.y;
    const speed = dy / Math.max(1, performance.now() - drag.time);
    if (dy > Math.min(140, drag.height * 0.3) || speed > 0.5) {
      navigator.vibrate?.(6);
      this.dragY.set(window.innerHeight);
      window.setTimeout(() => { this.cancelled.emit(); this.dragY.set(0); }, 240);
      return;
    }
    this.dragY.set(0);
  }

  adjust(delta: number): void {
    const max = this.item()?.quantity || 1;
    this.quantity.update(value => Math.max(1, Math.min(max, value + delta)));
  }

  confirm(): void {
    if (!this.reason() || this.busy()) return;
    this.confirmed.emit({ quantity: this.quantity(), reason: this.reason() });
  }
}
