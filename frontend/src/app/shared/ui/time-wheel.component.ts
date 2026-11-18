import { AfterViewInit, Component, ElementRef, model, signal, viewChildren } from '@angular/core';

const ITEM = 44;

/**
 * Rueda de hora y minutos tipo iPhone (24 h): el número que queda al centro es el elegido.
 * Uso: <app-time-wheel [(value)]="hora" /> con hora en formato "HH:mm".
 */
@Component({
  selector: 'app-time-wheel',
  template: `
    <div class="tw" [class.tw-ready]="ready()">
      <div class="tw-col" #col data-kind="h" (scroll)="onScroll($event, 'h')" role="listbox" aria-label="Hora">
        @for (hour of hours; track hour) { <button type="button" [class.on]="hour === hourOf()" (click)="scrollTo('h', $index)">{{ hour }}</button> }
      </div>
      <b class="tw-colon">:</b>
      <div class="tw-col" #col data-kind="m" (scroll)="onScroll($event, 'm')" role="listbox" aria-label="Minutos">
        @for (minute of minutes; track minute) { <button type="button" [class.on]="minute === minuteOf()" (click)="scrollTo('m', $index)">{{ minute }}</button> }
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .tw { position: relative; display: flex; justify-content: center; align-items: center; gap: 6px; height: 176px; }
    .tw::before { content: ''; position: absolute; left: 0; right: 0; top: 66px; height: 44px; border-radius: 14px; background: color-mix(in srgb, var(--brand) 16%, transparent); pointer-events: none; }
    .tw:not(.tw-ready) .tw-col { visibility: hidden; }
    .tw-col { position: relative; width: 84px; height: 100%; overflow-y: auto; padding: 66px 0; box-sizing: border-box; scroll-snap-type: y mandatory; scrollbar-width: none; overscroll-behavior: contain; mask-image: linear-gradient(transparent, #000 32%, #000 68%, transparent); }
    .tw-col::-webkit-scrollbar { display: none; }
    .tw-col button { display: block; width: 100%; height: 44px; border: 0; color: var(--muted); background: none; font: 700 1.3rem var(--font-display); font-variant-numeric: tabular-nums; scroll-snap-align: center; transition: color .15s, transform .15s; }
    .tw-col button.on { color: var(--text); transform: scale(1.12); }
    .tw-colon { color: var(--muted); font: 800 1.3rem var(--font-display); }
  `],
})
export class TimeWheelComponent implements AfterViewInit {
  readonly value = model('08:00');
  readonly hours = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'));
  readonly minutes = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'));
  readonly ready = signal(false);
  private readonly columns = viewChildren<ElementRef<HTMLElement>>('col');

  hourOf(): string { return this.value().slice(0, 2); }
  minuteOf(): string { return this.value().slice(3, 5); }

  ngAfterViewInit(): void {
    // Se muestran las ruedas recién cuando ya están en la hora elegida: sin saltos visibles.
    requestAnimationFrame(() => {
      this.scrollTo('h', Number(this.hourOf()), 'auto');
      this.scrollTo('m', Number(this.minuteOf()), 'auto');
      this.ready.set(true);
    });
  }

  onScroll(event: Event, kind: 'h' | 'm'): void {
    if (!this.ready()) return;
    const list = kind === 'h' ? this.hours : this.minutes;
    const index = Math.max(0, Math.min(list.length - 1, Math.round((event.target as HTMLElement).scrollTop / ITEM)));
    const next = kind === 'h' ? `${list[index]}:${this.minuteOf()}` : `${this.hourOf()}:${list[index]}`;
    if (next !== this.value()) this.value.set(next);
  }

  scrollTo(kind: 'h' | 'm', index: number, behavior: ScrollBehavior = 'smooth'): void {
    this.columns().find((column) => column.nativeElement.dataset['kind'] === kind)?.nativeElement.scrollTo({ top: index * ITEM, behavior });
  }
}
