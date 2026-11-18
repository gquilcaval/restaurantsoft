import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/api/api.service';
import { DialogService } from '../../../shared/ui/dialog.service';
import { IconComponent } from '../../../shared/ui/icon.component';
import { ToastService } from '../../../shared/ui/toast.service';

interface Printer {
  id: string; name: string; host: string; port: number; paperWidth: number; active: boolean;
  lastOkAt: string | null; lastError: string | null; lastErrorAt: string | null;
}
interface Route { stationId: string | null; stationName: string; printerId: string | null; }
interface Agent { id: string; name: string; lastSeenAt: string | null; version: string | null; pairingCode: string | null; pairingExpiresAt: string | null; online: boolean; }
interface Overview { printers: Printer[]; stations: Route[]; agents: Agent[]; agentOnline: boolean; }

/**
 * Comandas impresas: la PC que hace de puente con las ticketeras, las ticketeras de la red
 * y qué estación imprime en cuál (las que no imprimen siguen con la pantalla de cocina).
 */
@Component({
  selector: 'app-printing-settings',
  imports: [FormsModule, IconComponent, NgTemplateOutlet],
  templateUrl: './printing-settings.component.html',
  styleUrls: ['./printing-settings.component.scss', './printing-settings-parts.scss'],
})
export class PrintingSettingsComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);

  readonly data = signal<Overview | null>(null);
  readonly failed = signal(false);
  readonly serverUrl = signal(location.origin);
  /** Formulario de impresora: null cerrado, 'new' o el id que se edita. */
  readonly editing = signal<string | null>(null);
  readonly saving = signal(false);
  formName = ''; formHost = ''; formPort = 9100; formWidth = 80;
  private timer = 0;

  readonly pairing = computed(() => this.data()?.agents.find((agent) => !!agent.pairingCode) || null);
  readonly linked = computed(() => (this.data()?.agents || []).filter((agent) => !agent.pairingCode));
  readonly printsSomething = computed(() => (this.data()?.stations || []).some((route) => !!route.printerId));

  ngOnInit(): void {
    this.load();
    this.api.get<{ baseUrl: string }>('/public/config').subscribe({ next: (value) => this.serverUrl.set((value.baseUrl || location.origin).replace(/\/$/, '')) });
    // Mientras se mira, se actualiza el estado de la PC y de las impresoras.
    this.timer = window.setInterval(() => this.load(), 5000);
  }
  ngOnDestroy(): void { window.clearInterval(this.timer); }

  load(): void {
    this.api.get<Overview>('/admin/printing').subscribe({
      next: (value) => { this.data.set(value); this.failed.set(false); },
      error: () => this.failed.set(true),
    });
  }

  // ——— PC de impresión ———
  linkPc(): void {
    this.api.post<Agent>('/admin/printing/agents', { name: 'PC del local' }).subscribe({
      next: () => this.load(),
      error: (err) => this.fail(err),
    });
  }
  async unlink(agent: Agent): Promise<void> {
    const accepted = await this.dialog.confirm({
      title: agent.pairingCode ? 'Cancelar vinculación' : 'Desvincular PC',
      message: agent.pairingCode ? 'El código dejará de funcionar.' : `${agent.name} dejará de imprimir comandas hasta que la vuelvas a vincular.`,
      confirmLabel: agent.pairingCode ? 'Cancelar código' : 'Desvincular', tone: 'danger', icon: '!',
    });
    if (!accepted) return;
    this.api.delete(`/admin/printing/agents/${agent.id}`).subscribe({ next: () => this.load(), error: (err) => this.fail(err) });
  }
  pairCommand(code: string): string { return `java RestosPrintAgent.java vincular ${this.serverUrl()} ${code}`; }
  async copy(text: string): Promise<void> {
    try { await navigator.clipboard.writeText(text); this.toast.show('Copiado', 'Pégalo en la ventana de comandos de la PC.', 'success', '✓', 2500); }
    catch { this.toast.show('No se pudo copiar', 'Escríbelo a mano en la PC.', 'warning', '!'); }
  }
  formatCode(code: string): string { return `${code.slice(0, 3)} ${code.slice(3)}`; }

  // ——— Impresoras ———
  newPrinter(): void { this.formName = ''; this.formHost = ''; this.formPort = 9100; this.formWidth = 80; this.editing.set('new'); }
  editPrinter(printer: Printer): void {
    this.formName = printer.name; this.formHost = printer.host; this.formPort = printer.port; this.formWidth = printer.paperWidth;
    this.editing.set(printer.id);
  }
  savePrinter(): void {
    const id = this.editing();
    if (!id || this.saving()) return;
    const body = { name: this.formName.trim(), host: this.formHost.trim(), port: Number(this.formPort) || 9100, paperWidth: this.formWidth, active: true };
    this.saving.set(true);
    const request = id === 'new' ? this.api.post<Printer>('/admin/printing/printers', body) : this.api.put<Printer>(`/admin/printing/printers/${id}`, body);
    request.subscribe({
      next: (printer) => {
        this.saving.set(false); this.editing.set(null); this.load();
        this.toast.show('Listo', `${printer.name} guardada. Imprime una prueba para comprobarla.`, 'success', '✓');
      },
      error: (err) => { this.saving.set(false); this.fail(err); },
    });
  }
  test(printer: Printer): void {
    this.api.post(`/admin/printing/printers/${printer.id}/test`, {}).subscribe({
      next: () => {
        const online = this.data()?.agentOnline;
        this.toast.show(online ? 'Prueba enviada' : 'Prueba en espera', online ? `Revisa la impresora ${printer.name}.` : 'Saldrá cuando la PC de impresión esté conectada.', online ? 'success' : 'warning', '⎙');
        window.setTimeout(() => this.load(), 3000);
      },
      error: (err) => this.fail(err),
    });
  }
  async removePrinter(printer: Printer): Promise<void> {
    const accepted = await this.dialog.confirm({
      title: `Quitar ${printer.name}`, message: 'Las estaciones que imprimían aquí volverán a la pantalla de cocina.',
      confirmLabel: 'Quitar', tone: 'danger', icon: '!',
    });
    if (!accepted) return;
    this.api.delete(`/admin/printing/printers/${printer.id}`).subscribe({ next: () => { this.editing.set(null); this.load(); }, error: (err) => this.fail(err) });
  }
  printerState(printer: Printer): { text: string; tone: 'ok' | 'bad' | 'muted' } {
    const ok = printer.lastOkAt ? Date.parse(printer.lastOkAt) : 0;
    const bad = printer.lastErrorAt ? Date.parse(printer.lastErrorAt) : 0;
    if (bad > ok) return { text: printer.lastError || 'No responde', tone: 'bad' };
    if (ok) return { text: 'Funciona', tone: 'ok' };
    return { text: 'Sin probar', tone: 'muted' };
  }

  // ——— Qué imprime cada estación ———
  setRoute(route: Route, printerId: string | null): void {
    if (route.printerId === printerId) return;
    this.api.put('/admin/printing/routes', { stationId: route.stationId, printerId }).subscribe({
      next: () => {
        this.load();
        const printer = this.data()?.printers.find((item) => item.id === printerId);
        this.toast.show('Listo', printerId ? `${route.stationName} imprimirá comandas en ${printer?.name}.` : `${route.stationName} usará la pantalla de cocina.`, 'success', '✓');
      },
      error: (err) => this.fail(err),
    });
  }

  ago(iso: string | null): string {
    if (!iso) return 'nunca';
    const seconds = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
    if (seconds < 60) return `hace ${seconds} s`;
    if (seconds < 3600) return `hace ${Math.round(seconds / 60)} min`;
    return `hace ${Math.round(seconds / 3600)} h`;
  }
  private fail(err: { error?: { message?: string } }): void {
    this.toast.show('No se pudo completar', err.error?.message || 'Revisa tu conexión.', 'error', '!');
  }
}
