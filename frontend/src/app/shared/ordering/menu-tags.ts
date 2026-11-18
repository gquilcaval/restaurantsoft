/** Etiquetas visibles de la carta, compartidas por la administración y la carta QR. */
export const MENU_TAGS: { value: string; icon: string; label: string }[] = [
  { value: 'SPICY', icon: '🌶', label: 'Picante' },
  { value: 'VEGAN', icon: '🌱', label: 'Vegano' },
  { value: 'VEGETARIAN', icon: '🥗', label: 'Vegetariano' },
  { value: 'GLUTEN_FREE', icon: '🌾', label: 'Sin gluten' },
  { value: 'NEW', icon: '✨', label: 'Nuevo' },
  { value: 'CHEF', icon: '👨‍🍳', label: 'Del chef' },
];

export const tagMeta = (value: string) => MENU_TAGS.find((tag) => tag.value === value);
