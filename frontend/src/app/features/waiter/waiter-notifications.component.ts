import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

export type NotificationTone = 'info' | 'success' | 'warning' | 'error';

interface WaiterNotification {
  id: string;
  title: string;
  body: string;
  icon: string;
  tone: NotificationTone;
  createdAt: number;
  unread: boolean;
}

/**
 * Campana y bandeja de actividad del turno del mesero. El historial se guarda por usuario, sucursal y día
 * en el navegador, así sobrevive a una recarga sin mezclar turnos.
 */
@Component({
  selector: 'app-waiter-notifications',
  templateUrl: './waiter-notifications.component.html',
  host: { style: 'display: contents' }
})
export class WaiterNotificationsComponent implements OnInit {
  private readonly auth = inject(AuthService);
  readonly notifications = signal<WaiterNotification[]>([]);
  readonly notificationCenterOpen = signal(false);
  readonly unreadNotifications = computed(() => this.notifications().filter(item => item.unread).length);

  ngOnInit(): void { this.load(); }

  add(title: string, body: string, tone: NotificationTone, icon: string): void {
    const notification: WaiterNotification = {
      id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, title, body, tone, icon, createdAt: Date.now(), unread: true
    };
    this.notifications.update(current => [notification, ...current].slice(0, 50));
    this.persist();
  }

  toggleNotificationCenter(): void {
    const opening = !this.notificationCenterOpen();
    this.notificationCenterOpen.set(opening);
    if (opening && this.unreadNotifications()) {
      this.notifications.update(current => current.map(item => ({ ...item, unread: false })));
      this.persist();
    }
  }

  markAllRead(): void {
    if (!this.unreadNotifications()) return;
    this.notifications.update(current => current.map(item => ({ ...item, unread: false })));
    this.persist();
  }

  clearNotificationHistory(): void {
    this.notifications.set([]);
    this.persist();
  }

  notificationTime(timestamp: number): string {
    return new Intl.DateTimeFormat('es-PE', { hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp));
  }

  private storageKey(): string {
    const day = new Date().toLocaleDateString('en-CA');
    return `restos_waiter_notifications_${this.auth.branchId()}_${this.auth.userName()}_${day}`;
  }

  private load(): void {
    try {
      const stored = localStorage.getItem(this.storageKey());
      if (stored) this.notifications.set((JSON.parse(stored) as WaiterNotification[]).slice(0, 50));
    } catch {
      try { localStorage.removeItem(this.storageKey()); } catch { /* sin almacenamiento */ }
    }
  }

  private persist(): void {
    try { localStorage.setItem(this.storageKey(), JSON.stringify(this.notifications().slice(0, 50))); } catch { /* sin almacenamiento */ }
  }
}
