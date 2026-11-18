import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, finalize, firstValueFrom, from, Observable, of, shareReplay, tap } from 'rxjs';
import { ApiService } from '../api/api.service';

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  role: string;
  fullName: string;
  organizationId: string | null;
  branchId: string | null;
  refreshToken: string | null;
}

/** Conserva la sesión del personal y expone únicamente los datos necesarios a la interfaz. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  /** sessionStorage mantiene la identidad aislada por pestaña y sobrevive a una recarga. */
  private readonly authStorage = sessionStorage;
  private readonly authKeys = [
    'restos_access_token', 'restos_refresh_token', 'restos_user_name',
    'restos_user_role', 'restos_branch_id', 'restos_organization_id', 'restos_demo'
  ] as const;
  private readonly tokenState = signal(this.authStorage.getItem('restos_access_token'));
  private refreshRequest?: Observable<string>;
  readonly authenticated = computed(() => !!this.tokenState());

  constructor() {
    // Elimina la sesión compartida de versiones anteriores para impedir mezclar roles entre pestañas.
    this.authKeys.forEach(key => localStorage.removeItem(key));
  }

  login(email: string, password: string) {
    return this.api.post<LoginResponse>('/auth/login', { email, password }).pipe(
      tap(response => this.applySession(response))
    );
  }

  /** Ingreso rápido desde una tablet autorizada del local. */
  pinLogin(deviceToken: string, userId: string, pin: string) {
    return this.api.post<LoginResponse>('/auth/pin-login', { deviceToken, userId, pin }).pipe(
      tap(response => this.applySession(response))
    );
  }

  /** Renueva el JWT una sola vez aunque varias peticiones fallen simultáneamente. */
  refreshAccessToken(staleAccessToken = this.token()): Observable<string> {
    if (this.refreshRequest) return this.refreshRequest;
    this.refreshRequest = from(this.rotateWithCrossTabLock(staleAccessToken)).pipe(
      finalize(() => this.refreshRequest = undefined),
      shareReplay({bufferSize:1,refCount:false}));
    return this.refreshRequest;
  }

  /** Renueva antes de cargar una ruta para que HTTP y WebSocket reciban un JWT vigente. */
  ensureValidAccessToken(): Observable<string | null> {
    const accessToken = this.token();
    if (accessToken && this.isTokenValid(accessToken, 30)) return of(accessToken);
    if (!this.refreshToken()) return of(null);
    return this.refreshAccessToken(accessToken).pipe(catchError(() => {
      this.clearSession();
      return of(null);
    }));
  }

  switchBranch(branchId: string): Observable<LoginResponse> {
    return this.api.post<LoginResponse>(`/auth/switch-branch/${branchId}`,
      { refreshToken: this.refreshToken() }).pipe(tap(response => this.applySession(response)));
  }

  token(): string | null {
    const stored = this.authStorage.getItem('restos_access_token');
    if (stored !== this.tokenState()) this.tokenState.set(stored);
    return stored;
  }

  refreshToken(): string | null { return this.authStorage.getItem('restos_refresh_token'); }

  role(): string { return this.authStorage.getItem('restos_user_role') || ''; }
  branchId(): string { return this.authStorage.getItem('restos_branch_id') || ''; }
  userName(): string { return this.authStorage.getItem('restos_user_name') || 'Usuario'; }
  /** Id del usuario (campo «sub» del JWT): sirve para saber qué mesas y avisos son míos. */
  userId(): string {
    const token = this.token();
    if (!token) return '';
    try {
      const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return (JSON.parse(atob(encoded)) as { sub?: string }).sub || '';
    } catch { return ''; }
  }

  logout(): void {
    const refreshToken = this.refreshToken();
    if (refreshToken) this.api.post('/auth/logout', { refreshToken }).subscribe({ error: () => undefined });
    this.clearSession();
  }

  clearSession(): void {
    this.authKeys.forEach(key => this.authStorage.removeItem(key));
    this.tokenState.set(null);
  }

  /** Entra a la demostración pública con la sesión que entregó el servidor. */
  startDemo(role: 'ADMIN' | 'WAITER' | 'KITCHEN') {
    return this.api.post<{ session: LoginResponse; dinerQr: string | null }>('/public/demo/start', { role }).pipe(
      tap(response => {
        this.applySession(response.session);
        this.authStorage.setItem('restos_demo', '1');
      })
    );
  }

  isDemo(): boolean { return this.authStorage.getItem('restos_demo') === '1'; }

  private applySession(response: LoginResponse): void {
    this.authStorage.setItem('restos_access_token', response.accessToken);
    if (response.refreshToken) this.authStorage.setItem('restos_refresh_token', response.refreshToken);
    this.authStorage.setItem('restos_user_name', response.fullName);
    this.authStorage.setItem('restos_user_role', response.role);
    if (response.branchId) this.authStorage.setItem('restos_branch_id', response.branchId);
    else this.authStorage.removeItem('restos_branch_id');
    if (response.organizationId) this.authStorage.setItem('restos_organization_id', response.organizationId);
    else this.authStorage.removeItem('restos_organization_id');
    this.tokenState.set(response.accessToken);
  }

  /** Web Locks evita que dos pestañas roten simultáneamente el mismo refresh token. */
  private async rotateWithCrossTabLock(staleAccessToken: string | null): Promise<string> {
    const rotate = async (): Promise<string> => {
      const current = this.authStorage.getItem('restos_access_token');
      if (staleAccessToken && current && current !== staleAccessToken && this.isTokenValid(current, 5)) {
        this.tokenState.set(current);
        return current;
      }
      const refreshToken = this.refreshToken();
      if (!refreshToken) throw new Error('No existe un refresh token');
      const response = await firstValueFrom(this.api.post<LoginResponse>('/auth/refresh', { refreshToken }));
      this.applySession(response);
      return response.accessToken;
    };
    const locks = (navigator as Navigator & {
      locks?: {request<T>(name:string, callback:() => Promise<T>):Promise<T>}
    }).locks;
    return locks ? locks.request('restos-auth-refresh', rotate) : rotate();
  }

  private isTokenValid(token: string, safetySeconds: number): boolean {
    try {
      const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(encoded)) as {exp?:number};
      return !!payload.exp && payload.exp * 1000 > Date.now() + safetySeconds * 1000;
    } catch { return false; }
  }
}
