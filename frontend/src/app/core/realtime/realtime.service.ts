import { Injectable, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';

export interface RealtimeEvent<T = unknown> {
  eventId: string;
  type: string;
  occurredAt: string;
  payload: T;
  /**
   * Solo en el canal de meseros y en eventos de una mesa: quién la atiende y a quiénes debe sonarles
   * el aviso. alert null = a todo el equipo (mesa sin mesero, nadie en turno o aviso escalado).
   */
  audience?: { waiterId: string | null; alert: string[] | null } | null;
}

/** Mantiene una única conexión STOMP y deja que cada módulo controle sus suscripciones. */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  readonly connected = signal(false);
  readonly connectionState = signal<'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ERROR'>('DISCONNECTED');
  private client?: Client;
  /** Varios módulos pueden escuchar el mismo canal (p. ej. mesero y barra de metas): una suscripción, muchos oyentes. */
  private readonly pendingSubscriptions = new Map<string, Set<(event: RealtimeEvent<unknown>) => void>>();
  private readonly subscriptions = new Map<string, StompSubscription>();
  private readonly auth = inject(AuthService);
  /** Credenciales vigentes: se leen en cada (re)conexión para no reconectar con un token vencido. */
  private headerFactory?: () => Record<string, string>;
  /** Ya hubo una conexión: las siguientes son reconexiones y obligan a recargar datos. */
  private everConnected = false;
  private readonly resyncHandlers = new Set<() => void>();
  private hiddenAt = 0;

  constructor() {
    // Al bloquear el celular o pasar a otra app el navegador suspende el socket (a veces sin cerrarlo).
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') { this.hiddenAt = Date.now(); return; }
      this.resume(this.hiddenAt > 0 && Date.now() - this.hiddenAt > 15000);
    });
    window.addEventListener('pageshow', (event) => { if ((event as PageTransitionEvent).persisted) this.resume(true); });
    window.addEventListener('online', () => this.resume(true));
    // En la computadora: al volver a la ventana se verifica la conexión.
    window.addEventListener('focus', () => this.resume(false));
    // Vigilante: si la conexión lleva caída más de 20 s (p. ej. quedó en error o la laptop se suspendió),
    // se rehace desde cero en vez de esperar a que se recupere sola.
    window.setInterval(() => {
      if (!this.headerFactory || document.visibilityState !== 'visible') return;
      if (this.client?.connected) { this.downSince = 0; return; }
      if (!this.downSince) { this.downSince = Date.now(); return; }
      if (Date.now() - this.downSince > 20000) { this.downSince = 0; this.resume(true); }
    }, 5000);
  }
  private downSince = 0;

  /**
   * Avisa cada vez que la conexión se recupera tras un corte: cada módulo recarga sus datos ahí
   * para no perder lo que pasó mientras estaba desconectado. Devuelve la función para dejar de escuchar.
   */
  onResync(handler: () => void): () => void {
    this.resyncHandlers.add(handler);
    return () => this.resyncHandlers.delete(handler);
  }

  /** Conecta paneles internos usando el JWT emitido para el personal. */
  connectStaff(jwtToken: string): void {
    this.connect(() => ({ Authorization: `Bearer ${this.auth.token() || jwtToken}` }));
  }

  /** Conecta al comensal usando la credencial compartida de su mesa. */
  connectTable(tableToken: string): void {
    this.connect(() => ({ 'X-Table-Token': tableToken }));
  }

  private connect(headers: () => Record<string, string>): void {
    this.headerFactory = headers;
    if (this.client?.active) return;
    this.connectionState.set('CONNECTING');
    const client = new Client({
      brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
      connectHeaders: headers(),
      beforeConnect: () => { client.connectHeaders = this.headerFactory?.() ?? {}; },
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        this.connected.set(true);
        this.connectionState.set('CONNECTED');
        this.pendingSubscriptions.forEach((_, destination) => this.activateSubscription(destination));
        if (this.everConnected) this.resyncHandlers.forEach((handler) => { try { handler(); } catch { /* cada módulo maneja sus errores */ } });
        this.everConnected = true;
      },
      onStompError: () => {
        this.connected.set(false);
        this.connectionState.set('ERROR');
      },
      onWebSocketError: () => {
        this.connected.set(false);
        this.connectionState.set('ERROR');
      },
      onWebSocketClose: () => {
        this.connected.set(false);
        if (this.client?.active && this.connectionState() !== 'ERROR') this.connectionState.set('CONNECTING');
      }
    });
    this.client = client;
    client.activate();
  }

  subscribe<T>(destination: string, handler: (event: RealtimeEvent<T>) => void): StompSubscription {
    if (!this.client?.connected) throw new Error('La conexión en tiempo real todavía no está disponible');
    return this.client.subscribe(destination, (message: IMessage) => handler(JSON.parse(message.body)));
  }

  /** Registra el canal aunque el socket todavía esté conectando y lo restaura tras una reconexión. */
  watch<T>(destination: string, handler: (event: RealtimeEvent<T>) => void): () => void {
    const typed = handler as (event: RealtimeEvent<unknown>) => void;
    const handlers = this.pendingSubscriptions.get(destination) ?? new Set();
    const isNew = !handlers.size;
    handlers.add(typed);
    this.pendingSubscriptions.set(destination, handlers);
    if (isNew && this.client?.connected) this.activateSubscription(destination);
    return () => {
      const current = this.pendingSubscriptions.get(destination);
      current?.delete(typed);
      if (current && current.size) return;
      this.pendingSubscriptions.delete(destination);
      this.subscriptions.get(destination)?.unsubscribe();
      this.subscriptions.delete(destination);
    };
  }

  private activateSubscription(destination: string): void {
    this.subscriptions.get(destination)?.unsubscribe();
    const subscription = this.client!.subscribe(destination, (message: IMessage) => {
      const event = JSON.parse(message.body) as RealtimeEvent<unknown>;
      // Cada oyente recibe el evento; si uno falla, los demás igual lo procesan.
      this.pendingSubscriptions.get(destination)?.forEach((listener) => {
        try { listener(event); } catch (error) { console.error('Error procesando evento en tiempo real', error); }
      });
    });
    this.subscriptions.set(destination, subscription);
  }

  /**
   * Rehace la conexión manteniendo los canales registrados (al desbloquear el celular el socket
   * puede quedar muerto sin avisar). Sin «force», solo actúa si no hay conexión.
   */
  resume(force = false): void {
    const headers = this.headerFactory;
    if (!headers || (!force && this.client?.connected)) return;
    const previous = this.client;
    this.client = undefined;
    this.subscriptions.clear();
    this.connected.set(false);
    void previous?.deactivate();
    this.connect(headers);
  }

  disconnect(): void {
    this.headerFactory = undefined;
    this.everConnected = false;
    const client = this.client;
    this.client = undefined;
    this.subscriptions.clear();
    this.pendingSubscriptions.clear();
    this.connected.set(false);
    this.connectionState.set('DISCONNECTED');
    void client?.deactivate();
  }
}
