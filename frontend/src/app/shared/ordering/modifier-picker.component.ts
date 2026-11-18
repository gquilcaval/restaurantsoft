import { CurrencyPipe } from '@angular/common';
import { Component, input, model } from '@angular/core';
import { ModifierGroupView } from '../../core/api/models';
import { modifierRule, selectedInGroup } from './modifiers';

/** Selector de opciones y extras compartido por la carta del comensal y la toma de pedidos del mesero. */
@Component({
  selector: 'app-modifier-picker',
  imports: [CurrencyPipe],
  template: `
    @for (group of groups(); track group.id) {
      <fieldset class="modifier-group" [class.incomplete]="count(group) < group.minSelect">
        <legend><strong>{{ group.name }}</strong><small>{{ rule(group) }}</small></legend>
        <div class="modifier-options">
          @for (option of group.options; track option.id) {
            <button type="button" class="modifier-option" [class.selected]="isSelected(option.id)"
              [class.multi]="group.maxSelect > 1" [attr.aria-pressed]="isSelected(option.id)"
              [disabled]="!isSelected(option.id) && group.maxSelect > 1 && count(group) >= group.maxSelect"
              (click)="toggle(group, option.id)">
              <i aria-hidden="true"></i><span>{{ option.name }}</span>
              @if (option.priceDelta > 0) { <b>+{{ option.priceDelta | currency:'PEN':'S/ ' }}</b> }
            </button>
          }
        </div>
      </fieldset>
    }
  `
})
export class ModifierPickerComponent {
  readonly groups = input.required<ModifierGroupView[]>();
  readonly selected = model<string[]>([]);

  isSelected(optionId: string): boolean { return this.selected().includes(optionId); }
  count(group: ModifierGroupView): number { return selectedInGroup(group, this.selected()); }
  rule(group: ModifierGroupView): string { return modifierRule(group); }

  /** Con un máximo de 1 se comporta como opción única; con más, como casillas. */
  toggle(group: ModifierGroupView, optionId: string): void {
    const current = this.selected();
    if (current.includes(optionId)) {
      this.selected.set(current.filter(id => id !== optionId));
      return;
    }
    if (group.maxSelect === 1) {
      const groupIds = group.options.map(option => option.id);
      this.selected.set([...current.filter(id => !groupIds.includes(id)), optionId]);
      return;
    }
    if (this.count(group) < group.maxSelect) this.selected.set([...current, optionId]);
  }
}
