import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

/** Cliente HTTP central; evita que cada pantalla construya URLs y cabeceras de forma diferente. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api';

  get<T>(path: string, tableToken?: string, guestToken?: string) {
    return this.http.get<T>(`${this.baseUrl}${path}`, { headers: this.guestHeaders(tableToken, guestToken) });
  }

  post<T>(path: string, body: unknown, tableToken?: string, guestToken?: string) {
    return this.http.post<T>(`${this.baseUrl}${path}`, body,
      { headers: this.guestHeaders(tableToken, guestToken) });
  }

  patch<T>(path: string, body: unknown, tableToken?: string, guestToken?: string) {
    return this.http.patch<T>(`${this.baseUrl}${path}`, body,
      { headers: this.guestHeaders(tableToken, guestToken) });
  }

  put<T>(path: string, body: unknown) {
    return this.http.put<T>(`${this.baseUrl}${path}`, body);
  }

  delete<T>(path: string, tableToken?: string, guestToken?: string) {
    return this.http.delete<T>(`${this.baseUrl}${path}`,
      { headers: this.guestHeaders(tableToken, guestToken) });
  }

  private tableHeaders(token?: string): HttpHeaders {
    return token ? new HttpHeaders({ 'X-Table-Token': token }) : new HttpHeaders();
  }

  /** Agrega la identidad individual únicamente en operaciones realizadas por un comensal. */
  private guestHeaders(tableToken?: string, guestToken?: string): HttpHeaders {
    let headers = this.tableHeaders(tableToken);
    if (guestToken) headers = headers.set('X-Guest-Token', guestToken);
    return headers;
  }
}
