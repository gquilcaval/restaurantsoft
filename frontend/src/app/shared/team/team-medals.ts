/** Medallas del ranking del equipo, compartidas por el salón, el Modo TV y los reportes. */
export const MEDALS: Record<string, { icon: string; title: string; hint: string }> = {
  SALES: { icon: '💰', title: 'Rey de ventas', hint: 'Más cobrado' },
  STARS: { icon: '⭐', title: 'Favorito de los clientes', hint: 'Mejor calificación' },
  FAST: { icon: '⚡', title: 'El más rápido', hint: 'Atiende llamadas y cuentas antes' },
  TIPS: { icon: '💸', title: 'Imán de propinas', hint: 'Más propinas' },
  TABLES: { icon: '🍽️', title: 'Incansable', hint: 'Más mesas atendidas' },
};

export interface TeamMember {
  userId: string;
  name: string;
  rating: number | null;
  ratings: number;
  tips: number;
  collected: number;
  tables: number;
  responseMinutes: number | null;
  responses: number;
  score: number;
  medals: string[];
  me: boolean;
}

export interface TeamRanking {
  overall: number | null;
  waiters: TeamMember[];
  comments: unknown[];
}
