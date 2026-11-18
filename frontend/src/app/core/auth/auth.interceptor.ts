import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Adjunta JWT solo cuando existe una sesión de personal autenticada. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const isAuthEndpoint = /\/api\/auth\/(login|refresh|logout)/.test(request.url);
  const isPublicEndpoint = request.url.includes('/api/public/');
  const token = auth.token();
  const authenticatedRequest = token && !isAuthEndpoint && !isPublicEndpoint
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
  return next(authenticatedRequest).pipe(catchError(error => {
    if (error.status !== 401 || isAuthEndpoint || isPublicEndpoint || !auth.refreshToken()) return throwError(() => error);
    return auth.refreshAccessToken(token).pipe(
      // Solo un fallo al renovar cierra la sesión; los errores de la petición reintentada no lo hacen.
      catchError(refreshError => {
        auth.clearSession();
        location.assign('/login');
        return throwError(() => refreshError);
      }),
      switchMap(accessToken => next(request.clone({
        setHeaders: { Authorization: `Bearer ${accessToken}` }
      })))
    );
  }));
};
