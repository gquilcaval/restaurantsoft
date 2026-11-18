/** Contratos de los módulos internos: importes en PEN y fechas ISO proporcionadas por el servidor. */
export interface MethodTotal { method:string; count:number; amount:number; }
export interface CashShift { id:string; openedAt:string; closedAt:string|null; openedBy:string; closedBy?:string|null; openingAmount:number; countedAmount:number|null; expectedAmount:number|null; openingNote?:string; closingNote?:string; }
export interface CashMovement { id:string; kind:'IN'|'OUT'; amount:number; reason:string; createdAt:string; registeredBy:string; }
export interface CashDetail { shift:CashShift; cashPayments:number; digitalPayments:number; cashIn:number; cashOut:number; expectedCash:number; difference:number|null; methods:MethodTotal[]; movements:CashMovement[]; tips:number; }
export interface CashOverview { active:CashDetail|null; history:CashShift[]; unassignedToday:{count:number;amount:number}; timezone:string; }
export interface SalesReport {
  from:string; to:string; timezone:string;
  totals:{collected:number;payments:number;sessions:number;unassigned:number;tips:number};
  voids:{count:number;amount:number}; discounts:{count:number;amount:number}; inventoryCost:number;
  zones:{name:string;color:string;amount:number;sessions:number}[];
  methods:MethodTotal[]; days:{day:string;amount:number}[]; products:{name:string;quantity:number;amount:number}[];
  payments:{id:string;paidAt:string;tableLabel:string;method:string;amount:number;tip:number;reference:string|null;registeredBy:string;shiftId:string|null}[];
  paymentCount:number; page:number; pageSize:number; hours?:{hour:number;amount:number;payments:number}[];
}
export const paymentMethodLabel=(method:string):string=>({CASH:'Efectivo',CARD:'Tarjeta',YAPE:'Yape',PLIN:'Plin'}[method]||method);
export function businessDate():string { return new Intl.DateTimeFormat('en-CA',{timeZone:'America/Lima',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()); }
export function localTimestamp(value:string,zone:string):string { return new Intl.DateTimeFormat('es-PE',{timeZone:zone,day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'}).format(new Date(value)); }
