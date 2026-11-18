/** Personas del equipo: tipos y textos compartidos por la lista y la ficha. */
export type Post = 'WAITER' | 'KITCHEN' | 'BRANCH_MANAGER' | 'STAFF';

export interface StaffPerson {
  id: string; fullName: string; email: string | null; role: Post; active: boolean; hasPin: boolean;
  canCounter: boolean; canReservations: boolean;
  documentNumber: string | null; phone: string | null; jobTitle: string | null; hiredOn: string | null;
  /** Falso para quien no usa RestOS (solo marca asistencia). */
  systemAccess: boolean;
  /** Tiene correo y contraseña para entrar desde su propio celular. */
  hasPassword: boolean;
}

/** Los cuatro puestos posibles; «Otro puesto» es personal que no usa el sistema. */
export const POSTS: { key: Post; label: string; hint: string; icon: string; tone: string }[] = [
  { key: 'WAITER', label: 'Mesero', hint: 'Atiende mesas', icon: 'utensils', tone: '#4f8cff' },
  { key: 'KITCHEN', label: 'Cocina', hint: 'Ve los pedidos', icon: 'chef', tone: '#f08a3c' },
  { key: 'BRANCH_MANAGER', label: 'Encargado', hint: 'Maneja el local', icon: 'star', tone: '#9b7bea' },
  { key: 'STAFF', label: 'Otro puesto', hint: 'No usa el sistema', icon: 'users', tone: '#7d8a84' },
];
export const OTHER_JOBS = ['Cajero', 'Barman', 'Lavaplatos', 'Limpieza', 'Seguridad', 'Repartidor', 'Anfitrión'];

export function postOf(role: Post): (typeof POSTS)[number] { return POSTS.find((post) => post.key === role) || POSTS[3]; }
/** Lo que se muestra bajo el nombre: su cargo si lo tiene; si no, su puesto. */
export function postLabel(person: StaffPerson): string {
  return person.jobTitle || (person.role === 'STAFF' ? 'Personal' : postOf(person.role).label);
}
/** Cómo entra o marca cada persona, en pocas palabras. */
export function entryLabel(person: StaffPerson): string {
  if (!person.systemAccess) return person.hasPin ? 'Marca con PIN' : '';
  if (person.hasPin && person.hasPassword) return 'PIN y correo';
  return person.hasPin ? 'Entra con PIN' : 'Entra con correo';
}
