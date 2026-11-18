import { Component, inject, signal } from '@angular/core';
import { ToastService } from './toast.service';

/** Host global para que cualquier pantalla utilice la misma experiencia de notificación. */
@Component({
  selector: 'app-toast-host',
  template: `
    <div class="toast-stack" aria-live="polite">
      @for(toast of toasts.messages();track toast.id){
        <article [class]="'app-toast '+toast.tone" [class.dragging]="drag()?.id===toast.id" [style.--toast-life]="(toast.duration||5000)+'ms'"
          [style.transform]="drag()?.id===toast.id && drag()!.dy<0 ? 'translateY('+drag()!.dy+'px)' : null" [style.opacity]="drag()?.id===toast.id ? 1+drag()!.dy/120 : null"
          (pointerdown)="start($event, toast.id)" (pointermove)="move($event)" (pointerup)="end($event)" (pointercancel)="end($event)"><span class="notification-symbol">{{toast.icon}}</span><div><strong>{{toast.title}}</strong><p>{{toast.body}}</p></div><button (click)="toasts.dismiss(toast.id)" aria-label="Cerrar notificación">×</button></article>
      }
    </div>
  `,
  styles: `
    .app-toast { position: relative; overflow: hidden; animation: toast-spring .45s cubic-bezier(.2,1.4,.4,1) both; }
    .app-toast::after { content: ''; position: absolute; left: 0; bottom: 0; height: 3px; width: 100%; background: currentColor; opacity: .18; transform-origin: left; animation: toast-life var(--toast-life) linear both; }
    .app-toast.success .notification-symbol { animation: toast-pop .55s cubic-bezier(.3,1.8,.5,1) .12s both; }
    .app-toast.error { animation: toast-spring .45s both, toast-shake .4s .45s; }
    @keyframes toast-spring { from { opacity: 0; transform: translateX(40px) scale(.96); } }
    @keyframes toast-life { to { transform: scaleX(0); } }
    @keyframes toast-pop { from { transform: scale(0) rotate(-45deg); } }
    @keyframes toast-shake { 25% { transform: translateX(-5px); } 75% { transform: translateX(5px); } }
    /* Celular: arriba, centradas y a lo ancho; bajan desde arriba y se descartan deslizando hacia arriba. */
    @media (max-width: 620px) {
      .toast-stack { top: max(10px, env(safe-area-inset-top)) !important; left: 10px !important; right: 10px !important; width: auto !important; gap: 8px !important; z-index: 120 !important; }
      .app-toast { grid-template-columns: 36px minmax(0, 1fr) 28px !important; gap: 10px !important; padding: 10px 10px 10px 12px !important; border: 0 !important; border-radius: 20px !important; pointer-events: auto; touch-action: none;
        animation: toast-drop .45s cubic-bezier(.2,1.3,.4,1) both; transition: transform .25s cubic-bezier(.2,1,.3,1), opacity .25s; }
      .app-toast.dragging { transition: none; }
      .app-toast:nth-child(n+3) { display: none !important; }
      .app-toast p { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
      .app-toast .notification-symbol { width: 36px; height: 36px; border-radius: 12px; }
      .app-toast.error { animation: toast-drop .45s both, toast-shake .4s .45s; }
    }
    @keyframes toast-drop { from { opacity: 0; transform: translateY(-120%) scale(.96); } }
    @media (prefers-reduced-motion: reduce) { .app-toast, .app-toast::after, .notification-symbol { animation: none !important; } }
  `
})
export class ToastHostComponent {
  readonly toasts = inject(ToastService);
  /** Deslizar una alerta hacia arriba la descarta, como las notificaciones del teléfono. */
  readonly drag = signal<{ id: string; pointerId: number; y: number; dy: number } | null>(null);
  start(event: PointerEvent, id: string): void {
    if ((event.target as Element).closest('button')) return;
    this.drag.set({ id, pointerId: event.pointerId, y: event.clientY, dy: 0 });
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  move(event: PointerEvent): void {
    const drag = this.drag();
    if (!drag || drag.pointerId !== event.pointerId) return;
    const dy = event.clientY - drag.y;
    this.drag.set({ ...drag, dy: dy < 0 ? dy : dy * 0.2 });
  }
  end(event: PointerEvent): void {
    const drag = this.drag();
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.drag.set(null);
    if (drag.dy < -40) this.toasts.dismiss(drag.id);
  }
}
