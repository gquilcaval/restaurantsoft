import { Injectable, signal } from '@angular/core';

/**
 * Estado de la barra inferior del mesero, compartido entre la estructura común y la pantalla de mesas:
 * qué sección y vista mostrar, y cuántos pendientes hay (para el contador rojo en cualquier pantalla).
 */
@Injectable({ providedIn: 'root' })
export class WaiterNavService {
  readonly section = signal<'TABLES' | 'ALERTS'>('TABLES');
  readonly view = signal<'cards' | 'floor'>(this.savedView());
  readonly pending = signal(0);

  setView(mode: 'cards' | 'floor'): void {
    this.view.set(mode);
    try { localStorage.setItem('restos_waiter_view', mode); } catch { /* sin almacenamiento */ }
  }

  private savedView(): 'cards' | 'floor' {
    try { return localStorage.getItem('restos_waiter_view') === 'floor' ? 'floor' : 'cards'; } catch { return 'cards'; }
  }
}
