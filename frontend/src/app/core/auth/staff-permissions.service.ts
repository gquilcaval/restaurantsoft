import { inject, Injectable, signal } from '@angular/core';
import { catchError, map, Observable, of, tap } from 'rxjs';
import { ApiService } from '../api/api.service';
import { AuthService } from './auth.service';

export interface StaffPermissionsView { counter: boolean; reservations: boolean; }

/** Módulos opcionales de la persona conectada (Mostrador y Reservas para meseros). */
@Injectable({ providedIn: 'root' })
export class StaffPermissionsService {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  readonly value = signal<StaffPermissionsView | null>(null);
  private loadedFor = '';

  load(force = false): Observable<StaffPermissionsView> {
    if (this.auth.role() !== 'WAITER') return of({ counter: true, reservations: true });
    const key = this.auth.userName() + this.auth.role();
    const current = this.value();
    if (current && !force && this.loadedFor === key) return of(current);
    return this.api.get<StaffPermissionsView>('/staff/permissions').pipe(
      tap((value) => { this.value.set(value); this.loadedFor = key; }),
      catchError(() => of({ counter: false, reservations: false })),
    );
  }
  can(module: keyof StaffPermissionsView): Observable<boolean> { return this.load().pipe(map((value) => value[module])); }
}
