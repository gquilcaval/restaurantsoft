import { Injectable, signal } from '@angular/core';

export type ToastTone = 'info' | 'success' | 'warning' | 'error';
export interface ToastMessage {
  id: string;
  title: string;
  body: string;
  tone: ToastTone;
  icon: string;
  duration: number;
}

/** Muestra mensajes breves sin interrumpir el flujo ni ocupar espacio permanente. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly messages = signal<ToastMessage[]>([]);
  private readonly timers = new Map<string, number>();

  show(title: string, body: string, tone: ToastTone = 'info', icon = 'i', duration = 5000): void {
    const message: ToastMessage = {
      id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, title, body, tone, icon, duration
    };
    this.messages.update(current => [message, ...current].slice(0, 3));
    this.timers.set(message.id, window.setTimeout(() => this.dismiss(message.id), duration));
  }

  dismiss(id: string): void {
    this.messages.update(current => current.filter(message => message.id !== id));
    const timer = this.timers.get(id);
    if (timer) window.clearTimeout(timer);
    this.timers.delete(id);
  }
}
