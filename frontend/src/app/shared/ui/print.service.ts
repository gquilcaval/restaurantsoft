import { inject, Injectable } from '@angular/core';
import { ToastService } from './toast.service';

export type PrintDocument = 'COMANDA' | 'PRECUENTA' | 'COMPROBANTE';

const LABELS: Record<PrintDocument, string> = {
  COMANDA: 'comanda',
  PRECUENTA: 'precuenta',
  COMPROBANTE: 'comprobante de pago'
};

/**
 * Punto único de impresión. Por ahora solo simula el envío a la impresora con un aviso;
 * al integrar una impresora térmica bastará con reemplazar este servicio.
 */
@Injectable({ providedIn: 'root' })
export class PrintService {
  private readonly toast = inject(ToastService);

  print(document: PrintDocument, detail: string): void {
    this.toast.show(`Imprimiendo ${LABELS[document]}…`, detail, 'info', '⎙', 3500);
  }
}
