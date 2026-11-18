export interface TableView {
  id: string;
  label: string;
  capacity: number;
  qrPublicId: string;
  status: string;
  sessionId: string | null;
  requiresReview: boolean;
  lastActivityAt: string | null;
  zoneId: string | null;
  openedAt: string | null;
  /** Mesero asignado desde el estudio de planos. */
  waiterId?: string | null;
  /** Mesero a cargo de la visita actual (recibe los avisos de la mesa). */
  servedById?: string | null;
  servedByName?: string | null;
  /** El mesero a cargo no está en turno (no marcó entrada o está en refrigerio). */
  servedByAway?: boolean;
}

/** Zona del local (salón, terraza, barra, VIP…). Cerrada: no abre mesas nuevas por QR. */
export interface ZoneView {
  id: string;
  name: string;
  color: string;
  displayOrder: number;
  open: boolean;
  active: boolean;
  tables: number;
  occupied: number;
  waiterIds: string[];
}

export interface GuestView { id: string; displayName: string; host: boolean; active: boolean; leftAt: string | null; }

export interface OrderItemView {
  id: string;
  orderId: string;
  roundNumber: number;
  orderStatus: string;
  /** Nulo cuando el plato lo registró el personal desde el salón. */
  guestId: string | null;
  name: string;
  unitPrice: number;
  quantity: number;
  notes: string | null;
  status: string;
  /** Opciones elegidas, ya resumidas para cocina (p. ej. «Medio · Queso extra»). */
  modifiers: string | null;
  voidReason: string | null;
  createdByName: string | null;
  stationId: string | null;
  stationName: string | null;
  /** Cuándo se envió la ronda a cocina (para medir la espera). */
  submittedAt?: string | null;
  /** Su estación trabaja con comanda impresa: el mesero lo sirve directo (avisa el timbre de cocina). */
  printed?: boolean;
}

/** Comanda impresa de una ronda en una estación. */
export interface PrintJobView {
  id: string;
  orderId: string;
  stationName: string | null;
  kind: string;
  status: 'PENDING' | 'SENT' | 'PRINTED' | 'FAILED';
  error: string | null;
  createdAt: string;
}

export interface OrderSnapshot {
  sessionId: string;
  guests: GuestView[];
  items: OrderItemView[];
  submittedTotal: number;
  generatedAt: string;
  /** Nombre de pila del mesero a cargo de la mesa (null si aún no tiene). */
  waiterName?: string | null;
  /** Comandas impresas de la mesa (si alguna estación imprime). */
  printJobs?: PrintJobView[];
}

export interface ModifierOptionView {
  id: string;
  name: string;
  priceDelta: number;
  /** Insumo que consume la opción (p. ej. queso para «Queso extra»); nulo si no descuenta inventario. */
  ingredientId: string | null;
  ingredientQuantity: number | null;
}

export interface ModifierGroupView {
  id: string;
  name: string;
  minSelect: number;
  maxSelect: number;
  options: ModifierOptionView[];
}

export interface MenuItemView {
  id: string;
  categoryId: string;
  categoryName: string;
  name: string;
  description: string;
  price: number;
  imageUrl: string | null;
  /** Falso cuando el producto se marcó como agotado en la sucursal. */
  available: boolean;
  modifierGroups: ModifierGroupView[];
  /** Etiquetas de la carta: SPICY, VEGAN, VEGETARIAN, GLUTEN_FREE, NEW, CHEF. */
  tags: string[];
  /** Entre los más pedidos de la semana en esta sucursal. */
  popular: boolean;
  soldToday: number;
}

export interface GuestSession {
  sessionId: string;
  guestId: string;
  tableToken: string;
  guestToken: string;
  displayName: string;
  host: boolean;
  qrPublicId: string;
  tableLabel?: string;
}

export interface TableTransferView {
  id: string;
  guestId: string;
  displayName: string;
  sourceSessionId: string;
  sourceTableLabel: string;
  destinationTableLabel: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  reason: string | null;
  requestedAt: string;
}

export interface PublicTransferStatus {
  id: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  sourceTableLabel: string;
  destinationTableLabel: string;
  reason: string | null;
  sessionId: string | null;
  guestId: string;
  tableToken: string | null;
  guestToken: string;
  displayName: string;
  host: boolean;
  qrPublicId: string;
}

/** Información mínima y no sensible que el comensal necesita para elegir una mesa libre. */
export interface AvailableTableView {
  qrPublicId: string;
  label: string;
  capacity: number;
}

export interface ServiceRequestView {
  id: string;
  sessionId: string;
  tableLabel: string;
  type: 'WAITER_CALL' | 'BILL' | 'TABLE_EMPTY';
  status: 'PENDING' | 'IN_PROGRESS' | 'RESOLVED';
  requestedAt: string;
  /** Al pedir la cuenta: cómo quiere pagar la mesa (p. ej. «3 pagos separados · propina 10 %»). */
  note?: string | null;
  /** Quién va a atender un llamado («Voy»): el comensal ve «Carlos va en camino». */
  waiterName?: string | null;
  /** Motivo del llamado (opcional): CUTLERY, DRINK, QUESTION, PROBLEM. */
  reason?: string | null;
  /** 0 normal · 1 la mesa dijo «Aún no» · 2 se avisó a todo el equipo. */
  escalation?: number;
  /** Cuándo el mesero tocó «Voy» (para el reloj de 5 minutos y la pregunta a la mesa). */
  attendedAt?: string | null;
}

export interface PaymentView {
  id: string;
  method: 'CASH' | 'CARD' | 'YAPE' | 'PLIN';
  amount: number;
  reference: string | null;
  paidAt: string;
  tip: number;
  amountReceived: number | null;
  changeGiven: number;
  guestId: string | null;
}

/** Parte de la cuenta de cada comensal; guestId nulo agrupa lo pedido por el mesero para toda la mesa. */
export interface GuestShare {
  guestId: string | null;
  name: string;
  consumed: number;
  due: number;
  paid: number;
  pending: number;
}

export interface DiscountView {
  kind: 'PERCENT' | 'AMOUNT';
  value: number;
  amount: number;
  reason: string;
}

export interface PaymentSummary {
  sessionId: string;
  subtotal: number;
  discount: DiscountView | null;
  deliveryFee: number;
  total: number;
  paid: number;
  remaining: number;
  tips: number;
  payments: PaymentView[];
  shares: GuestShare[];
  /** Solicitud de cuenta activa; IN_PROGRESS significa que el consumo está congelado para cobrar. */
  bill: { requestId: string; status: 'PENDING' | 'IN_PROGRESS' } | null;
  /** Lo que eligió la mesa desde el celular al pedir la cuenta. */
  preference: BillPreference | null;
}

export interface BillPreference {
  mode: 'SINGLE' | 'GUEST' | 'EQUAL';
  parts: number | null;
  tipPercent: number;
  note: string | null;
}

export type OrderChannel = 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY';

export interface CounterOrderView {
  sessionId: string;
  channel: Exclude<OrderChannel, 'DINE_IN'>;
  label: string;
  orderNumber: number;
  customerName: string;
  customerPhone: string | null;
  deliveryAddress: string | null;
  deliveryReference: string | null;
  deliveryFee: number;
  openedAt: string;
  itemCount: number;
  stage: 'NEW' | 'IN_KITCHEN' | 'READY' | 'DELIVERED';
  total: number;
  paid: number;
  remaining: number;
}

export interface KitchenOrder {
  id: string;
  sessionId: string;
  tableLabel: string;
  channel: OrderChannel;
  roundNumber: number;
  status: string;
  submittedAt: string;
  items: OrderItemView[];
}

export type IngredientUnit = 'KG' | 'G' | 'L' | 'ML' | 'UNIDAD';
export const UNIT_LABELS: Record<IngredientUnit, string> = { KG: 'kg', G: 'g', L: 'L', ML: 'ml', UNIDAD: 'und.' };

export interface IngredientView {
  id: string;
  name: string;
  unit: IngredientUnit;
  costPerUnit: number;
  minStock: number;
  active: boolean;
  stock: number;
  stockValue: number;
  /** Stock igual o menor al mínimo configurado. */
  low: boolean;
  usedIn: number;
}

export interface StockMovement {
  id: string;
  createdAt: string;
  kind: 'PURCHASE' | 'SALE' | 'VOID_RETURN' | 'WASTE' | 'ADJUSTMENT';
  ingredient: string;
  unit: IngredientUnit;
  quantity: number;
  unitCost: number;
  reference: string | null;
  userName: string;
}

export interface RecipeView {
  menuItemId: string;
  price: number;
  lines: { ingredientId: string; name: string; unit: IngredientUnit; quantity: number; cost: number }[];
  cost: number;
  margin: number;
  foodCostPercent: number;
}

export interface CustomerView {
  id: string;
  fullName: string;
  phone: string;
  email: string | null;
  birthday: string | null;
  notes: string | null;
  points: number;
  visits: number;
  totalSpent: number;
  lastVisitAt: string | null;
}

export interface CustomerDetail {
  customer: CustomerView;
  visits: { sessionId: string; closedAt: string; label: string; branch: string; paid: number }[];
  points: { id: string; points: number; reason: string; createdAt: string }[];
  favorites: { name: string; quantity: number }[];
}

export interface LoyaltyProgram { enabled: boolean; spendPerPoint: number; pointValue: number; minRedeem: number; }

export interface LoyaltyState { customer: CustomerView | null; redeemedPoints: number; program: LoyaltyProgram; }

export interface ReservationView {
  id: string;
  customerName: string;
  phone: string;
  partySize: number;
  /** Fecha y hora local del restaurante (sin zona). */
  reservedAt: string;
  durationMinutes: number;
  tableId: string | null;
  tableLabel: string | null;
  tableCapacity: number | null;
  status: 'PENDING' | 'CONFIRMED' | 'SEATED' | 'CANCELLED' | 'NO_SHOW';
  notes: string | null;
  customerId: string | null;
  customerVisits: number;
  sessionId: string | null;
  zoneId: string | null;
  zoneName: string | null;
}
