import { CurrencyPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import QRCode from 'qrcode';
import { ApiService } from '../../core/api/api.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { ToastService } from '../../shared/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';

interface SetupStatus {
  categories: number; products: number; productsWithPhoto: number; tables: number; staff: number; staffWithPin: number;
  devices: number; hasLogo: boolean; ingredients: number; recipes: number; cashShifts: number;
}
interface PreviewRow { line: number; category: string; name: string; description: string; price: number | null; station: string; action: 'CREATE' | 'UPDATE' | 'ERROR'; error: string | null; }
interface ImportResult { rows: PreviewRow[]; toCreate: number; toUpdate: number; errors: number; newCategories: number; applied: boolean; }
interface AdminTable { id: string; label: string; capacity: number; qrPublicId: string; active: boolean; }
interface Step { key: string; title: string; detail: string; done: boolean; link?: string; action?: string; optional?: boolean; icon: string; why: string; cta?: string; section?: string; }

/** Asistente para dejar listo un restaurante nuevo: carta desde Excel, mesas y QR imprimibles. */
@Component({
  imports: [AppShellComponent, FormsModule, CurrencyPipe, RouterLink, IconComponent],
  templateUrl: './setup.page.html',
  styleUrls: ['../../shared/ui/data-table.scss', './setup.page.scss', './setup-wizard.scss', './setup-tools.scss']
})
export class SetupPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  readonly status = signal<SetupStatus | null>(null);
  readonly preview = signal<ImportResult | null>(null);
  readonly tables = signal<AdminTable[]>([]);
  readonly selectedTables = signal<Set<string>>(new Set());
  readonly busy = signal(false);
  readonly fileName = signal('');
  private file: File | null = null;
  tablePrefix = 'Mesa';
  tableFrom = 1;
  tableCount = 10;
  tableCapacity = 4;
  tableZoneId = '';
  readonly zones = signal<{ id: string; name: string; active: boolean }[]>([]);
  qrPerPage = 6;
  qrMessage = 'Escanea para ver la carta y pedir desde tu mesa';
  private brand = { name: 'RestOS', logoUrl: null as string | null };
  private baseUrl = location.origin;

  readonly steps = computed<Step[]>(() => {
    const s = this.status();
    if (!s) return [];
    return [
      { key: 'logo', icon: 'sparkles', title: 'Logotipo y colores', detail: s.hasLogo ? 'Identidad cargada' : 'Sube tu logotipo para la carta digital', done: s.hasLogo, link: '/configuration', section: 'brand', cta: 'Abrir marca',
        why: 'Tu logo y tu color aparecen en la carta QR, en las tarjetas impresas y en las reservas online. Se editan en Configuración → Marca y carta.' },
      { key: 'menu', icon: 'utensils', title: 'Carta', detail: `${s.products} productos en ${s.categories} categorías`, done: s.products > 0, action: 'menu', why: '' },
      { key: 'photos', icon: 'map', title: 'Fotos de los platos', detail: `${s.productsWithPhoto} de ${s.products} con foto`, done: s.products > 0 && s.productsWithPhoto >= s.products / 2, link: '/menu', optional: true, cta: 'Ir a Menú',
        why: 'Los platos con foto se piden más en la carta QR. Sube una foto por plato desde Menú: se optimizan solas.' },
      { key: 'tables', icon: 'chair', title: 'Mesas', detail: `${s.tables} mesas activas`, done: s.tables > 0, action: 'tables', why: '' },
      { key: 'floor', icon: 'grid', title: 'Plano del salón', detail: 'Ubica tus mesas como en tu local', done: false, link: '/floor', optional: true, cta: 'Ir a Plano del salón',
        why: 'Con el plano, los meseros ven el salón tal cual y pueden mover o unir mesas arrastrándolas. Hay plantillas para empezar en minutos.' },
      { key: 'qr', icon: 'receipt', title: 'Códigos QR impresos', detail: 'Genera el PDF y colócalos en cada mesa', done: false, action: 'qr', why: '' },
      { key: 'staff', icon: 'users', title: 'Personal', detail: `${s.staff} usuarios del equipo`, done: s.staff > 0, link: '/personal', cta: 'Ir a Personal',
        why: 'Registra a cada persona y dale acceso a quien use el sistema (mesero, cocina o encargado). Así cada pedido y cada cobro queda con su nombre y ves el ranking del equipo.' },
      { key: 'pins', icon: 'phone', title: 'PIN y tablets', detail: `${s.staffWithPin} con PIN · ${s.devices} tablets autorizadas`, done: s.staffWithPin > 0 && s.devices > 0, link: '/configuration', section: 'devices', optional: true, cta: 'Ir a Configuración → Tablets',
        why: 'En una tablet del salón, cada persona entra tocando su nombre y su PIN de 4 dígitos, sin escribir correo ni contraseña.' },
      { key: 'inventory', icon: 'package', title: 'Inventario y recetas', detail: `${s.ingredients} insumos · ${s.recipes} platos con receta`, done: s.recipes > 0, link: '/inventory', optional: true, cta: 'Ir a Inventario',
        why: 'Registra tus insumos y arma la receta de cada plato: el stock se descuenta solo con cada venta y verás qué se acaba hoy y tu margen real.' },
      { key: 'cash', icon: 'money', title: 'Primera apertura de caja', detail: s.cashShifts ? 'Caja usada' : 'Abre la caja el día que empieces a atender', done: s.cashShifts > 0, link: '/cash', cta: 'Ir a Caja',
        why: 'El primer día cuenta el fondo inicial y abre la caja. Desde ahí, cada cobro en efectivo se suma y al cierre sabrás si cuadra.' },
    ];
  });
  readonly progress = computed(() => {
    const required = this.steps().filter(step => !step.optional && step.action !== 'qr');
    return required.length ? Math.round(required.filter(step => step.done).length / required.length * 100) : 0;
  });

  /** Paso abierto en el asistente: el primero pendiente al entrar. */
  readonly active = signal<string>('');
  readonly current = computed(() => this.steps().find(step => step.key === this.active()) || this.steps()[0]);
  readonly doneCount = computed(() => this.steps().filter(step => step.done).length);
  readonly qrSample = signal('');
  private readonly autoOpen = effect(() => { const steps = this.steps(); if (steps.length && !this.active()) this.active.set((steps.find(step => !step.done && !step.optional) || steps[0]).key); });
  private readonly samplePreview = effect(() => {
    const first = this.tables().find(table => this.selectedTables().has(table.id));
    if (!first) { this.qrSample.set(''); return; }
    QRCode.toDataURL(`${this.baseUrl}/diner?qr=${first.qrPublicId}`, { width: 280, margin: 1 }).then(url => this.qrSample.set(url));
  });
  sampleLabel(): string { return this.tables().find(table => this.selectedTables().has(table.id))?.label || 'Mesa 01'; }
  brandName(): string { return this.brand.name; }
  brandLogo(): string | null { return this.brand.logoUrl; }
  open(key: string): void { this.active.set(key); }
  next(): void { const list = this.steps(); const index = list.findIndex(step => step.key === this.active()); this.active.set(list[Math.min(list.length - 1, index + 1)].key); }

  ngOnInit(): void {
    this.loadStatus();
    this.loadTables();
    this.api.get<{ id: string; name: string; active: boolean }[]>('/admin/zones').subscribe(value => this.zones.set(value.filter(zone => zone.active)));
    this.api.get<{ baseUrl: string }>('/public/config').subscribe(config => this.baseUrl = config.baseUrl.replace(/\/$/, ''));
    this.api.get<{ name: string; logoUrl: string | null }>('/staff/branding').subscribe(brand => this.brand = brand);
  }

  loadStatus(): void { this.api.get<SetupStatus>('/admin/setup/status').subscribe(value => this.status.set(value)); }

  loadTables(): void {
    this.api.get<AdminTable[]>('/admin/operations/tables').subscribe(value => {
      const active = value.filter(table => table.active);
      this.tables.set(active);
      this.selectedTables.set(new Set(active.map(table => table.id)));
      this.tableFrom = active.length + 1;
    });
  }

  scrollTo(section: string | undefined): void { if (section) this.active.set(section); }

  downloadTemplate(): void {
    this.http.get('/api/admin/setup/menu-template', { responseType: 'blob' }).subscribe({
      next: blob => this.save(blob, 'plantilla-carta-restos.xlsx'),
      error: () => this.toast.show('No se pudo descargar', 'Inténtalo nuevamente.', 'error', '!')
    });
  }

  chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file = input.files?.[0] || null;
    input.value = '';
    this.preview.set(null);
    this.fileName.set(this.file?.name || '');
    if (this.file) this.upload(false);
  }

  /** Primero se valida y se muestra la vista previa; solo al confirmar se guarda la carta. */
  upload(apply: boolean): void {
    if (!this.file || this.busy()) return;
    const body = new FormData();
    body.append('file', this.file);
    this.busy.set(true);
    this.http.post<ImportResult>(`/api/admin/setup/menu-import?apply=${apply}`, body).subscribe({
      next: result => {
        this.busy.set(false);
        this.preview.set(result);
        if (result.applied) {
          this.toast.show('Carta importada', `${result.toCreate} productos nuevos y ${result.toUpdate} actualizados.`, 'success', '✓');
          this.file = null; this.fileName.set(''); this.loadStatus();
        }
      },
      error: err => { this.busy.set(false); this.toast.show('No se pudo leer la carta', err.error?.message || 'Revisa el archivo.', 'error', '!'); }
    });
  }

  createTables(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.api.post<{ created: string[]; skipped: string[] }>('/admin/setup/tables', { prefix: this.tablePrefix, from: this.tableFrom, count: this.tableCount, capacity: this.tableCapacity, zoneId: this.tableZoneId || null }).subscribe({
      next: result => {
        this.busy.set(false);
        this.toast.show('Mesas creadas', `${result.created.length} creadas${result.skipped.length ? `, ${result.skipped.length} ya existían` : ''}.`, 'success', '✓');
        this.loadTables(); this.loadStatus();
      },
      error: err => { this.busy.set(false); this.toast.show('No se pudieron crear', err.error?.message || 'Revisa los datos.', 'error', '!'); }
    });
  }

  toggleTable(id: string): void {
    this.selectedTables.update(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  }
  selectAll(all: boolean): void { this.selectedTables.set(new Set(all ? this.tables().map(table => table.id) : [])); }

  /**
   * Arma una hoja imprimible con un QR por tarjeta. El navegador permite «Guardar como PDF» al imprimir,
   * así se obtiene el PDF sin depender de servicios externos.
   */
  async printQrs(): Promise<void> {
    const tables = this.tables().filter(table => this.selectedTables().has(table.id));
    if (!tables.length) return;
    const popup = window.open('', '_blank');
    if (!popup) { this.toast.show('Ventana bloqueada', 'Permite las ventanas emergentes para generar el PDF.', 'warning', '!'); return; }
    const codes = await Promise.all(tables.map(table => QRCode.toDataURL(`${this.baseUrl}/diner?qr=${table.qrPublicId}`, { width: 600, margin: 1, errorCorrectionLevel: 'M' })));
    const columns = this.qrPerPage === 4 ? 2 : this.qrPerPage === 1 ? 1 : 2;
    const rows = this.qrPerPage === 6 ? 3 : this.qrPerPage === 4 ? 2 : 1;
    const doc = popup.document;
    doc.title = `QR ${this.brand.name}`;
    const style = doc.createElement('style');
    style.textContent = `@page{size:A4;margin:10mm}*{box-sizing:border-box}body{margin:0;font-family:Inter,Arial,sans-serif;color:#111}
      .grid{display:grid;grid-template-columns:repeat(${columns},1fr);grid-auto-rows:calc((277mm - ${(rows - 1) * 6}mm)/${rows});gap:6mm}
      .card{border:1.5px dashed #bbb;border-radius:6mm;padding:6mm;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;break-inside:avoid}
      .card img.qr{width:${rows === 1 ? 130 : rows === 2 ? 75 : 52}mm;height:auto}.card img.logo{max-height:14mm;max-width:40mm;object-fit:contain;margin-bottom:2mm}
      .brand{font-weight:800;font-size:${rows === 1 ? 24 : 13}pt}.table{font-weight:800;font-size:${rows === 1 ? 40 : 20}pt;margin:2mm 0}
      .hint{font-size:${rows === 1 ? 16 : 9}pt;color:#444;max-width:85%}.page{page-break-after:always}.page:last-child{page-break-after:auto}`;
    doc.head.append(style);
    for (let start = 0; start < tables.length; start += this.qrPerPage) {
      const page = doc.createElement('div'); page.className = 'page';
      const grid = doc.createElement('div'); grid.className = 'grid';
      tables.slice(start, start + this.qrPerPage).forEach((table, index) => {
        const card = doc.createElement('div'); card.className = 'card';
        if (this.brand.logoUrl) { const logo = doc.createElement('img'); logo.className = 'logo'; logo.src = this.brand.logoUrl; card.append(logo); }
        const brand = doc.createElement('div'); brand.className = 'brand'; brand.textContent = this.brand.name;
        const label = doc.createElement('div'); label.className = 'table'; label.textContent = table.label;
        const qr = doc.createElement('img'); qr.className = 'qr'; qr.src = codes[start + index]; qr.alt = `QR ${table.label}`;
        const hint = doc.createElement('div'); hint.className = 'hint'; hint.textContent = this.qrMessage;
        card.append(brand, label, qr, hint); grid.append(card);
      });
      page.append(grid); doc.body.append(page);
    }
    const images = Array.from(doc.images);
    await Promise.all(images.map(image => image.complete ? Promise.resolve() : new Promise(resolve => { image.onload = resolve; image.onerror = resolve; })));
    popup.focus();
    popup.print();
  }

  actionLabel(action: string): string { return ({ CREATE: 'Nuevo', UPDATE: 'Actualiza', ERROR: 'Error' } as Record<string, string>)[action] || action; }

  private save(blob: Blob, name: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
