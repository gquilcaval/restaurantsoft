import { ModifierGroupView } from '../../core/api/models';

/** Recargo total de las opciones elegidas; el servidor vuelve a calcularlo al registrar el pedido. */
export function modifiersExtra(groups: ModifierGroupView[], selected: string[]): number {
  return groups.flatMap(group => group.options)
    .filter(option => selected.includes(option.id))
    .reduce((sum, option) => sum + Number(option.priceDelta), 0);
}

export function selectedInGroup(group: ModifierGroupView, selected: string[]): number {
  return group.options.filter(option => selected.includes(option.id)).length;
}

/** Cada grupo debe respetar su mínimo y máximo, igual que la validación del servidor. */
export function modifiersValid(groups: ModifierGroupView[], selected: string[]): boolean {
  return groups.every(group => {
    const count = selectedInGroup(group, selected);
    return count >= group.minSelect && count <= group.maxSelect;
  });
}

/** Nombres elegidos en el orden de la carta, con el mismo formato que muestra cocina. */
export function modifiersSummary(groups: ModifierGroupView[], selected: string[]): string {
  return groups.flatMap(group => group.options)
    .filter(option => selected.includes(option.id))
    .map(option => option.name)
    .join(' · ');
}

export function modifierRule(group: ModifierGroupView): string {
  if (group.minSelect === 0) return group.maxSelect === 1 ? 'Opcional' : `Opcional · hasta ${group.maxSelect}`;
  if (group.minSelect === group.maxSelect) return group.minSelect === 1 ? 'Obligatorio · elige 1' : `Obligatorio · elige ${group.minSelect}`;
  return `Elige de ${group.minSelect} a ${group.maxSelect}`;
}
