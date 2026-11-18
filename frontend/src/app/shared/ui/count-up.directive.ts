import { Directive, effect, ElementRef, inject, input, OnDestroy } from '@angular/core';

const money = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const whole = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

/** Anima un número desde su valor anterior hasta el nuevo (cifras de reportes y resúmenes). */
@Directive({ selector: '[appCountUp]' })
export class CountUpDirective implements OnDestroy {
  readonly appCountUp = input.required<number>();
  readonly countUpFormat = input<'currency' | 'number'>('number');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private current = 0;
  private frame = 0;

  constructor() {
    effect(() => this.animateTo(Number(this.appCountUp()) || 0, this.countUpFormat()));
  }

  private animateTo(target: number, format: 'currency' | 'number'): void {
    cancelAnimationFrame(this.frame);
    const from = this.current;
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    // Siempre hay una cifra visible; en segundo plano el navegador pausa las animaciones.
    if (reduced || from === target || document.hidden) {
      this.paint(target, format);
      return;
    }
    this.paint(from, format);
    const duration = 900;
    const start = performance.now();
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 4);
      this.paint(from + (target - from) * eased, format);
      if (progress < 1) this.frame = requestAnimationFrame(step);
    };
    this.frame = requestAnimationFrame(step);
  }

  private paint(value: number, format: 'currency' | 'number'): void {
    this.current = value;
    this.host.nativeElement.textContent =
      format === 'currency' ? `S/ ${money.format(value)}` : whole.format(Math.round(value));
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }
}
