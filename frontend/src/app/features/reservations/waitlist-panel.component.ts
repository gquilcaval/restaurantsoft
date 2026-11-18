import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnDestroy, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { DialogService } from '../../shared/ui/dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { InfoTipComponent } from '../../shared/ui/info-tip.component';

interface WaitlistEntry {
  id: string;
  publicToken: string;
  customerName: string;
  phone: string | null;
  partySize: number;
  notes: string | null;
  status: 'WAITING' | 'NOTIFIED' | 'SEATED' | 'CANCELLED' | 'NO_SHOW';
  tableId: string | null;
  tableLabel: string | null;
  createdAt: string;
  notifiedAt: string | null;
  closedAt: string | null;
  position: number | null;
  estimatedMinutes: number | null;
}
interface FreeTable {
  id: string;
  label: string;
  capacity: number;
}
interface WaitlistBoard {
  entries: WaitlistEntry[];
  freeTables: FreeTable[];
  averageVisitMinutes: number;
}

/**
 * Lista de espera del anfitrión: registra al cliente, le envía su enlace de seguimiento por WhatsApp,
 * le avisa cuando su mesa está lista y lo sienta abriendo la mesa.
 */
@Component({
  selector: 'app-waitlist-panel',
  imports: [FormsModule, DatePipe, IconComponent, InfoTipComponent],
  templateUrl: './waitlist-panel.component.html',
  styleUrls: ['./waitlist-panel.component.scss', './waitlist-cards.scss'],
})
export class WaitlistPanelComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  readonly countChange = output<number>();
  readonly board = signal<WaitlistBoard | null>(null);
  readonly now = signal(Date.now());
  readonly seating = signal<WaitlistEntry | null>(null);
  readonly busy = signal(false);
  private baseUrl = location.origin;
  private timer?: number;
  private unwatch?: () => void;
  private stopResync?: () => void;
  name = '';
  phone = '';
  partySize = 2;
  notes = '';

  readonly waiting = computed(() =>
    (this.board()?.entries || []).filter(
      (entry) => entry.status === 'WAITING' || entry.status === 'NOTIFIED',
    ),
  );
  readonly history = computed(() =>
    (this.board()?.entries || []).filter(
      (entry) => entry.status !== 'WAITING' && entry.status !== 'NOTIFIED',
    ),
  );
  /** Sugerencias: la primera mesa libre donde cabe cada grupo, respetando el orden de llegada. */
  readonly suggestions = computed(() => {
    const free = [...(this.board()?.freeTables || [])];
    const result = new Map<string, FreeTable>();
    for (const entry of this.waiting()) {
      const index = free.findIndex((table) => table.capacity >= entry.partySize);
      if (index >= 0) {
        result.set(entry.id, free[index]);
        free.splice(index, 1);
      }
    }
    return result;
  });

  ngOnInit(): void {
    this.api
      .get<{ baseUrl: string }>('/public/config')
      .subscribe({
        next: (config) => (this.baseUrl = config.baseUrl.replace(/\/$/, '')),
        error: () => undefined,
      });
    this.load();
    this.timer = window.setInterval(() => this.now.set(Date.now()), 30000);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => this.load());
    const branch = this.auth.branchId();
    if (branch)
      this.unwatch = this.realtime.watch(`/topic/branches/${branch}/waiters`, (event) => {
        if (
          [
            'WAITLIST_UPDATED',
            'TABLE_SESSION_CLOSED',
            'TABLE_SESSION_OPENED',
            'TABLE_GUEST_JOINED',
          ].includes(event.type)
        )
          this.load();
      });
  }

  ngOnDestroy(): void { this.stopResync?.();
    this.unwatch?.();
    if (this.timer) window.clearInterval(this.timer);
  }

  load(): void {
    this.api.get<WaitlistBoard>('/staff/waitlist').subscribe({
      next: (value) => {
        this.board.set(value);
        this.countChange.emit(this.waiting().length);
      },
      error: () => undefined,
    });
  }

  add(): void {
    if (!this.name.trim() || this.busy()) return;
    this.busy.set(true);
    this.api
      .post<WaitlistEntry>('/staff/waitlist', {
        name: this.name.trim(),
        phone: this.phone.trim() || null,
        partySize: this.partySize,
        notes: this.notes.trim() || null,
      })
      .subscribe({
        next: (entry) => {
          this.busy.set(false);
          this.name = '';
          this.phone = '';
          this.notes = '';
          this.partySize = 2;
          this.toast.show(
            'Agregado a la lista',
            `${entry.customerName} ya puede seguir su turno desde el celular.`,
            'success',
            '⏳',
          );
          this.load();
          if (entry.phone) this.sendLink(entry);
        },
        error: (err) => {
          this.busy.set(false);
          this.toast.show(
            'No se pudo agregar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          );
        },
      });
  }

  link(entry: WaitlistEntry): string {
    return `${this.baseUrl}/waitlist?t=${entry.publicToken}`;
  }

  /** Envía por WhatsApp el enlace para seguir el turno. */
  sendLink(entry: WaitlistEntry): void {
    this.openWhatsapp(
      entry,
      `Hola ${entry.customerName} 👋 Ya estás en nuestra lista de espera para ${entry.partySize} ${entry.partySize === 1 ? 'persona' : 'personas'}.\nSigue tu turno aquí, te avisaremos cuando tu mesa esté lista:\n${this.link(entry)}`,
    );
  }

  copyLink(entry: WaitlistEntry): void {
    navigator.clipboard
      .writeText(this.link(entry))
      .then(() => this.toast.show('Enlace copiado', 'Compártelo con el cliente.', 'success', '🔗'))
      .catch(() => undefined);
  }

  /** «Tu mesa está lista»: actualiza su pantalla y, si hay número, abre WhatsApp con el aviso. */
  notifyReady(entry: WaitlistEntry): void {
    const table = this.suggestions().get(entry.id);
    this.api
      .post<WaitlistEntry>(`/staff/waitlist/${entry.id}/notify`, { tableId: table?.id || null })
      .subscribe({
        next: (updated) => {
          this.toast.show(
            'Cliente avisado',
            `La pantalla de ${updated.customerName} ya muestra que su mesa está lista.`,
            'success',
            '🔔',
          );
          this.load();
          if (updated.phone)
            this.openWhatsapp(
              updated,
              `¡${updated.customerName}, tu mesa está lista! 🎉${updated.tableLabel ? ' Te esperamos en ' + updated.tableLabel + '.' : ''} Acércate a la entrada, por favor.\n${this.link(updated)}`,
            );
        },
        error: (err) =>
          this.toast.show(
            'No se pudo avisar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          ),
      });
  }

  openSeat(entry: WaitlistEntry): void {
    this.seating.set(entry);
  }

  seat(entry: WaitlistEntry, table: FreeTable): void {
    this.api
      .post<WaitlistEntry>(`/staff/waitlist/${entry.id}/seat`, { tableId: table.id })
      .subscribe({
        next: () => {
          this.seating.set(null);
          this.toast.show(
            '¡A la mesa!',
            `${entry.customerName} se sentó en ${table.label}. La mesa ya está abierta.`,
            'success',
            '🪑',
          );
          this.load();
        },
        error: (err) =>
          this.toast.show(
            'No se pudo sentar',
            err.error?.message || 'Intenta nuevamente.',
            'error',
            '!',
          ),
      });
  }

  async close(entry: WaitlistEntry, noShow: boolean): Promise<void> {
    const accepted = await this.dialog.confirm({
      title: noShow ? 'No se presentó' : 'Quitar de la lista',
      message: noShow
        ? `${entry.customerName} saldrá de la lista como «no llegó».`
        : `${entry.customerName} saldrá de la lista de espera.`,
      confirmLabel: noShow ? 'Marcar no llegó' : 'Quitar',
      tone: 'warning',
      icon: noShow ? '⌛' : '×',
    });
    if (!accepted) return;
    this.api
      .post(`/staff/waitlist/${entry.id}/close`, { noShow })
      .subscribe({ next: () => this.load(), error: () => this.load() });
  }

  waitedMinutes(entry: WaitlistEntry): number {
    return Math.max(0, Math.floor((this.now() - new Date(entry.createdAt).getTime()) / 60000));
  }

  fitting(entry: WaitlistEntry): FreeTable[] {
    return (this.board()?.freeTables || []).filter((table) => table.capacity >= entry.partySize);
  }

  statusLabel(status: string): string {
    return (
      (
        { SEATED: 'Sentado', CANCELLED: 'Cancelado', NO_SHOW: 'No llegó' } as Record<string, string>
      )[status] || status
    );
  }

  private openWhatsapp(entry: WaitlistEntry, text: string): void {
    const digits = (entry.phone || '').replace(/\D/g, '');
    const phone = digits.length === 9 ? `51${digits}` : digits;
    if (!phone) return;
    window.open(`https://wa.me/${phone}?text=${encodeURIComponent(text)}`, '_blank', 'noopener');
  }
}
