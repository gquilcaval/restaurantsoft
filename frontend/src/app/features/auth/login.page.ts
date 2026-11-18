import { Component, HostListener, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService, LoginResponse } from '../../core/auth/auth.service';
import { ThemeService } from '../../core/theme/theme.service';
import { ThemeToggleComponent } from '../../shared/ui/theme-toggle.component';
import { ApiService } from '../../core/api/api.service';

interface StaffMember { id: string; fullName: string; role: string; }
interface DeviceStaff { deviceName: string; branchName: string; staff: StaffMember[]; }

const DEVICE_TOKEN_KEY = 'restos_device_token';

/** Acceso del personal: correo y contraseña, o nombre y PIN en una tablet autorizada del local. */
@Component({
  imports: [ThemeToggleComponent, FormsModule, RouterLink],
  styleUrl: './login.page.scss',
  template: `
    <main class="auth-page">
      <button class="theme-toggle" appThemeToggle></button>
      @if (device() && !passwordMode()) {
        <section class="auth-card pin-card">
          <div class="logo">R</div><p class="eyebrow">{{ device()!.branchName }} · {{ device()!.deviceName }}</p>
          @if (!selected()) {
            <h1>¿Quién ingresa?</h1><p class="muted">Toca tu nombre y escribe tu PIN.</p>
            <div class="pin-staff">
              @for (member of device()!.staff; track member.id) {
                <button type="button" (click)="choose(member)"><b>{{ initials(member.fullName) }}</b><span><strong>{{ member.fullName }}</strong><small>{{ roleLabel(member.role) }}</small></span></button>
              } @empty { <p class="muted">Nadie tiene PIN todavía. Un encargado puede asignarlos en Configuración.</p> }
            </div>
          } @else {
            <button type="button" class="link-button pin-back" (click)="selected.set(null)">← Cambiar de persona</button>
            <h1>Hola, {{ selected()!.fullName.split(' ')[0] }}</h1><p class="muted">Escribe tu PIN de {{ pinLength }} a 6 dígitos.</p>
            <div class="pin-dots" [class.error]="!!error()" aria-live="polite" [attr.aria-label]="pin.length + ' dígitos ingresados'">@for (slot of [0,1,2,3,4,5]; track slot) {<i [class.filled]="slot < pin.length" [class.optional]="slot >= pinLength"></i>}</div>
            @if (error()) { <p class="form-error">{{ error() }}</p> }
            <div class="pin-pad">
              @for (digit of digits; track digit) { <button type="button" (click)="press(digit)" [disabled]="loading()">{{ digit }}</button> }
              <button type="button" class="pin-muted" (click)="pin = ''" [disabled]="loading()" aria-label="Borrar todo">C</button>
              <button type="button" (click)="press('0')" [disabled]="loading()">0</button>
              <button type="button" class="pin-muted" (click)="backspace()" [disabled]="loading()" aria-label="Borrar último dígito">⌫</button>
            </div>
            <button class="primary full" [disabled]="pin.length < pinLength || loading()" (click)="submitPin()">{{ loading() ? 'Ingresando…' : 'Ingresar' }}</button>
          }
          @if (!selected()) { <a class="pin-clock" routerLink="/asistencia"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>Marcar asistencia</a> }
          <button type="button" class="link-button pin-switch" (click)="passwordMode.set(true)">Ingresar con correo y contraseña</button>
        </section>
      } @else {
        <section class="auth-card">
          <div class="logo">R</div><p class="eyebrow">Plataforma para restaurantes</p>
          <h1>Bienvenido a RestOS</h1><p class="muted">Administra mesas, cocina y servicio desde un solo lugar.</p>
          <form (ngSubmit)="submit()">
            <label>Correo electrónico<input type="email" name="email" [(ngModel)]="email" required></label>
            <label>Contraseña<input type="password" name="password" [(ngModel)]="password" required></label>
            @if (error()) { <p class="form-error">{{ error() }}</p> }
            <button class="primary full" [disabled]="loading()">{{ loading() ? 'Ingresando…' : 'Ingresar' }}</button>
          </form>
          @if (device()) { <button type="button" class="link-button pin-switch" (click)="passwordMode.set(false); error.set('')">← Volver al ingreso con PIN</button> }
          @if (demoMode()) { <div class="demo-access"><strong>Accesos de demostración</strong><span>admin@restos.local · RestOS123!</span></div> }
          <a routerLink="/" class="diner-link">¿Aún no usas RestOS? Prueba la demo →</a>
        </section>
      }
    </main>
  `
})
export class LoginPage implements OnInit {
  readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  readonly digits = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
  readonly pinLength = 4;
  email = '';
  password = '';
  pin = '';
  readonly demoMode = signal(false);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly device = signal<DeviceStaff | null>(null);
  readonly selected = signal<StaffMember | null>(null);
  readonly passwordMode = signal(false);

  ngOnInit(): void {
    this.api.get<{demoMode:boolean}>('/public/config').subscribe(config => {
      this.demoMode.set(config.demoMode);
      if (config.demoMode) { this.email = 'admin@restos.local'; this.password = 'RestOS123!'; }
    });
    const token = this.deviceToken();
    if (token) this.api.post<DeviceStaff>('/auth/device/staff', { deviceToken: token }).subscribe({
      next: value => this.device.set(value),
      error: err => {
        // Un equipo desautorizado vuelve al ingreso normal sin quedar bloqueado.
        if (err.status === 401) { try { localStorage.removeItem(DEVICE_TOKEN_KEY); } catch { /* sin almacenamiento */ } }
        this.error.set(err.error?.message || '');
      }
    });
  }

  submit(): void {
    this.loading.set(true); this.error.set('');
    this.auth.login(this.email, this.password).subscribe({
      next: response => this.enter(response),
      error: err => { this.loading.set(false); this.error.set(err.status === 0 ? 'No hay conexión con el servidor. Revisa tu red.' : err.status === 403 ? (err.error?.message || 'El servidor no acepta conexiones desde esta dirección. Avisa al administrador.') : err.status === 401 ? 'Correo o contraseña incorrectos.' : (err.error?.message || 'No pudimos validar tus credenciales.')); }
    });
  }

  choose(member: StaffMember): void { this.selected.set(member); this.pin = ''; this.error.set(''); }
  press(digit: string): void { if (this.pin.length < 6) { this.pin += digit; this.error.set(''); } }
  backspace(): void { this.pin = this.pin.slice(0, -1); }

  /** Permite usar el teclado físico en tablets con teclado o en la PC de caja. */
  @HostListener('document:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    if (!this.selected() || this.passwordMode() || this.loading() || event.ctrlKey || event.metaKey || event.altKey) return;
    // Si el foco está en un campo de texto, la tecla es para ese campo y no para el PIN.
    if ((event.target as HTMLElement | null)?.closest('input, textarea, select')) return;
    // event.key también entrega dígitos del teclado numérico lateral (Numpad).
    if (/^\d$/.test(event.key)) { event.preventDefault(); this.press(event.key); }
    else if (event.key === 'Backspace') { event.preventDefault(); this.backspace(); }
    else if (event.key === 'Escape') { event.preventDefault(); this.pin = ''; }
    else if (event.key === 'Enter' && this.pin.length >= this.pinLength) { event.preventDefault(); this.submitPin(); }
  }

  submitPin(): void {
    const member = this.selected(); const token = this.deviceToken();
    if (!member || !token || this.loading()) return;
    this.loading.set(true); this.error.set('');
    this.auth.pinLogin(token, member.id, this.pin).subscribe({
      next: response => this.enter(response),
      error: err => { this.loading.set(false); this.pin = ''; this.error.set(err.error?.message || 'PIN incorrecto'); }
    });
  }

  initials(name: string): string { return name.split(' ').map(part => part[0]).join('').slice(0, 2).toUpperCase(); }
  roleLabel(role: string): string { return ({ WAITER: 'Mesero', KITCHEN: 'Cocina', BRANCH_MANAGER: 'Encargado' } as Record<string, string>)[role] || role; }

  private enter(response: LoginResponse): void {
    this.router.navigateByUrl(({KITCHEN:'/kitchen',WAITER:'/waiter',PLATFORM_ADMIN:'/platform'} as Record<string,string>)[response.role] || '/admin');
  }

  private deviceToken(): string | null {
    try { return localStorage.getItem(DEVICE_TOKEN_KEY); } catch { return null; }
  }
}
