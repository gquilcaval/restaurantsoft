import { Component, computed, HostListener, inject, OnDestroy, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { KitchenOrder, MenuItemView, OrderItemView } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeEvent, RealtimeService } from '../../core/realtime/realtime.service';
import { VoidItemDialogComponent, VoidRequest } from '../../shared/ordering/void-item-dialog.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { PrintService } from '../../shared/ui/print.service';
import { ToastService } from '../../shared/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { SoundService } from '../../shared/ui/sound.service';

interface StationBoard {
  stations: { id: string; name: string; pending: number; waitAverage: number; waitMax: number; prepAverage: number | null; status: 'OK' | 'BUSY' | 'LATE'; lateTables: string[] }[];
  alerts: { key: string; tone: 'warning' | 'error' | 'info'; icon: string; title: string; body: string }[];
}

/** KDS conectado: recibe rondas por STOMP y confirma cada transición de preparación. */
@Component({
  imports: [IconComponent, AppShellComponent, FormsModule, VoidItemDialogComponent],
  // Estilos sin encapsular: son los mismos que antes vivían en la hoja global, ahora fuera del paquete inicial.
  encapsulation: ViewEncapsulation.None,
  styleUrls: ['./kitchen-1.scss', './kitchen-2.scss', './kitchen-3.scss', './kitchen-motion.scss', './kitchen-ops.scss', './kitchen-glass.scss', './kitchen-top.scss', './kitchen-cards.scss'],
  template: `
    <app-shell title="Cocina" eyebrow="">
      <div shell-actions class="kitchen-shell-actions">
        <button class="secondary small kitchen-stock-button" (click)="openStock()">⊘ Agotados @if(soldOutCount()){<b>{{soldOutCount()}}</b>}</button>
        <span class="kitchen-connection" [class.offline]="realtime.connectionState()==='ERROR'"><i></i><b>{{realtime.connected()?'En línea':realtime.connectionState()==='ERROR'?'Sin conexión':'Conectando'}}</b></span>
      </div>
      @if (error()) { <div class="kitchen-error" role="alert"><span>!</span><p><strong>No se pudo completar</strong><small>{{error()}}</small></p><button (click)="error.set('')" aria-label="Cerrar mensaje">×</button></div> }
      <!-- Estaciones como pestañas (cola y espera) y avisos agrupados: los pedidos tienen todo el alto -->
      <div class="kx-top">
        <nav class="kx-stations" aria-label="Estación de preparación">
          <button [class.on]="!station()" (click)="selectStation('')"><app-icon name="layers" [size]="15" />Todas<small>{{ totalPending() }}</small></button>
          @for (item of stationTabs(); track item.id) {
            <button [class.on]="station() === item.id" [attr.data-status]="item.status" (click)="selectStation(item.id)" [title]="item.waitAverage !== null ? 'Espera promedio ' + item.waitAverage + ' min' : ''">
              <i></i>{{ item.name }}<small>{{ item.pending }}</small>@if (item.waitAverage !== null) {<em>{{ item.waitAverage }}′</em>}
            </button>
          }
        </nav>
        @if (visibleAlerts().length) {
          <div class="kx-alerts">
            <button class="kx-alert-btn" [attr.data-tone]="alertTone()" [attr.aria-expanded]="alertsOpen()" (click)="alertsOpen.set(!alertsOpen())"><app-icon name="alert" [size]="15" />{{ alertSummary() }}<b>{{ visibleAlerts().length }}</b></button>
            @if (alertsOpen()) {
              <div class="lg kx-alert-pop" role="dialog" aria-label="Avisos de cocina">
                @for (alert of visibleAlerts(); track alert.key) {
                  <div class="kx-alert" [attr.data-tone]="alert.key.startsWith('STOCK') ? 'stock' : alert.tone"><app-icon [name]="alert.key.startsWith('STOCK') ? 'package' : 'clock'" [size]="17" /><p><strong>{{ alert.title }}</strong><small>{{ alert.body }}</small></p><button (click)="dismissAlert(alert.key)" aria-label="Ocultar aviso"><app-icon name="x" [size]="14" /></button></div>
                }
              </div>
            }
          </div>
        }
      </div>
      <nav class="kitchen-mobile-tabs" aria-label="Estados de cocina">
        @for(column of columns();track column.status){<button [class.active]="activeColumn()===column.status" [class]="column.tone" (click)="activeColumn.set(column.status)"><span>{{column.icon}}</span>{{column.title}}<b>{{itemCount(column.status)}}</b></button>}
      </nav>
      <div class="kds">
        @for (column of columns(); track column.status) {
          <section class="kds-column kitchen-column" [class]="column.tone" [class.mobile-active]="activeColumn()===column.status"><header><div><span>{{column.icon}}</span><div><h2>{{column.title}}</h2><small>{{itemCount(column.status)}} {{itemCount(column.status)===1?'plato':'platos'}}</small></div></div><b>{{column.orders.length}}</b></header>
            <div class="kitchen-column-scroll">
            @for (order of column.orders; track order.id) {
              <article class="order-card kitchen-order-card" [class]="ageTone(order.submittedAt)" [class.fresh]="freshIds().has(order.id)" [class.priority]="column.status!=='READY' && isPriority(order, column.status)">@if(column.status!=='READY' && isPriority(order, column.status)){<span class="kds-priority"><app-icon name="zap" [size]="11" /> Prioridad</span>}<div class="order-head kitchen-order-head"><div>@if(order.channel!=='DINE_IN'){<span class="channel-badge" [class.delivery]="order.channel==='DELIVERY'">{{order.channel==='DELIVERY'?'Delivery':'Llevar'}}</span>}<span>{{order.tableLabel}}</span><strong>Ronda {{order.roundNumber}}</strong></div><time><i></i>{{elapsed(order.submittedAt)}}</time></div>
                @if(syncHint(order, column.status); as hint){<p class="kds-sync" [class.wait]="column.status==='READY'">{{hint}}</p>}
                @for (item of itemsFor(order, column.status); track item.id) {
                  <div class="kitchen-item kitchen-dish"><b>{{item.quantity}}</b><span><strong>{{item.name}}</strong>@if(item.modifiers){<small class="kitchen-modifiers">{{item.modifiers}}</small>}@if(item.notes){<small><i>!</i>{{item.notes}}</small>}@if(canVoid(item)){<button class="kitchen-void" (click)="voiding.set(item)" [attr.aria-label]="'Anular '+item.name">Anular</button>}</span>
                    @if (column.status === 'SUBMITTED') { <button class="kitchen-action start" [disabled]="updating(item.id)" (click)="change(item,'PREPARING')">{{updating(item.id)?'Actualizando…':'Preparar'}}</button> }
                    @if (column.status === 'PREPARING') { <button class="kitchen-action finish" [disabled]="updating(item.id)" (click)="change(item,'READY')">{{updating(item.id)?'Actualizando…':'Marcar listo'}}</button> }
                    @if (column.status === 'READY') { <span class="kitchen-waiter-status"><i></i>Esperando mesero</span> }
                  </div>
                }
              </article>
            }
            @if (!column.orders.length) { <div class="kitchen-empty"><span>{{column.status==='READY'?'✓':column.icon}}</span><strong>{{column.status==='SUBMITTED'?'Sin órdenes nuevas':column.status==='PREPARING'?'Nada en preparación':'Nada por entregar'}}</strong><p>Los pedidos aparecerán aquí automáticamente.</p></div> }
            </div>
          </section>
        }
      </div>
      <app-void-item-dialog [item]="voiding()" [busy]="voidBusy()" (confirmed)="confirmVoid($event)" (cancelled)="voiding.set(null)" />
      @if(stockOpen()){
        <div class="modal-backdrop" (click)="stockOpen.set(false)"><section class="qr-dialog stock-dialog" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" aria-labelledby="stock-title">
          <button class="modal-close" (click)="stockOpen.set(false)" aria-label="Cerrar">×</button>
          <p class="eyebrow">Disponibilidad de hoy</p><h2 id="stock-title">Productos agotados</h2>
          <p class="muted">Un producto agotado deja de poder pedirse desde el QR y desde el salón hasta que lo vuelvas a activar.</p>
          <label class="table-search stock-search"><input type="search" placeholder="Buscar producto" aria-label="Buscar producto" [ngModel]="stockSearch()" (ngModelChange)="stockSearch.set($event)"></label>
          <div class="stock-list">
            @for(product of visibleStock();track product.id){
              <label class="stock-row" [class.sold-out]="!product.available"><span><strong>{{product.name}}</strong><small>{{product.categoryName}}</small></span><span class="stock-state">{{product.available?'Disponible':'Agotado'}}</span><input type="checkbox" role="switch" [checked]="product.available" [disabled]="stockSaving()===product.id" (change)="toggleStock(product)" [attr.aria-label]="(product.available?'Marcar agotado ':'Marcar disponible ')+product.name"></label>
            } @empty {<p class="muted">No hay productos que coincidan.</p>}
          </div>
        </section></div>
      }
    </app-shell>
  `
})
export class KitchenPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly printer = inject(PrintService);
  private readonly toast = inject(ToastService);
  private readonly sound = inject(SoundService);
  readonly realtime = inject(RealtimeService);
  readonly voiding = signal<OrderItemView | null>(null);
  readonly voidBusy = signal(false);
  readonly stockOpen = signal(false);
  readonly stock = signal<MenuItemView[]>([]);
  readonly stockSearch = signal('');
  readonly stockSaving = signal<string | null>(null);
  readonly stations = signal<{id:string;name:string}[]>([]);
  /** Cada pantalla recuerda su estación (por ejemplo, la tablet de la barra solo ve bebidas). */
  readonly station = signal(this.savedStation());
  readonly soldOutCount = computed(() => this.stock().filter(product => !product.available).length);
  readonly visibleStock = computed(() => { const term = this.stockSearch().trim().toLocaleLowerCase(); return this.stock().filter(product => !term || product.name.toLocaleLowerCase().includes(term)); });
  /** Rondas ya vistas: solo las nuevas generan comanda, no las que existían al abrir la pantalla. */
  private knownOrders: Set<string> | null = null;
  /** Rondas recién llegadas: entran con animación y un distintivo de «nuevo» durante unos segundos. */
  readonly freshIds = signal<Set<string>>(new Set());
  private ownVoidUntil = 0;
  private unwatch?: () => void;
  private stopResync?: () => void;
  readonly orders = signal<KitchenOrder[]>([]);
  readonly error = signal('');
  readonly activeColumn = signal('SUBMITTED');
  readonly updatingIds = signal<Set<string>>(new Set());
  readonly clock = signal(Date.now());
  private clockTimer?: number;
  readonly columns = computed(() => [
    {status:'SUBMITTED', title:'Nuevos', icon:'▣', tone:'new', orders:this.byPriority(this.withStatus('SUBMITTED', this.station()), 'SUBMITTED')},
    {status:'PREPARING', title:'Preparando', icon:'♨', tone:'preparing', orders:this.byPriority(this.withStatus('PREPARING', this.station()), 'PREPARING')},
    {status:'READY', title:'Listos', icon:'✓', tone:'ready', orders:this.withStatus('READY', this.station())}
  ]);

  /** Estado de las estaciones y alertas proactivas (demoras e insumos que se acaban). */
  readonly board = signal<StationBoard | null>(null);
  readonly dismissed = signal<Set<string>>(new Set());
  readonly visibleAlerts = computed(() => (this.board()?.alerts || []).filter(alert => !this.dismissed().has(alert.key)));
  private boardTimer?: number;
  private boardRefresh?: number;
  readonly alertsOpen = signal(false);
  /** El desplegable de avisos se cierra al tocar fuera de él. */
  @HostListener('document:click', ['$event'])
  closeAlerts(event: MouseEvent): void {
    if (this.alertsOpen() && !(event.target as Element | null)?.closest?.('.kx-alerts')) this.alertsOpen.set(false);
  }
  /** Pestañas de estación con su cola y espera promedio. */
  readonly stationTabs = computed(() => this.stations().map(item => {
    const info = this.board()?.stations.find(value => value.id === item.id);
    return { id: item.id, name: item.name, pending: info?.pending ?? 0, waitAverage: info ? info.waitAverage : null, status: info?.status ?? 'OK' };
  }));
  readonly totalPending = computed(() => (this.board()?.stations || []).reduce((sum, item) => sum + item.pending, 0));
  readonly alertTone = computed(() => this.visibleAlerts().some(alert => !alert.key.startsWith('STOCK')) ? 'late' : 'stock');
  /** Resumen de una línea: «2 estaciones atrasadas · 5 insumos por acabarse». */
  readonly alertSummary = computed(() => {
    const alerts = this.visibleAlerts();
    const stock = alerts.filter(alert => alert.key.startsWith('STOCK')).length;
    const late = alerts.length - stock;
    return [late ? `${late} ${late === 1 ? 'estación atrasada' : 'estaciones atrasadas'}` : '', stock ? `${stock} ${stock === 1 ? 'insumo por acabarse' : 'insumos por acabarse'}` : ''].filter(Boolean).join(' · ');
  });
  dismissAlert(key: string): void { this.dismissed.update(current => new Set([...current, key])); }
  private loadBoard(): void { this.api.get<StationBoard>('/staff/ops/stations').subscribe({ next: value => this.board.set(value), error: () => undefined }); }

  /**
   * Prioridad automática: minutos de espera, más un empuje si la misma mesa ya tiene platos listos
   * esperando (conviene sacarlos juntos) y para pedidos para llevar o delivery.
   */
  priority(order: KitchenOrder): number {
    const minutes = Math.max(0, (this.clock() - new Date(order.submittedAt).getTime()) / 60000);
    const waiting = this.sessionItems(order).some(item => item.status === 'READY' && !order.items.includes(item));
    return minutes + (waiting ? 8 : 0) + (order.channel !== 'DINE_IN' ? 3 : 0);
  }
  byPriority(orders: KitchenOrder[], status: string): KitchenOrder[] {
    void status;
    return [...orders].sort((a, b) => this.priority(b) - this.priority(a));
  }
  /** La primera de la columna es prioritaria si ya pasó de 10 minutos o tiene platos de su mesa esperando. */
  isPriority(order: KitchenOrder, status: string): boolean {
    const column = this.columns().find(item => item.status === status);
    return column?.orders[0]?.id === order.id && column.orders.length > 1 && this.priority(order) >= 10;
  }
  private sessionItems(order: KitchenOrder): OrderItemView[] {
    return this.orders().filter(item => item.sessionId === order.sessionId).flatMap(item => item.items).filter(item => item.status !== 'CANCELLED');
  }
  /** Coordina la salida: platos de la misma mesa que conviene servir juntos. */
  syncHint(order: KitchenOrder, status: string): string | null {
    const others = this.sessionItems(order).filter(item => !order.items.includes(item));
    const own = order.items.filter(item => item.status === status);
    const count = (list: OrderItemView[]) => list.reduce((sum, item) => sum + item.quantity, 0);
    if (status === 'READY') {
      const pending = [...others, ...order.items].filter(item => item.status === 'SUBMITTED' || item.status === 'PREPARING');
      if (!pending.length) return null;
      return `⏳ Falta ${pending.map(item => item.quantity + '× ' + item.name).slice(0, 2).join(', ')}${pending.length > 2 ? '…' : ''} de ${order.tableLabel}: sírvanse juntos.`;
    }
    const ready = others.filter(item => item.status === 'READY');
    if (ready.length) return `🔗 ${order.tableLabel} ya tiene ${count(ready)} ${count(ready) === 1 ? 'plato listo' : 'platos listos'} esperando: saca este pronto para servir todo junto.`;
    const parallel = others.filter(item => item.status === 'PREPARING' && item.stationId !== own[0]?.stationId);
    if (status === 'SUBMITTED' && parallel.length) return `🔗 Otra estación ya prepara lo de ${order.tableLabel}: empieza ahora para que salga a la par.`;
    return null;
  }

  ngOnInit(): void {
    this.loadBoard();
    this.boardTimer = window.setInterval(() => this.loadBoard(), 30000);
    this.load(); this.realtime.connectStaff(this.auth.token()!);
    // Al recuperar la conexión (desbloqueo, internet de vuelta) se recarga lo que pudo cambiar.
    this.stopResync = this.realtime.onResync(() => { this.load(); this.loadBoard(); this.loadStock(); });
    this.clockTimer=window.setInterval(()=>this.clock.set(Date.now()),30000);
    const branch = this.auth.branchId();
    if (branch) this.unwatch = this.realtime.watch(`/topic/branches/${branch}/kitchen`, event => this.onEvent(event));
    this.loadStock();
    this.api.get<{id:string;name:string}[]>('/staff/menu/stations').subscribe({ next: value => {
      this.stations.set(value);
      // Si la estación guardada ya no existe, la pantalla vuelve a mostrar todo.
      if (this.station() && !value.some(item => item.id === this.station())) this.selectStation('');
    }, error: () => undefined });
  }
  load(): void { this.api.get<KitchenOrder[]>('/kitchen/orders').subscribe({next: value => { this.printNewOrders(value); this.orders.set(value); }, error: err => this.error.set(err.error?.message || 'No se pudieron cargar las órdenes.')}); }
  private onEvent(event: RealtimeEvent): void {
    window.clearTimeout(this.boardRefresh);
    this.boardRefresh = window.setTimeout(() => this.loadBoard(), 1500);
    if (event.type === 'MENU_AVAILABILITY_CHANGED') { this.loadStock(); return; }
    // La anulación hecha desde esta misma pantalla ya mostró su propio aviso.
    if (event.type === 'ORDER_ITEM_CANCELLED' && Date.now() > this.ownVoidUntil) this.toast.show('Plato anulado', 'Se retiró un producto de la cola de cocina.', 'warning', '⊘');
    this.load();
  }
  private printNewOrders(orders: KitchenOrder[]): void {
    const ids = new Set(orders.map(order => order.id));
    if (this.knownOrders) {
      const arrived = orders.filter(order => !this.knownOrders!.has(order.id) && order.status === 'SUBMITTED' && order.items.some(item => this.inStation(item)));
      arrived.forEach(order => this.printer.print('COMANDA', `${order.tableLabel} · Ronda ${order.roundNumber}`));
      if (arrived.length) {
        const fresh = arrived.map(order => order.id);
        this.sound.play('order');
        this.freshIds.update(current => new Set([...current, ...fresh]));
        window.setTimeout(() => this.freshIds.update(current => new Set([...current].filter(id => !fresh.includes(id)))), 6000);
      }
    }
    this.knownOrders = ids;
  }
  isManager(): boolean { return ['BRANCH_MANAGER', 'ORGANIZATION_ADMIN'].includes(this.auth.role()); }
  canVoid(item: OrderItemView): boolean { return item.status === 'SUBMITTED' || (this.isManager() && item.status !== 'CANCELLED'); }
  confirmVoid(request: VoidRequest): void {
    const item = this.voiding(); if (!item || this.voidBusy()) return;
    this.voidBusy.set(true);
    this.api.post(`/staff/order-items/${item.id}/void`, request).subscribe({
      next: () => { this.ownVoidUntil = Date.now() + 3000; this.voidBusy.set(false); this.voiding.set(null); this.toast.show('Plato anulado', `${request.quantity}× ${item.name} salió de la cola.`, 'success', '✓'); this.load(); },
      error: err => { this.voidBusy.set(false); this.voiding.set(null); this.error.set(err.error?.message || 'No se pudo anular el producto.'); this.load(); }
    });
  }
  openStock(): void { this.stockOpen.set(true); this.stockSearch.set(''); this.loadStock(); }
  private loadStock(): void { this.api.get<MenuItemView[]>('/staff/menu').subscribe({ next: value => this.stock.set(value), error: () => undefined }); }
  toggleStock(product: MenuItemView): void {
    this.stockSaving.set(product.id);
    this.api.put(`/staff/menu/items/${product.id}/availability`, { available: !product.available }).subscribe({
      next: () => { this.stockSaving.set(null); this.stock.update(list => list.map(value => value.id === product.id ? { ...value, available: !product.available } : value)); },
      error: err => { this.stockSaving.set(null); this.error.set(err.error?.message || 'No se pudo cambiar la disponibilidad.'); this.loadStock(); }
    });
  }
  withStatus(status: string, station = this.station()): KitchenOrder[] { return this.orders().filter(order => order.items.some(item => item.status === status && this.inStation(item, station))); }
  itemsFor(order: KitchenOrder, status: string): OrderItemView[] { return order.items.filter(item => item.status === status && this.inStation(item)); }
  itemCount(status:string):number{return this.orders().flatMap(order=>order.items).filter(item=>item.status===status&&this.inStation(item)).reduce((sum,item)=>sum+item.quantity,0);}
  selectStation(id: string): void { this.station.set(id); try { localStorage.setItem('restos_kitchen_station', id); } catch { /* sin almacenamiento */ } }
  private inStation(item: OrderItemView, station = this.station()): boolean { return !station || item.stationId === station; }
  private savedStation(): string { try { return localStorage.getItem('restos_kitchen_station') || ''; } catch { return ''; } }
  updating(itemId:string):boolean{return this.updatingIds().has(itemId);}
  change(item: OrderItemView, status: string): void {
    if(this.updating(item.id))return;
    this.error.set('');
    this.updatingIds.update(current=>new Set([...current,item.id]));
    this.api.patch(`/staff/order-items/${item.id}/status`, {status}).subscribe({
      next: () => {this.updatingIds.update(current=>new Set([...current].filter(id=>id!==item.id)));this.load();},
      error: err => {this.updatingIds.update(current=>new Set([...current].filter(id=>id!==item.id)));this.error.set(err.status===409?'El pedido cambió en otro dispositivo. La cola fue actualizada.':err.error?.message || 'No se pudo cambiar el estado.');this.load();}
    });
  }
  elapsed(value: string): string { const minutes = Math.max(0, Math.floor((this.clock() - new Date(value).getTime()) / 60000)); return minutes<1?'Ahora':`${minutes} min`; }
  ageTone(value:string):string{const minutes=Math.max(0,Math.floor((this.clock()-new Date(value).getTime())/60000));return minutes>=20?'late':minutes>=10?'attention':'on-time';}
  ngOnDestroy(): void { this.stopResync?.(); if (this.boardTimer) window.clearInterval(this.boardTimer); window.clearTimeout(this.boardRefresh); this.unwatch?.();if(this.clockTimer)window.clearInterval(this.clockTimer); }
}
