import { Component, input, output } from '@angular/core';
import { doorArc, elementMeta, FloorElement, stairSteps } from './floor.models';

export interface ElementPointer {
  event: PointerEvent;
  element: FloorElement;
  mode: 'move' | 'resize';
}

/** Capa de elementos del local (barra, paredes, puertas…) dibujada dentro del SVG del plano. */
@Component({
  selector: 'g[appFloorElements]',
  template: `
    @for (el of elements(); track el.id) {
      <svg:g
        class="fp-el"
        [attr.data-kind]="el.kind"
        [class.selected]="selectedId() === el.id"
        [class.editable]="editable()"
        [attr.transform]="transform(el)"
        (pointerdown)="editable() && pointer.emit({ event: $event, element: el, mode: 'move' })"
      >
        @if (el.kind === 'PLANT') {
          <svg:circle
            class="fp-el-shape"
            [attr.cx]="el.x + el.width / 2"
            [attr.cy]="el.y + el.height / 2"
            [attr.r]="radius(el)"
          />
          <svg:text
            class="fp-el-icon"
            [attr.x]="el.x + el.width / 2"
            [attr.y]="el.y + el.height / 2"
          >
            🪴
          </svg:text>
        } @else {
          <svg:rect
            class="fp-el-shape"
            [attr.x]="el.x"
            [attr.y]="el.y"
            [attr.width]="el.width"
            [attr.height]="el.height"
            [attr.rx]="el.kind === 'WALL' || el.kind === 'WINDOW' || el.kind === 'DOOR' ? 3 : 12"
          />
          @if (el.kind === 'STAIRS') {
            @for (y of steps(el); track y) {
              <svg:line
                class="fp-step"
                [attr.x1]="el.x + 6"
                [attr.x2]="el.x + el.width - 6"
                [attr.y1]="y"
                [attr.y2]="y"
              />
            }
          }
          @if (el.kind === 'DOOR') {
            <svg:path class="fp-door-arc" [attr.d]="arc(el)" />
          }
          @if (el.width >= 60 && el.height >= 34) {
            <svg:text
              class="fp-el-label"
              [attr.x]="el.x + el.width / 2"
              [attr.y]="el.y + el.height / 2"
            >
              {{ icon(el) }} {{ el.label }}
            </svg:text>
          }
        }
        @if (editable() && selectedId() === el.id) {
          <svg:rect
            class="fp-handle"
            [attr.x]="el.x + el.width - 9"
            [attr.y]="el.y + el.height - 9"
            width="18"
            height="18"
            rx="5"
            (pointerdown)="
              $event.stopPropagation(); pointer.emit({ event: $event, element: el, mode: 'resize' })
            "
          />
        }
      </svg:g>
    }
  `,
  styleUrl: './floor-elements.scss',
})
export class FloorElementsComponent {
  readonly elements = input.required<FloorElement[]>();
  readonly editable = input(false);
  readonly selectedId = input<string | null>(null);
  readonly pointer = output<ElementPointer>();

  transform(el: FloorElement): string {
    return `rotate(${el.rotation} ${el.x + el.width / 2} ${el.y + el.height / 2})`;
  }
  radius(el: FloorElement): number {
    return Math.min(el.width, el.height) / 2;
  }
  steps(el: FloorElement): number[] {
    return stairSteps(el);
  }
  arc(el: FloorElement): string {
    return doorArc(el);
  }
  icon(el: FloorElement): string {
    return elementMeta(el.kind).icon;
  }
}
