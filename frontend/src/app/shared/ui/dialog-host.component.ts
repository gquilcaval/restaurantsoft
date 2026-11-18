import { Component, effect, HostListener, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DialogService } from './dialog.service';

/** Host único para confirmaciones y solicitudes de texto en cualquier pantalla de RestOS. */
@Component({
  selector: 'app-dialog-host',
  imports: [FormsModule],
  template: `
    @if(dialog.current(); as current){
      <div class="app-dialog-backdrop" (mousedown)="dialog.cancel()">
        <section class="app-dialog" [class]="'app-dialog '+current.tone" role="dialog" aria-modal="true" [attr.aria-labelledby]="'app-dialog-title'" (mousedown)="$event.stopPropagation()">
          <button class="app-dialog-close" type="button" (click)="dialog.cancel()" aria-label="Cerrar">×</button>
          <div class="app-dialog-icon">{{current.icon}}</div>
          <div class="app-dialog-copy"><h2 id="app-dialog-title">{{current.title}}</h2><p>{{current.message}}</p></div>
          @if(current.kind==='prompt'){
            <label class="app-dialog-input">{{current.inputLabel}}<textarea autofocus [(ngModel)]="inputValue" [placeholder]="current.placeholder||''" [maxlength]="current.maxLength||250"></textarea></label>
          }
          <div class="app-dialog-actions"><button type="button" class="secondary" (click)="dialog.cancel()">{{current.cancelLabel}}</button><button type="button" class="primary" [class.danger-action]="current.tone==='danger'" [disabled]="current.kind==='prompt'&&current.required&&!inputValue.trim()" (click)="accept()">{{current.confirmLabel}}</button></div>
        </section>
      </div>
    }
  `,
  styles: `
    .app-dialog { animation: dialog-spring .38s cubic-bezier(.2,1.35,.4,1) both; }
    .app-dialog-icon { animation: dialog-icon .6s cubic-bezier(.3,1.8,.5,1) .1s both; }
    .app-dialog.danger .app-dialog-icon, .app-dialog.warning .app-dialog-icon { animation: dialog-icon .6s .1s both, dialog-wiggle .5s .7s; }
    .app-dialog-actions .primary { transition: transform .15s ease; }
    .app-dialog-actions .primary:active:not(:disabled) { transform: scale(.95); }
    @keyframes dialog-spring { from { opacity: 0; transform: translateY(18px) scale(.94); } }
    @keyframes dialog-icon { from { transform: scale(0) rotate(-30deg); } }
    @keyframes dialog-wiggle { 25% { transform: rotate(-10deg); } 75% { transform: rotate(10deg); } }
    @media (prefers-reduced-motion: reduce) { .app-dialog, .app-dialog-icon { animation: none !important; } }
  `
})
export class DialogHostComponent {
  readonly dialog = inject(DialogService);
  inputValue = '';

  constructor() {
    effect(() => {
      const current = this.dialog.current();
      if (current) this.inputValue = current.initialValue || '';
    });
  }

  accept(): void {
    const current = this.dialog.current();
    if (!current) return;
    this.dialog.complete(current.kind === 'prompt' ? this.inputValue.trim() : true);
  }

  @HostListener('document:keydown.escape')
  cancelOnEscape(): void { if (this.dialog.current()) this.dialog.cancel(); }
}
