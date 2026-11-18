import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map, of, switchMap } from 'rxjs';
import { AuthService } from './auth.service';
import { StaffPermissionsView, StaffPermissionsService } from './staff-permissions.service';

/** Impide mostrar paneles internos sin JWT o con un rol ajeno a la pantalla. */
export const roleGuard = (roles: string[]): CanActivateFn => () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureValidAccessToken().pipe(map(token =>
    token && roles.includes(auth.role()) ? true : router.createUrlTree(['/login'])));
};

/** Mostrador y Reservas: el mesero entra solo si se le dio el permiso en Configuración → Equipo. */
export const permissionGuard = (module: keyof StaffPermissionsView): CanActivateFn => () => {
  const auth = inject(AuthService);
  const permissions = inject(StaffPermissionsService);
  const router = inject(Router);
  if (auth.role() !== 'WAITER') return of(true);
  return permissions.can(module).pipe(switchMap((allowed) => of(allowed ? true : router.createUrlTree(['/waiter']))));
};
