import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, HostListener, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { ApiService } from '../../../core/api/api.service';
import { ImageUploadService } from '../../../core/api/image-upload.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { IconComponent } from '../../../shared/ui/icon.component';
import { ToastService } from '../../../shared/ui/toast.service';

type DefaultTheme = 'LIGHT' | 'DARK' | 'SYSTEM';
interface Settings { name: string; logoUrl: string | null; primaryColor: string; defaultTheme: DefaultTheme; }
interface Brand { name: string; logoUrl: string; color: string; theme: DefaultTheme; menu: string; }

/** Identidad del restaurante: logotipo, nombre, color, tema del equipo y estilo de la carta, con vista previa en vivo. */
@Component({
  selector: 'app-brand-dialog',
  imports: [FormsModule, IconComponent, NgTemplateOutlet],
  templateUrl: './brand-dialog.component.html',
  styleUrls: ['./brand-dialog.scss', './brand-editor.scss', './brand-cards.scss', './brand-preview.scss', './brand-app.scss'],
})
export class BrandDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly uploads = inject(ImageUploadService);
  private readonly theme = inject(ThemeService);
  private readonly toast = inject(ToastService);
  /** Integrado en Configuración (sin ventana encima) o como diálogo. */
  readonly inline = input(false);
  readonly closed = output<void>();

  readonly brand = signal<Brand>({ name: '', logoUrl: '', color: '#44d62c', theme: 'SYSTEM', menu: 'CLASSIC' });
  private readonly original = signal<Brand | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly uploading = signal(false);
  readonly dragging = signal(false);
  readonly nudge = signal(false);
  readonly previewTab = signal<'menu' | 'team'>('menu');

  readonly dirty = computed(() => !!this.original() && JSON.stringify(this.original()) !== JSON.stringify(this.brand()));
  /** Texto legible sobre el color elegido, igual que en toda la app. */
  readonly ink = computed(() => inkFor(this.brand().color));
  readonly previewDark = computed(() => this.brand().theme === 'DARK' || (this.brand().theme === 'SYSTEM' && matchMedia('(prefers-color-scheme: dark)').matches));

  readonly swatches = ['#e4572e', '#d62828', '#f59e0b', '#44d62c', '#0f9d8a', '#2563eb', '#7c3aed', '#1f2937'];
  readonly themes: { value: DefaultTheme; label: string }[] = [{ value: 'LIGHT', label: 'Claro' }, { value: 'DARK', label: 'Oscuro' }, { value: 'SYSTEM', label: 'Automático' }];
  readonly menuThemes = [
    { value: 'CLASSIC', label: 'Clásico', hint: 'Limpio y versátil' },
    { value: 'MODERN', label: 'Moderno oscuro', hint: 'Bar, cocteles, noche' },
    { value: 'FRESH', label: 'Cevichería fresca', hint: 'Blanco y aguamarina' },
    { value: 'WARM', label: 'Cafetería cálida', hint: 'Cremas y serifas' },
  ];
  readonly sampleDishes = [{ name: 'Ceviche clásico', note: 'Pescado del día, leche de tigre', price: 'S/ 38' }, { name: 'Lomo saltado', note: 'Con papas nativas y arroz', price: 'S/ 42' }];

  ngOnInit(): void {
    forkJoin({
      settings: this.api.get<Settings>('/admin/settings'),
      menu: this.api.get<{ theme: string }>('/admin/settings/menu-theme'),
    }).subscribe({
      next: ({ settings, menu }) => {
        const value: Brand = { name: settings.name, logoUrl: settings.logoUrl || '', color: (settings.primaryColor || "#44d62c").toLowerCase(), theme: settings.defaultTheme, menu: menu.theme || 'CLASSIC' };
        this.brand.set(value); this.original.set({ ...value }); this.loading.set(false);
      },
      error: () => { this.loading.set(false); this.toast.show('No se pudo cargar tu marca', 'Revisa la conexión e inténtalo otra vez.', 'error', '!'); },
    });
  }

  patch(change: Partial<Brand>): void { this.brand.update((value) => ({ ...value, ...change })); }
  setHex(value: string): void {
    const hex = value.trim().startsWith('#') ? value.trim() : '#' + value.trim();
    if (/^#[0-9a-f]{6}$/i.test(hex)) this.patch({ color: hex.toLowerCase() });
  }
  initial(): string { return (this.brand().name || 'R').trim().slice(0, 1).toUpperCase(); }

  pickLogo(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0]; input.value = '';
    if (file) void this.uploadLogo(file);
  }
  dropLogo(event: DragEvent): void {
    event.preventDefault(); this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file && /^image\/(png|jpeg|webp)$/.test(file.type)) void this.uploadLogo(file);
    else if (file) this.toast.show('Formato no compatible', 'Usa una imagen PNG, JPG o WEBP.', 'warning', '!');
  }
  private async uploadLogo(file: File): Promise<void> {
    this.uploading.set(true);
    try { this.patch({ logoUrl: await this.uploads.upload(file) }); }
    catch (err: any) { this.toast.show('No se pudo subir el logotipo', err?.error?.message || err?.message || 'Inténtalo con otra imagen.', 'error', '!'); }
    finally { this.uploading.set(false); }
  }

  /** Cierra sin guardar; si hay cambios pendientes, avisa en lugar de perderlos por un clic fuera. */
  requestClose(fromBackdrop = false): void {
    if (fromBackdrop && this.dirty()) { this.nudge.set(true); window.setTimeout(() => this.nudge.set(false), 700); return; }
    this.closed.emit();
  }
  @HostListener('document:keydown.escape') onEscape(): void { if (!this.inline()) this.requestClose(true); }
  discard(): void { const original = this.original(); if (original) this.brand.set({ ...original }); }

  save(): void {
    const value = this.brand(), original = this.original();
    if (!this.dirty() || this.saving() || !value.name.trim() || !original) return;
    this.saving.set(true);
    const identity = value.name !== original.name || value.logoUrl !== original.logoUrl || value.color !== original.color || value.theme !== original.theme;
    forkJoin([
      identity ? this.api.patch<Settings>('/admin/settings', { name: value.name.trim(), primaryColor: value.color, defaultTheme: value.theme, logoUrl: value.logoUrl || null }) : of(null),
      value.menu !== original.menu ? this.api.put<{ theme: string }>('/admin/settings/menu-theme', { theme: value.menu }) : of(null),
    ]).subscribe({
      next: () => {
        this.saving.set(false); this.original.set({ ...value });
        this.theme.applyBrand(value.color, value.theme);
        try { sessionStorage.setItem('restos_brand', JSON.stringify({ name: value.name.trim(), logoUrl: value.logoUrl || null })); } catch { /* sin almacenamiento */ }
        this.toast.show('Marca actualizada', 'El equipo, la carta QR y las reservas ya muestran los cambios.', 'success', '✓');
        if (!this.inline()) this.closed.emit();
      },
      error: (err) => { this.saving.set(false); this.toast.show('No se pudo guardar', err.error?.message || 'Inténtalo otra vez.', 'error', '!'); },
    });
  }
}

function inkFor(hex: string): string {
  const value = /^#?([0-9a-f]{6})$/i.exec(hex.trim())?.[1];
  if (!value) return '#ffffff';
  const [r, g, b] = [0, 2, 4].map((i) => { const c = parseInt(value.slice(i, i + 2), 16) / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.36 ? '#04110a' : '#ffffff';
}
