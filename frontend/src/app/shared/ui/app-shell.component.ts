import { afterNextRender, Component, ElementRef, HostListener, inject, input, OnInit, output, signal, viewChild } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { StaffPermissionsService } from '../../core/auth/staff-permissions.service';
import { TeamBoardComponent } from '../team/team-board.component';
import { IconComponent } from './icon.component';
import { WaiterNavService } from './waiter-nav.service';
import { ThemeMode, ThemeService } from '../../core/theme/theme.service';
import { ApiService } from '../../core/api/api.service';
import { SoundService } from './sound.service';

/** Estructura visual reutilizable para paneles internos; el comensal usa su navegación móvil propia. */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, TeamBoardComponent, IconComponent],
  template: `
    <div class="shell" [class.has-dock]="auth.role() === 'WAITER'" [class.sidebar-collapsed]="sidebarCollapsed() && !mobileViewport()" [class.mobile-menu-open]="mobileOpen()">
      @if (mobileOpen()) { <div class="sidebar-scrim" (click)="closeMobileMenu()" aria-hidden="true"></div> }
      <aside #sidebar class="lg" id="staff-navigation" [attr.role]="mobileOpen() ? 'dialog' : null" [attr.aria-modal]="mobileOpen() ? 'true' : null" aria-label="Menú del restaurante" (keydown)="handleMenuKeys($event)">
        <div class="aside-branding">
          <a class="brand" [routerLink]="visibleNavigation()[0]?.path || '/login'" [attr.title]="brandName()" (click)="closeMobileMenu()">
            @if (logoUrl()) { <img [src]="logoUrl()" [alt]="brandName()"> } @else { <span>{{brandInitial()}}</span> }
            <div class="brand-copy"><strong>{{brandName()}}</strong><small>Tu restaurante, en orden</small></div>
          </a>
          <button class="mobile-menu-close" (click)="closeMobileMenu()" aria-label="Cerrar menú"><svg viewBox="0 0 24 24"><path d="m6 6 12 12M6 18 18 6"/></svg></button>
        </div>
        <nav #navScroll aria-label="Navegación principal" (scroll)="rememberNavScroll()">
          @for (group of navGroups(); track group.name) {
            @if (showGroupHeaders()) {
              <button type="button" class="nav-group" (click)="toggleGroup(group.name)" [attr.aria-expanded]="isGroupOpen(group)">
                <span>{{ group.name }}</span><svg viewBox="0 0 24 24" aria-hidden="true" [class.open]="isGroupOpen(group)"><path d="m9 6 6 6-6 6"/></svg>
              </button>
            }
            @if (!showGroupHeaders() || isGroupOpen(group)) {
            @for (item of group.items; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page" [routerLinkActiveOptions]="{exact:item.path==='/admin'}" [attr.aria-label]="item.label" [attr.title]="sidebarCollapsed() && !mobileViewport() ? item.label : null" (click)="closeMobileMenu()">
              <span class="nav-icon" aria-hidden="true">
                @switch (item.path) {
                  @case ('/admin') { <svg viewBox="0 0 24 24"><path d="M4 13h6V4H4v9Zm0 7h6v-4H4v4Zm10 0h6v-9h-6v9Zm0-16v4h6V4h-6Z"/></svg> }
                  @case ('/menu') { <svg viewBox="0 0 24 24"><path d="M12 5v15M3 4h5a4 4 0 0 1 4 2 4 4 0 0 1 4-2h5v15h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3Z"/></svg> }
                  @case ('/configuration') { <svg viewBox="0 0 24 24"><path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.86 2.86-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21H9.55v-.1A1.7 1.7 0 0 0 8.5 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.86-2.86.06-.06A1.7 1.7 0 0 0 4.1 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H2.3V9.55h.1A1.7 1.7 0 0 0 4.1 8.5a1.7 1.7 0 0 0-.34-1.88l-.06-.06L6.56 3.7l.06.06A1.7 1.7 0 0 0 8.5 4.1a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1v-.1h4.05v.1A1.7 1.7 0 0 0 15 4.1a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.86 2.86-.06.06A1.7 1.7 0 0 0 19.4 8.5c.14.4.35.75.65 1 .3.25.68.4 1.05.4h.1v4.05h-.1A1.7 1.7 0 0 0 19.4 15Z"/></svg> }
                  @case ('/live') { <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8M12 17v4M7 13l3-3 2 2 4-4"/></svg> }
                  @case ('/floor') { <svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="2.5"/><rect x="13" y="13" width="5" height="5" rx="1"/><path d="M13 6h5M6 15v3"/></svg> }
                  @case ('/orders') { <svg viewBox="0 0 24 24"><path d="M6 7h12l-1 13H7L6 7Z"/><path d="M9 7a3 3 0 0 1 6 0"/></svg> }
                  @case ('/setup') { <svg viewBox="0 0 24 24"><path d="M5 12l4 4L19 6"/><path d="M4 4h10M4 20h16"/></svg> }
                  @case ('/reservations') { <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4m8-4v4M4 10h16M9 15l2 2 4-4"/></svg> }
                  @case ('/inventory') { <svg viewBox="0 0 24 24"><path d="M4 8 12 4l8 4v8l-8 4-8-4V8Z"/><path d="m4 8 8 4 8-4M12 12v8"/></svg> }
                  @case ('/personal') { <svg viewBox="0 0 24 24"><rect x="4" y="3.5" width="16" height="17" rx="3"/><circle cx="12" cy="10" r="2.6"/><path d="M7.5 17a4.5 4.5 0 0 1 9 0"/></svg> }
                  @case ('/customers') { <svg viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14a5 5 0 0 1 3.5 6"/></svg> }
                  @case ('/audit') { <svg viewBox="0 0 24 24"><path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3Z"/><path d="m9 12 2 2 4-4"/></svg> }
                  @case ('/waiter') { <svg viewBox="0 0 24 24"><path d="M4 7h16v13H4zM7 4h10v3M8 11h3M14 11h2M8 15h8"/></svg> }
                  @case ('/kitchen') { <svg viewBox="0 0 24 24"><path d="M4 17a8 8 0 0 1 16 0M2 20h20M3 17h18M12 9V6M10 6h4"/></svg> }
                  @case ('/cash') { <svg viewBox="0 0 24 24"><path d="M4 10h16v10H4zM7 10V4h10v6M8 7h8M4 15h16M10 18h4"/></svg> }
                  @case ('/reports') { <svg viewBox="0 0 24 24"><path d="M4 4v16h16M8 16v-5m5 5V7m5 9v-3"/></svg> }
                  @default { <svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM8 9h8M8 13h8M8 17h5"/></svg> }
                }
              </span>
              <span class="nav-text">{{ item.label }}</span>
              <i class="nav-marker" aria-hidden="true"></i>
            </a>
            }
            }
          }
        </nav>
        <div class="aside-footer">
          <div class="sidebar-signature"><span>Rest<span>OS</span></span><small>Gestión del restaurante</small></div>
        </div>
      </aside>
      <main [attr.inert]="mobileOpen() ? '' : null">
        <header class="lg">
          <div class="header-leading">
            <button #menuToggle class="sidebar-toggle" type="button" [class.no-menu]="mobileViewport() && (visibleNavigation().length <= 1 || auth.role() === 'WAITER')" (click)="toggleSidebar()" aria-controls="staff-navigation" [attr.aria-expanded]="mobileViewport() ? mobileOpen() : !sidebarCollapsed()" [attr.aria-label]="mobileViewport() ? 'Abrir menú' : sidebarCollapsed() ? 'Expandir menú' : 'Contraer menú'" [title]="mobileViewport() ? 'Abrir menú' : sidebarCollapsed() ? 'Expandir menú' : 'Contraer menú'">
              <svg class="ico-panel" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16"/></svg><svg class="ico-menu" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h10"/></svg>
            </button>
            @if (titleMenu()) {
              <button class="title-menu" type="button" (click)="titleClick.emit()" aria-haspopup="dialog"><p class="eyebrow">{{ eyebrow() }}</p><h1>{{ title() }}<svg class="title-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></h1></button>
            } @else {
              <div><p class="eyebrow">{{ eyebrow() }}</p><h1>{{ title() }}</h1></div>
            }
          </div>
          <div class="header-actions">
            @if (branches().length > 1) {
              <label class="branch-picker"><small>Sucursal</small><select [value]="auth.branchId()" (change)="switchBranch($event)">
                @for (branch of branches(); track branch.id) { <option [value]="branch.id">{{branch.name}}</option> }
              </select></label>
            }
            <ng-content select="[shell-actions]" />
            @if (auth.role() !== 'PLATFORM_ADMIN') {
              <button class="icon-button theme-control sound-control" [class.muted]="!sound.enabled()" (click)="sound.toggle()" [attr.aria-label]="sound.enabled() ? 'Silenciar sonidos de aviso' : 'Activar sonidos de aviso'" [title]="sound.enabled() ? 'Sonidos activados' : 'Sonidos silenciados'">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9h4l5-4v14l-5-4H4z"/>@if (sound.enabled()) {<path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/>} @else {<path d="m17 9 5 6m0-6-5 6"/>}</svg>
              </button>
            }
            <div class="user-menu">
              <button class="user-chip" (click)="toggleMenu()" aria-haspopup="dialog" [attr.aria-expanded]="menuOpen()" [attr.aria-label]="'Tu cuenta: ' + auth.userName()">
                <span>{{initials()}}</span><div><strong>{{auth.userName()}}</strong><small>{{roleLabel()}}</small></div>
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
              </button>
              @if (menuOpen()) {
                <div class="effects-pop" role="dialog" [attr.aria-label]="menuView() === 'main' ? 'Tu cuenta' : 'Apariencia'" (keydown.escape)="menuOpen.set(false)">
                  @if (menuView() === 'main') {
                    <header class="um-head"><span class="um-avatar">{{initials()}}</span>
                      <div><strong>{{auth.userName()}}</strong><small>{{roleLabel()}}@if (branchName()) { · {{ branchName() }}}</small></div></header>
                    <div class="fx-section">
                      <button class="fx-option um-row" (click)="menuView.set('appearance')">
                        <span class="fx-ico look"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 0 18c1.2 0 1.8-.8 1.8-1.7 0-1.3-1-1.6-1-2.7 0-1 .8-1.6 1.8-1.6H17a4 4 0 0 0 4-4C21 6.6 17 3 12 3Z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7.5" r="1"/></svg></span>
                        <span class="fx-text"><b>Apariencia</b><small>Tema {{ themeLabel().toLowerCase() }} · vidrio {{ theme.glass() ? 'activado' : 'apagado' }}</small></span>
                        <i class="um-chevron"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg></i>
                      </button>
                      @if (auth.role() === 'WAITER') {
                        <button class="fx-option um-row" (click)="menuOpen.set(false); teamOpen.set(true)">
                          <span class="fx-ico team"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0zM17 6h3v2a3 3 0 0 1-3 3M7 6H4v2a3 3 0 0 0 3 3"/></svg></span>
                          <span class="fx-text"><b>Mi desempeño y ranking</b><small>Meta del día y posiciones del equipo</small></span>
                          <i class="um-chevron"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg></i>
                        </button>
                      }
                      @if (auth.role() === 'ORGANIZATION_ADMIN') {
                        <button class="fx-option um-row" (click)="openBrand()">
                          <span class="fx-ico brand"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9.5 12 4l9 5.5M5 10v9h14v-9M9 19v-5h6v5"/></svg></span>
                          <span class="fx-text"><b>Marca del restaurante</b><small>Logo, color y carta QR · para todo el equipo</small></span>
                          <i class="um-chevron"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg></i>
                        </button>
                      }
                      @if (auth.role() !== 'PLATFORM_ADMIN') {
                        <button class="fx-option" role="switch" [attr.aria-checked]="sound.enabled()" (click)="sound.toggle()">
                          <span class="fx-ico sound"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M16.5 8.5a5 5 0 0 1 0 7"/></svg></span>
                          <span class="fx-text"><b>Sonidos de aviso</b><small>Pedidos listos, llamadas y alertas</small></span><i class="fx-switch"></i>
                        </button>
                      }
                    </div>
                    <div class="um-actions">
                      <button class="um-action" (click)="logout()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M14 8l4 4-4 4M8 12h10"/></svg>{{ sharedDevice ? 'Cambiar usuario' : 'Cerrar sesión' }}</button>
                    </div>
                  } @else {
                    <header class="fx-head"><button class="um-back" (click)="menuView.set('main')" aria-label="Volver"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 6-6 6 6 6"/></svg></button>
                      <div><strong>Apariencia</strong><small>Solo cambia en este equipo</small></div></header>
                  <div class="fx-section">
                    <span class="fx-label">Tema</span>
                    <div class="fx-cards" role="radiogroup" aria-label="Tema">
                      @for (option of themeOptions; track option.mode) {
                        <button role="radio" class="fx-card" [attr.aria-checked]="theme.mode() === option.mode" (click)="theme.setMode(option.mode)">
                          <span class="fx-prev" [attr.data-look]="option.mode"><i></i><b></b><b></b><em></em></span>
                          <span class="fx-card-name">{{ option.label }}</span>
                          <span class="fx-check"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4.5 4.5L19 7"/></svg></span>
                        </button>
                      }
                    </div>
                  </div>
                  <div class="fx-section">
                    <span class="fx-label">Efectos</span>
                    <div class="fx-group">
                      <button class="fx-option" role="switch" [attr.aria-checked]="theme.glass()" (click)="theme.toggleGlass()">
                        <span class="fx-ico glass"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="5"/><path d="M8 9h5M8 13h8"/></svg></span>
                        <span class="fx-text"><b>Efecto vidrio</b><small>Paneles translúcidos con desenfoque</small></span><i class="fx-switch"></i>
                      </button>
                      <button class="fx-option" role="switch" [attr.aria-checked]="theme.cursorGlow()" [disabled]="!theme.glass()" (click)="theme.toggleCursorGlow()">
                        <span class="fx-ico glow"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M12 3v2m0 14v2M3 12h2m14 0h2M6 6l1.5 1.5m9 9L18 18M6 18l1.5-1.5m9-9L18 6"/></svg></span>
                        <span class="fx-text"><b>Luz del cursor</b><small>{{ theme.glass() ? 'Brillo suave que sigue al mouse' : 'Necesita el efecto vidrio' }}</small></span><i class="fx-switch"></i>
                      </button>
                      <button class="fx-option" role="switch" [attr.aria-checked]="theme.motion()" (click)="theme.toggleMotion()">
                        <span class="fx-ico motion"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h3l2-6 4 12 2-6h5"/></svg></span>
                        <span class="fx-text"><b>Animaciones</b><small>Transiciones al abrir paneles y cambiar de vista</small></span><i class="fx-switch"></i>
                      </button>
                    </div>
                  </div>
                  <footer class="fx-foot">
                    <span class="fx-device" [class.modest]="theme.modestDevice"><i></i>{{ theme.modestDevice ? 'Equipo modesto: mejor sin vidrio' : 'Tu equipo maneja bien los efectos' }}</span>
                    <button class="fx-reset" (click)="theme.resetPreferences()">Restablecer</button>
                  </footer>
                  }
                </div>
              }
            </div>
          </div>
        </header>
        <section class="page-content">
        @if (auth.isDemo()) {
          <div class="demo-banner" role="status"><span>✨</span><p><strong>Estás en la demo<span class="db-long"> de RestOS</span>.</strong><span class="db-long"> Los datos son ficticios y se renuevan solos: toca todo sin miedo.</span></p><button type="button" (click)="exitDemo()">Volver al inicio</button></div>
        }
        @if (subscriptionNotice(); as notice) {
          <div class="subscription-banner" [class.critical]="notice.critical" role="status"><strong>{{ notice.title }}</strong><span>{{ notice.body }}</span></div>
        }
        <ng-content /></section>
      </main>
    </div>
    @if (auth.role() === 'WAITER') {
      <nav class="w-dock" [style.--cols]="dockItems().length" aria-label="Navegación del mesero">
        @for (item of dockItems(); track item.key) {
          <button [class.active]="dockActive(item.key)" (click)="dockGo(item.key)" [attr.aria-current]="dockActive(item.key) ? 'page' : null">
            <i class="w-dock-ico"><app-icon [name]="item.icon" [size]="21" />@if (item.key === 'alerts' && waiterNav.pending()) {<b>{{ waiterNav.pending() > 9 ? '9+' : waiterNav.pending() }}</b>}</i>
            <span>{{ item.label }}</span>
          </button>
        }
      </nav>
    }
    @if (teamOpen()) {
      <div class="modal-backdrop shell-team-backdrop" (click)="teamOpen.set(false)">
        <section class="qr-dialog shell-team-dialog" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" aria-label="Ranking del equipo">
          <button class="modal-close" (click)="teamOpen.set(false)" aria-label="Cerrar">×</button>
          <app-team-board />
        </section>
      </div>
    }
  `,
  styleUrls: ['./app-shell.component.scss', './staff-sidebar.scss', './staff-sidebar-layout.scss', './shell-glass.scss', './shell-effects.scss', './user-menu.scss', './appearance.scss', './waiter-dock.scss']
})
export class AppShellComponent implements OnInit {
  readonly theme = inject(ThemeService);
  private readonly permissions = inject(StaffPermissionsService);
  readonly auth = inject(AuthService);
  readonly sound = inject(SoundService);
  private readonly router = inject(Router);
  private readonly realtime = inject(RealtimeService);
  private readonly api = inject(ApiService);
  /** Marca guardada de la pantalla anterior: evita que el nombre y el logo parpadeen al cambiar de módulo. */
  private readonly cachedBrand = ((): { name?: string; logoUrl?: string | null } => { try { return JSON.parse(sessionStorage.getItem('restos_brand') || '{}'); } catch { return {}; } })();
  readonly brandName = signal(this.cachedBrand.name || 'RestOS');
  readonly logoUrl = signal<string | null>(this.cachedBrand.logoUrl ?? null);
  readonly branches = signal<{id:string;name:string;active:boolean}[]>([]);
  readonly subscriptionNotice = signal<{title:string;body:string;critical:boolean}|null>(null);
  /** En una tablet compartida se cambia de usuario con PIN en lugar de cerrar sesión con correo. */
  readonly sharedDevice = this.readSharedDevice();
  readonly sidebarCollapsed = signal(this.initialSidebarState());
  readonly menuOpen = signal(false);
  readonly teamOpen = signal(false);
  readonly waiterNav = inject(WaiterNavService);
  /** Barra inferior del mesero: lo del salón siempre; Mostrador y Reservas solo si tiene el permiso. */
  dockItems(): { key: 'tables' | 'floor' | 'alerts' | 'counter' | 'reservations'; label: string; icon: string }[] {
    const allowed = this.permissions.value();
    return [
      { key: 'tables' as const, label: 'Mesas', icon: 'grid' },
      { key: 'floor' as const, label: 'Plano', icon: 'map' },
      { key: 'alerts' as const, label: 'Pendientes', icon: 'bell' },
      ...(allowed?.counter ? [{ key: 'counter' as const, label: 'Mostrador', icon: 'store' }] : []),
      ...(allowed?.reservations ? [{ key: 'reservations' as const, label: 'Reservas', icon: 'calendar' }] : []),
    ];
  }
  dockActive(key: string): boolean {
    const url = this.router.url.split('?')[0];
    if (key === 'counter') return url.startsWith('/orders');
    if (key === 'reservations') return url.startsWith('/reservations');
    if (!url.startsWith('/waiter')) return false;
    if (key === 'alerts') return this.waiterNav.section() === 'ALERTS';
    return this.waiterNav.section() === 'TABLES' && this.waiterNav.view() === (key === 'floor' ? 'floor' : 'cards');
  }
  dockGo(key: 'tables' | 'floor' | 'alerts' | 'counter' | 'reservations'): void {
    navigator.vibrate?.(6);
    if (key === 'counter') { void this.router.navigateByUrl('/orders'); return; }
    if (key === 'reservations') { void this.router.navigateByUrl('/reservations'); return; }
    if (key === 'alerts') this.waiterNav.section.set('ALERTS');
    else { this.waiterNav.section.set('TABLES'); this.waiterNav.setView(key === 'floor' ? 'floor' : 'cards'); }
    if (!this.router.url.startsWith('/waiter')) void this.router.navigateByUrl('/waiter');
  }
  readonly menuView = signal<'main' | 'appearance'>('main');
  /** Brillo especular que sigue al puntero sobre cualquier superficie de vidrio líquido (.lg). */
  private readonly glow = (() => {
    const move = (event: PointerEvent) => {
      if (!this.theme.cursorGlow()) return;
      const target = (event.target as Element | null)?.closest?.('.lg') as HTMLElement | null;
      if (!target) return;
      const rect = target.getBoundingClientRect();
      target.style.setProperty('--mx', `${event.clientX - rect.left}px`);
      target.style.setProperty('--my', `${event.clientY - rect.top}px`);
    };
    document.addEventListener('pointermove', move, { passive: true });
    return move;
  })();
  readonly mobileViewport = signal(window.innerWidth <= 900);
  readonly mobileOpen = signal(false);
  private readonly sidebar = viewChild<ElementRef<HTMLElement>>('sidebar');
  private readonly navScroll = viewChild<ElementRef<HTMLElement>>('navScroll');
  /** Cada pantalla crea su propio menú: se recuerda dónde estaba el scroll para no saltar al inicio. */
  private readonly restoreNav = afterNextRender(() => {
    const nav = this.navScroll()?.nativeElement; if (!nav) return;
    let saved = 0; try { saved = Number(sessionStorage.getItem('restos_nav_scroll')) || 0; } catch { /* sin almacenamiento */ }
    nav.scrollTop = saved;
    // Si el módulo actual no se ve, se acerca sin mover el resto.
    nav.querySelector<HTMLElement>('a.active, a[aria-current="page"]')?.scrollIntoView({ block: 'nearest' });
  });
  rememberNavScroll(): void {
    const nav = this.navScroll()?.nativeElement; if (!nav) return;
    try { sessionStorage.setItem('restos_nav_scroll', String(Math.round(nav.scrollTop))); } catch { /* sin almacenamiento */ }
  }
  private readonly menuToggle = viewChild<ElementRef<HTMLButtonElement>>('menuToggle');
  readonly title = input.required<string>();
  readonly eyebrow = input('RestOS');
  /** El título funciona como selector (por ejemplo, la zona de trabajo del mesero). */
  readonly titleMenu = input(false);
  readonly titleClick = output<void>();
  /** Menú agrupado en secciones plegables; las de uso ocasional empiezan cerradas. */
  readonly navigation = input([
    { label: 'Resumen', path: '/admin', icon: '⌂', group: 'Negocio' },
    { label: 'Modo TV', path: '/live', icon: '▣', group: 'Negocio' },
    { label: 'Caja', path: '/cash', icon: '▤', group: 'Negocio' },
    { label: 'Reportes', path: '/reports', icon: '▥', group: 'Negocio' },
    { label: 'Clientes', path: '/customers', icon: '☺', group: 'Negocio' },
    { label: 'Personal', path: '/personal', icon: '☻', group: 'Negocio' },
    { label: 'Mesas', path: '/waiter', icon: '▦', group: 'Servicio' },
    { label: 'Mostrador', path: '/orders', icon: '◫', group: 'Servicio' },
    { label: 'Reservas', path: '/reservations', icon: '◷', group: 'Servicio' },
    { label: 'Cocina', path: '/kitchen', icon: '♨', group: 'Servicio' },
    { label: 'Menú', path: '/menu', icon: '☰', group: 'Carta y stock' },
    { label: 'Inventario', path: '/inventory', icon: '⬡', group: 'Carta y stock' },
    { label: 'Plano del salón', path: '/floor', icon: '▦', group: 'Carta y stock' },
    { label: 'Configuración', path: '/configuration', icon: '⚙', group: 'Administración' },
    { label: 'Puesta en marcha', path: '/setup', icon: '✓', group: 'Administración' },
    { label: 'Auditoría', path: '/audit', icon: '◈', group: 'Administración' },
    { label: 'Plataforma', path: '/platform', icon: '◇', group: 'Plataforma' }
  ]);
  private readonly closedGroups = signal<Set<string>>(this.readClosedGroups());
  navGroups(): { name: string; items: { label: string; path: string; icon: string }[] }[] {
    const groups: { name: string; items: { label: string; path: string; icon: string }[] }[] = [];
    for (const item of this.visibleNavigation()) {
      const name = (item as { group?: string }).group || 'Menú';
      let group = groups.find(entry => entry.name === name);
      if (!group) { group = { name, items: [] }; groups.push(group); }
      group.items.push(item);
    }
    return groups;
  }
  showGroupHeaders(): boolean { return this.navGroups().length > 1 && (!this.sidebarCollapsed() || this.mobileViewport()); }
  /** Una sección se abre sola si contiene la pantalla actual. */
  isGroupOpen(group: { name: string; items: { path: string }[] }): boolean {
    if (group.items.some(item => this.router.url.startsWith(item.path))) return true;
    return !this.closedGroups().has(group.name);
  }
  toggleGroup(name: string): void {
    this.closedGroups.update(current => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name); else next.add(name);
      try { localStorage.setItem('restos.nav.closed', JSON.stringify([...next])); } catch { /* sin almacenamiento */ }
      return next;
    });
  }
  private readClosedGroups(): Set<string> {
    try {
      const saved = localStorage.getItem('restos.nav.closed');
      return new Set(saved ? JSON.parse(saved) as string[] : ['Administración']);
    } catch { return new Set(['Administración']); }
  }
  visibleNavigation(): {label:string;path:string;icon:string}[] {
    const role = this.auth.role();
    if (role === 'PLATFORM_ADMIN') return this.navigation().filter(item => item.path === '/platform');
    if (role === 'KITCHEN') return this.navigation().filter(item => item.path === '/kitchen');
    if (role === 'WAITER') {
      const allowed = this.permissions.value();
      return this.navigation().filter(item => item.path === '/waiter' || (item.path === '/orders' && !!allowed?.counter) || (item.path === '/reservations' && !!allowed?.reservations));
    }
    return this.navigation().filter(item => item.path !== '/platform');
  }
  ngOnInit(): void {
    this.sound.reload();
    this.permissions.load().subscribe();
    this.api.get<{name:string;logoUrl:string|null;primaryColor:string;defaultTheme:string}>('/staff/branding').subscribe(brand => {
      this.brandName.set(brand.name); this.logoUrl.set(brand.logoUrl);
      try { sessionStorage.setItem('restos_brand', JSON.stringify({ name: brand.name, logoUrl: brand.logoUrl })); } catch { /* sin almacenamiento */ }
      this.theme.applyBrand(brand.primaryColor, brand.defaultTheme);
    });
    if (this.auth.role() === 'ORGANIZATION_ADMIN') {
      this.api.get<{id:string;name:string;active:boolean}[]>('/admin/branches').subscribe(branches =>
        this.branches.set(branches.filter(branch => branch.active)));
    }
    if (['ORGANIZATION_ADMIN','BRANCH_MANAGER'].includes(this.auth.role())) {
      this.api.get<{state:string;daysLeft:number;graceEndsAt:string|null}>('/admin/subscription').subscribe({
        next: value => this.subscriptionNotice.set(this.noticeFor(value)), error: () => undefined
      });
    }
  }
  brandInitial(): string { return this.brandName().slice(0, 1).toUpperCase(); }
  initials(): string { return this.auth.userName().split(' ').map(part => part[0]).join('').slice(0,2).toUpperCase(); }
  toggleSidebar(): void {
    // En móvil se abre un panel superpuesto; el ancho elegido para escritorio se conserva.
    if (this.mobileViewport()) {
      this.mobileOpen.set(true);
      requestAnimationFrame(() => this.sidebar()?.nativeElement.querySelector<HTMLElement>('a')?.focus());
      return;
    }
    this.sidebarCollapsed.update(value => !value);
    localStorage.setItem('restos.sidebar.collapsed', String(this.sidebarCollapsed()));
  }
  closeMobileMenu(): void {
    if (!this.mobileOpen()) return;
    this.mobileOpen.set(false);
    requestAnimationFrame(() => this.menuToggle()?.nativeElement.focus());
  }
  /** Cualquier clic o toque fuera del menú de la cuenta lo cierra. */
  @HostListener('document:pointerdown', ['$event'])
  closeMenuOutside(event: PointerEvent): void {
    if (this.menuOpen() && !(event.target instanceof Element && event.target.closest('.user-menu'))) this.menuOpen.set(false);
  }
  @HostListener('document:keydown.escape')
  closeMenuOnEscape(): void { this.menuOpen.set(false); }
  @HostListener('window:resize')
  onResize(): void {
    this.mobileViewport.set(window.innerWidth <= 900);
    if (!this.mobileViewport()) this.mobileOpen.set(false);
  }
  /** Mantiene el foco dentro del menú modal y permite cerrarlo con Escape. */
  handleMenuKeys(event: KeyboardEvent): void {
    if (!this.mobileOpen()) return;
    if (event.key === 'Escape') { event.preventDefault(); this.closeMobileMenu(); }
    if (event.key !== 'Tab') return;
    const elements = Array.from(this.sidebar()!.nativeElement.querySelectorAll<HTMLElement>('a, button, select')).filter(el => el.getClientRects().length);
    const first = elements[0], last = elements.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
  roleLabel(): string {
    return ({ ORGANIZATION_ADMIN: 'Administrador', PLATFORM_ADMIN: 'Plataforma', WAITER: 'Mesero', KITCHEN: 'Cocina' } as Record<string, string>)[this.auth.role()] || 'Equipo';
  }
  readonly themeOptions: { mode: ThemeMode; label: string }[] = [{ mode: 'light', label: 'Claro' }, { mode: 'dark', label: 'Oscuro' }, { mode: 'system', label: 'Automático' }];
  openBrand(): void { this.menuOpen.set(false); void this.router.navigate(['/configuration'], { queryParams: { s: 'brand' } }); }
  toggleMenu(): void { this.menuView.set('main'); this.menuOpen.set(!this.menuOpen()); }
  branchName(): string { return this.branches().length > 1 ? this.branches().find((b) => b.id === this.auth.branchId())?.name || '' : ''; }
  themeLabel(): string { return { light: 'Claro', dark: 'Oscuro', system: 'Automático' }[this.theme.mode()]; }
  logout(): void { this.realtime.disconnect(); this.auth.logout(); this.router.navigateByUrl('/login'); }
  exitDemo(): void { this.realtime.disconnect(); this.auth.logout(); this.router.navigateByUrl('/'); }
  switchBranch(event: Event): void {
    const branchId = (event.target as HTMLSelectElement).value;
    if (!branchId || branchId === this.auth.branchId()) return;
    this.auth.switchBranch(branchId).subscribe({
      next: () => { this.realtime.disconnect(); location.reload(); },
      error: () => location.reload()
    });
  }
  /** Avisa con anticipación al dueño antes de que se corte el servicio por falta de pago. */
  private noticeFor(value:{state:string;daysLeft:number;graceEndsAt:string|null}):{title:string;body:string;critical:boolean}|null {
    const days = value.daysLeft;
    if (value.state === 'TRIAL' && days <= 7) return { title: 'Prueba gratuita', body: days <= 0 ? 'Tu periodo de prueba termina hoy. Comunícate con RestOS para activar tu plan.' : `Te quedan ${days} ${days === 1 ? 'día' : 'días'} de prueba. Comunícate con RestOS para activar tu plan.`, critical: days <= 2 };
    if (value.state === 'ACTIVE' && days <= 5) return { title: 'Renovación próxima', body: `Tu plan vence en ${days} ${days === 1 ? 'día' : 'días'}. Renueva para no interrumpir el servicio.`, critical: false };
    if (value.state === 'GRACE') return { title: 'Pago pendiente', body: `Tu plan venció. El servicio se suspenderá el ${value.graceEndsAt} si no se registra el pago.`, critical: true };
    return null;
  }
  private readSharedDevice(): boolean {
    try { return !!localStorage.getItem('restos_device_token'); } catch { return false; }
  }
  private initialSidebarState(): boolean {
    const saved = localStorage.getItem('restos.sidebar.collapsed');
    // Por defecto, riel flotante de íconos (estilo iOS); el botón lo expande con nombres.
    return saved === null ? true : saved === 'true';
  }
}
