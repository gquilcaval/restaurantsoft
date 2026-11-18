import { CurrencyPipe } from '@angular/common';
import {
  Component,
  computed,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import {
  AvailableTableView,
  GuestSession,
  MenuItemView,
  OrderItemView,
  OrderSnapshot,
  PaymentSummary,
  PublicTransferStatus,
  ServiceRequestView,
  TableTransferView,
} from '../../core/api/models';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ThemeService } from '../../core/theme/theme.service';
import { ThemeToggleComponent } from '../../shared/ui/theme-toggle.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { DinerProductSheetComponent, DishSelection } from './diner-product-sheet.component';
import { DinerTrackerComponent } from './diner-tracker.component';
import { BillChoice, DinerBillSheetComponent } from './diner-bill-sheet.component';
import { DinerRatingComponent } from './diner-rating.component';
import { MENU_TAGS, tagMeta } from '../../shared/ordering/menu-tags';

interface TableRelocation {
  sessionId: string;
  tableToken: string;
  qrPublicId: string;
  tableLabel: string;
}

/** Experiencia QR real: une al comensal, sincroniza el carrito y controla las rondas de la mesa. */
@Component({
  imports: [ThemeToggleComponent, 
    CurrencyPipe,
    FormsModule,
    DinerProductSheetComponent,
    DinerTrackerComponent,
    DinerBillSheetComponent,
    DinerRatingComponent,
  ],
  templateUrl: './diner.page.html',
  // Sin encapsular: son los estilos que antes vivían en la hoja global, ahora fuera del paquete inicial.
  encapsulation: ViewEncapsulation.None,
  styleUrls: [
    './diner-a-1.scss',
    './diner-a-2.scss',
    './diner-a-3.scss',
    './diner-a-4.scss',
    './diner-a-5.scss',
    './diner-a-6.scss',
    './diner-a-7.scss',
    './diner-a-8.scss',
    './diner-a-9.scss',
    './diner-a-10.scss',
    './diner-b-1.scss',
    './diner-extras.scss',
    './diner-glass.scss',
    './diner-themes.scss',
    './diner-menu-v2.scss',
    './diner-people.scss',
    './diner-rejoin.scss',
    './diner-help.scss',
    './diner-help-cta.scss',
    './diner-call.scss',
    './diner-top-compact.scss',
    './diner-cart-sheet.scss',
    './diner-cart-sheet-b.scss',
    './diner-cart-groups.scss',
  ],
})
export class DinerPage implements OnInit, OnDestroy {
  readonly theme = inject(ThemeService);
  readonly realtime = inject(RealtimeService);
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  private unwatch?: () => void;
  private transferPoll?: number;
  private cartFlightTimer?: number;
  private cartCountTimer?: number;
  private removeAnimationTimer?: number;
  readonly guest = signal<GuestSession | null>(null);
  readonly menu = signal<MenuItemView[]>([]);
  readonly snapshot = signal<OrderSnapshot | null>(null);
  readonly activeCategory = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly reviewBlocked = signal(false);
  readonly transferCandidate = signal<GuestSession | null>(null);
  readonly transferRequest = signal<TableTransferView | null>(null);
  readonly tableChangeOpen = signal(false);
  readonly availableTables = signal<AvailableTableView[]>([]);
  readonly selectedTransferTable = signal<AvailableTableView | null>(null);
  readonly exitBlockedOpen = signal(false);
  readonly sessionCompleted = signal(false);
  readonly experienceStarted = signal(false);
  /** Se muestra una sola vez, al unirse a la mesa. */
  private welcomePending = false;
  readonly pageLoading = signal(true);
  readonly activeView = signal<'menu' | 'order'>('menu');
  readonly quickActionsOpen = signal(false);
  // ——— Llamar al mesero: motivo opcional y confirmación «¿Ya te atendieron?» ———
  readonly helpStep = signal<'menu' | 'reason'>('menu');
  readonly callReason = signal<string | null>(null);
  readonly callReasons = [
    { value: 'CUTLERY', icon: '🍴', label: 'Cubiertos o servilletas' },
    { value: 'DRINK', icon: '🥤', label: 'Pedir algo más' },
    { value: 'QUESTION', icon: '💬', label: 'Una consulta' },
    { value: 'PROBLEM', icon: '⚠️', label: 'Algo salió mal' },
  ];
  private readonly askTick = signal(Date.now());
  private askTimer = 0;
  readonly answering = signal(false);
  /** Llamados ya respondidos (id + momento en que el mesero lo tomó), para no volver a preguntar. */
  private readonly answered = signal<string[]>([]);
  /** A los 2 minutos de «Voy», se le pregunta a la mesa si ya la atendieron. */
  readonly askAttended = computed(() => {
    const request = this.waiterRequest();
    if (!request || request.status !== 'IN_PROGRESS' || !request.attendedAt) return null;
    if (this.answered().includes(request.id + request.attendedAt)) return null;
    return this.askTick() - new Date(request.attendedAt).getTime() >= 120000 ? request : null;
  });
  answerAttended(request: ServiceRequestView, attended: boolean): void {
    const session = this.guest();
    if (!session || this.answering()) return;
    this.answering.set(true);
    this.answered.update((list) => [...list, request.id + request.attendedAt]);
    this.api.post<ServiceRequestView>(`/public/sessions/${session.sessionId}/service-requests/${request.id}/feedback`, { attended }, session.tableToken, session.guestToken)
      .subscribe({
        next: (value) => {
          this.answering.set(false);
          if (attended) { this.waiterRequest.set(null); this.toast.show('¡Gracias!', 'Si necesitas algo más, toca Ayuda.', 'success', '✓', 2500); }
          else { this.waiterRequest.set(value); this.toast.show(`Le avisamos de nuevo a ${request.waiterName?.split(' ')[0] || 'tu mesero'}`, 'Si no llega pronto, avisamos a todo el equipo.', 'info', '🔔'); }
        },
        error: () => { this.answering.set(false); this.waiterRequest.set(null); },
      });
  }
  readonly flyingProduct = signal<{
    imageUrl: string | null;
    startX: number;
    startY: number;
    midX: number;
    midY: number;
    endX: number;
    endY: number;
  } | null>(null);
  readonly cartCountAnimating = signal(false);
  readonly removingItemId = signal<string | null>(null);
  readonly menuLayout = signal<'grid' | 'list'>('grid');
  readonly menuLimit = signal(12);
  readonly billRequest = signal<ServiceRequestView | null>(null);
  readonly waiterRequest = signal<ServiceRequestView | null>(null);
  readonly requestingService = signal<'WAITER_CALL' | 'BILL' | null>(null);
  readonly selectedDish = signal<MenuItemView | null>(null);
  readonly editingItem = signal<OrderItemView | null>(null);
  readonly brandName = signal('RestOS');
  readonly logoUrl = signal<string | null>(null);
  readonly tableLabel = signal('');
  /** Nombre repetido en la mesa: se pregunta «¿eres tú?» antes de crear otro comensal. */
  readonly sameName = signal('');
  /** Mesero a cargo: viene con los pedidos y se actualiza al instante si lo cambian. */
  private readonly waiterOverride = signal<string | null | undefined>(undefined);
  readonly waiterName = computed(() => {
    const override = this.waiterOverride();
    return override !== undefined ? override : this.snapshot()?.waiterName || null;
  });
  /** Estilo visual de la carta elegido por el restaurante (classic, modern, fresh, warm). */
  readonly menuTheme = signal('classic');
  displayName = '';
  search = '';
  qrPublicId = '';
  readonly categories = computed(() => [...new Set(this.menu().map((item) => item.categoryName))]);
  /** Filtro rápido por etiqueta (🌱 Vegano, 🌶 Picante…). */
  readonly tagFilter = signal('');
  // ——— Carta con más espacio para los platos ———
  readonly searchOpen = signal(false);
  readonly filtersOpen = signal(false);
  /** Al bajar por la carta se esconde la barra superior y quedan solo las categorías. */
  readonly menuCompact = signal(false);
  private lastMenuScroll = 0;
  private menuToggleLock = 0;
  private loadingMore = false;
  /** Solo filtros de dieta: lo promocional (popular, nuevo, del chef) ya va como insignia en cada plato. */
  readonly dietTags = computed(() => {
    const menu = this.menu();
    return MENU_TAGS.filter((tag) => ['VEGAN', 'VEGETARIAN', 'SPICY', 'GLUTEN_FREE'].includes(tag.value) && menu.some((item) => item.tags?.includes(tag.value)));
  });
  readonly billSheetOpen = signal(false);
  /** Visita recién terminada: permite calificar la atención después de cerrar la mesa. */
  readonly lastVisit = signal<{ sessionId: string; guestToken: string } | null>(null);
  readonly availableTags = computed(() => {
    const menu = this.menu();
    const tags = MENU_TAGS.filter((tag) => menu.some((item) => item.tags?.includes(tag.value)));
    return menu.some((item) => item.popular)
      ? [{ value: 'POPULAR', icon: '🔥', label: 'Popular' }, ...tags]
      : tags;
  });
  /** «Lo más pedido hoy»: los platos con más pedidos del día (o los populares de la semana). */
  readonly topToday = computed(() => {
    const available = this.menu().filter((item) => item.available);
    const today = available
      .filter((item) => item.soldToday > 0)
      .sort((a, b) => b.soldToday - a.soldToday);
    const list =
      today.length >= 3
        ? today
        : [...today, ...available.filter((item) => item.popular && !today.includes(item))];
    return { today: today.length >= 3, items: list.slice(0, 6) };
  });
  readonly filteredDishes = computed(() =>
    this.menu().filter(
      (item) =>
        (!this.activeCategory() || item.categoryName === this.activeCategory()) &&
        (!this.tagFilter() ||
          (this.tagFilter() === 'POPULAR'
            ? item.popular
            : item.tags?.includes(this.tagFilter()))) &&
        (!this.search.trim() || item.name.toLowerCase().includes(this.search.trim().toLowerCase())),
    ),
  );
  readonly visibleDishes = computed(() => this.filteredDishes().slice(0, this.menuLimit()));
  readonly draftCount = computed(
    () =>
      this.snapshot()
        ?.items.filter((item) => item.status === 'DRAFT')
        .reduce((sum, item) => sum + item.quantity, 0) || 0,
  );
  readonly draftTotal = computed(
    () =>
      this.snapshot()
        ?.items.filter((item) => item.status === 'DRAFT')
        .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0) || 0,
  );
  readonly total = computed(
    () =>
      this.snapshot()
        ?.items.filter((item) => item.orderStatus !== 'DRAFT')
        .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0) || 0,
  );
  readonly cartItems = computed(
    () =>
      this.snapshot()?.items.filter(
        (item) => item.status !== 'CANCELLED' && item.orderStatus !== 'CANCELLED',
      ) || [],
  );
  readonly cartCount = computed(() =>
    this.cartItems().reduce((sum, item) => sum + item.quantity, 0),
  );
  readonly cartTotal = computed(() =>
    this.cartItems().reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
  );
  readonly orderGuests = computed(
    () =>
      this.snapshot()?.guests.filter((person) =>
        this.cartItems().some((item) => item.guestId === person.id),
      ) || [],
  );
  /** Platos que el mesero registró desde el salón para toda la mesa. */
  readonly staffItems = computed(() => this.cartItems().filter((item) => !item.guestId));
  readonly staffTotal = computed(() =>
    this.staffItems().reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
  );
  readonly peopleOpen = signal(false);
  /** Conectados en la mesa, con quien mira primero. */
  readonly tableGuests = computed(() => {
    const me = this.guest()?.guestId;
    return [...this.activeGuests()].sort((a, b) => Number(b.id === me) - Number(a.id === me));
  });
  /** Para avisar cuando alguien se une o se va (no en la primera carga). */
  private knownGuests: Map<string, string> | null = null;
  readonly activeGuests = computed(
    () => this.snapshot()?.guests.filter((person) => person.active) || [],
  );
  readonly lastGuestWithConsumption = computed(
    () =>
      this.activeGuests().length === 1 &&
      (this.snapshot()?.items.some(
        (item) => item.orderStatus !== 'DRAFT' && item.orderStatus !== 'CANCELLED',
      ) ||
        false),
  );
  readonly checkoutInProgress = computed(() => this.billRequest()?.status === 'IN_PROGRESS');

  ngOnInit(): void {
    this.stopResync = this.realtime.onResync(() => this.catchUp());
    this.askTimer = window.setInterval(() => this.askTick.set(Date.now()), 10000);
    this.menuLayout.set(
      localStorage.getItem('restos_diner_menu_layout') === 'list' ? 'list' : 'grid',
    );
    this.qrPublicId = this.route.snapshot.queryParamMap.get('qr') || '';
    if (this.qrPublicId)
      this.api
        .get<{
          name: string;
          logoUrl: string | null;
          primaryColor: string;
          defaultTheme: string;
          tableLabel: string;
          menuTheme?: string;
        }>(`/public/tables/${this.qrPublicId}/branding`)
        .subscribe((brand) => {
          this.brandName.set(brand.name);
          this.logoUrl.set(brand.logoUrl);
          this.tableLabel.set(brand.tableLabel);
          this.menuTheme.set((brand.menuTheme || 'CLASSIC').toLowerCase());
          // Los estilos moderno, fresco y cálido definen su propia paleta clara u oscura.
          const fixed =
            brand.menuTheme === 'MODERN'
              ? 'DARK'
              : brand.menuTheme === 'FRESH' || brand.menuTheme === 'WARM'
                ? 'LIGHT'
                : brand.defaultTheme;
          this.theme.applyBrand(brand.primaryColor, fixed);
          const current = this.guest();
          if (current) this.saveGuest({ ...current, tableLabel: brand.tableLabel });
        });
    const key = `restos_guest_${this.qrPublicId}`;
    // localStorage permite recuperar la identidad al cerrar y volver a abrir el navegador.
    const saved = localStorage.getItem(key) || sessionStorage.getItem(key);
    const activeSaved = localStorage.getItem('restos_active_guest');
    if (saved) {
      const session = JSON.parse(saved) as GuestSession;
      // El nombre de la mesa guardado sirve si la marca del local tarda o falla en cargar.
      if (session.tableLabel && !this.tableLabel()) this.tableLabel.set(session.tableLabel);
      this.experienceStarted.set(true);
      this.saveGuest(session);
      sessionStorage.removeItem(key);
      // Las sesiones previas a la identidad individual se registran nuevamente una sola vez.
      if (session.guestToken) {
        this.guest.set(session);
        this.startSession();
      } else this.claimLegacySession(session);
    } else if (activeSaved) {
      const active = JSON.parse(activeSaved) as GuestSession;
      if (active.qrPublicId !== this.qrPublicId) {
        this.transferCandidate.set(active);
        const pending = localStorage.getItem('restos_pending_transfer');
        if (pending) {
          const request = JSON.parse(pending) as TableTransferView & {
            destinationQrPublicId?: string;
          };
          if (request.destinationQrPublicId === this.qrPublicId) {
            this.transferRequest.set(request);
            this.startTransferPolling();
          }
        }
      }
    }
  }

  private claimLegacySession(session: GuestSession): void {
    this.loading.set(true);
    this.api
      .post<{ guestToken: string }>(
        `/public/sessions/${session.sessionId}/guests/${session.guestId}/claim`,
        {},
        session.tableToken,
      )
      .subscribe({
        next: (identity) => {
          const upgraded = { ...session, guestToken: identity.guestToken };
          this.saveGuest(upgraded);
          this.experienceStarted.set(true);
          this.guest.set(upgraded);
          this.loading.set(false);
          this.startSession();
        },
        error: () => {
          this.clearSavedGuest();
          this.loading.set(false);
        },
      });
  }

  /** mode: RECLAIM («soy yo») recupera la identidad de quien tiene el mismo nombre; NEW crea otra persona. */
  join(mode?: 'RECLAIM' | 'NEW'): void {
    if (!this.displayName.trim()) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .post<Omit<GuestSession, 'qrPublicId'>>(`/public/tables/${this.qrPublicId}/join`, {
        displayName: this.displayName,
        mode: mode || null,
      })
      .subscribe({
        next: (response) => {
          const session = {
            ...response,
            qrPublicId: this.qrPublicId,
            tableLabel: this.tableLabel(),
          };
          this.sameName.set('');
          this.saveGuest(session);
          // Del nombre directo al menú: la bienvenida va en un aviso breve cuando llega la mesa.
          this.experienceStarted.set(true);
          this.activeView.set('menu');
          this.welcomePending = true;
          this.guest.set(session);
          this.loading.set(false);
          this.startSession();
        },
        error: (err) => {
          this.loading.set(false);
          // Mismo nombre que alguien de la mesa: se pregunta si es la misma persona.
          if (err.status === 409 && err.error?.code === 'SAME_NAME') { this.sameName.set(this.displayName.trim()); return; }
          this.error.set(err.status === 0 ? 'Sin conexión. Revisa tu internet e inténtalo otra vez.'
            : err.error?.message || 'No se pudo iniciar la atención con este QR.');
        },
      });
  }

  private startSession(): void {
    const guest = this.guest()!;
    this.loadAll();
    this.restorePendingTransfer(guest);
    this.realtime.connectTable(guest.tableToken);
    this.unwatch = this.realtime.watch<OrderSnapshot>(
      `/topic/table-sessions/${guest.sessionId}`,
      (event) => {
        if (event.type === 'MENU_AVAILABILITY_CHANGED')
          this.onAvailabilityChanged(
            event.payload as unknown as { menuItemId: string; name: string; available: boolean },
          );
        if (event.type === 'ORDER_ITEM_CANCELLED')
          this.toast.show(
            'Producto anulado',
            'El restaurante retiró un producto de la cuenta de la mesa.',
            'info',
            '⊘',
          );
        if (
          event.payload &&
          [
            'TABLE_CART_UPDATED',
            'ORDER_SUBMITTED',
            'ORDER_ITEM_PREPARING',
            'ORDER_ITEM_READY',
            'ORDER_ITEM_DELIVERED',
            'ORDER_ITEM_CANCELLED',
          ].includes(event.type)
        ) {
          if (event.type.startsWith('ORDER_ITEM_'))
            this.notifyOrderStatus(event.type, event.payload);
          this.acceptSnapshot(event.payload);
        }
        if (event.type === 'SERVICE_REQUEST_IN_PROGRESS') this.announceComing(event.payload as unknown as ServiceRequestView);
        if (event.type === 'SERVICE_REQUEST_CREATED' && (event.payload as unknown as ServiceRequestView).escalation === 2)
          this.toast.show('Avisamos a todo el equipo', 'El primer mesero libre irá a tu mesa.', 'info', '🔔');
        if (
          event.type === 'SERVICE_REQUEST_CREATED' ||
          event.type === 'SERVICE_REQUEST_IN_PROGRESS'
        )
          this.setServiceRequest(event.payload as unknown as ServiceRequestView);
        if (event.type === 'SERVICE_REQUEST_RESOLVED')
          this.clearServiceRequest(event.payload as unknown as ServiceRequestView);
        if (event.type === 'BILL_CANCELLED') {
          this.clearServiceRequest(event.payload as unknown as ServiceRequestView);
          this.toast.show(
            'Cuenta cancelada',
            'El consumo está abierto nuevamente. Ya puedes agregar o enviar pedidos.',
            'info',
            '↺',
          );
        }
        if (event.type === 'PAYMENT_REGISTERED') {
          const payment = event.payload as unknown as PaymentSummary;
          this.toast.show(
            payment.remaining === 0 ? 'Pago completo' : 'Pago registrado',
            payment.remaining === 0
              ? 'La cuenta está pagada. El mesero cerrará la atención.'
              : `Saldo pendiente: S/ ${Number(payment.remaining).toFixed(2)}.`,
            'success',
            '✓',
          );
        }
        if (event.type === 'TABLE_SESSION_CLOSED') this.completeSession();
        if (event.type === 'TABLE_SESSION_CANCELLED')
          this.resetExpiredSession('La atención fue cerrada por el restaurante.');
        if (event.type === 'TABLE_SESSION_REVIEW_REQUIRED') this.reviewBlocked.set(true);
        if (event.type === 'TABLE_SESSION_REACTIVATED') {
          this.reviewBlocked.set(false);
          this.toast.show(
            'Atención reactivada',
            'Ya puedes continuar usando la mesa.',
            'success',
            '✓',
          );
          this.loadAll();
        }
        if (event.type === 'TABLE_GUEST_JOINED') this.loadSnapshot();
        if (event.type === 'TABLE_WAITER_CHANGED') {
          const name = (event.payload as unknown as { waiterName?: string | null }).waiterName || null;
          const before = this.waiterName();
          if (before && name && before !== name) this.toast.show('Cambio de mesero', `Ahora te atiende ${name}.`, 'info', '🙂');
          this.waiterOverride.set(name);
        }
        if (event.type === 'TABLE_GUEST_LEFT') this.loadSnapshot();
        if (event.type === 'HOST_TRANSFERRED') this.loadSnapshot();
        if (event.type === 'TABLE_SESSION_RELOCATED')
          this.followRelocation(event.payload as unknown as TableRelocation);
      },
    );
  }

  private loadAll(): void {
    const session = this.guest()!;
    this.pageLoading.set(true);
    this.api
      .get<MenuItemView[]>(`/public/sessions/${session.sessionId}/menu`, session.tableToken)
      .subscribe({
        next: (items) => {
          // Se abre en «Todos»: así se ven «Lo más pedido hoy» y la carta completa.
          this.menu.set(items);
        },
        error: (err) => this.handleSessionError(err),
      });
    this.api
      .get<ServiceRequestView[]>(
        `/public/sessions/${session.sessionId}/service-requests`,
        session.tableToken,
        session.guestToken,
      )
      .subscribe({
        next: (requests) => {
          this.billRequest.set(requests.find((item) => item.type === 'BILL') || null);
          this.waiterRequest.set(requests.find((item) => item.type === 'WAITER_CALL') || null);
        },
        error: (err) => this.handleSessionError(err),
      });
    this.loadSnapshot();
  }
  /** Refleja agotados al instante; si el plato abierto se agotó, se cierra el detalle. */
  private onAvailabilityChanged(change: {
    menuItemId: string;
    name: string;
    available: boolean;
  }): void {
    this.menu.update((items) =>
      items.map((item) =>
        item.id === change.menuItemId ? { ...item, available: change.available } : item,
      ),
    );
    if (!change.available && this.selectedDish()?.id === change.menuItemId) {
      this.closeCustomization();
      this.toast.show('Producto agotado', `${change.name} se acaba de agotar.`, 'warning', '⊘');
    }
  }
  private loadSnapshot(): void {
    const session = this.guest()!;
    this.api
      .get<OrderSnapshot>(`/public/sessions/${session.sessionId}/orders`, session.tableToken)
      .subscribe({
        next: (value) => {
          this.acceptSnapshot(value);
          this.pageLoading.set(false);
        },
        error: (err) => {
          this.pageLoading.set(false);
          this.handleSessionError(err);
        },
      });
  }
  retrySession(): void {
    this.reviewBlocked.set(false);
    const session = this.guest();
    if (session) this.loadAll();
  }
  startExperience(): void {
    this.experienceStarted.set(true);
    this.activeView.set('menu');
  }
  setView(view: 'menu' | 'order'): void {
    this.quickActionsOpen.set(false);
    // El pedido ahora es una hoja inferior sobre la carta.
    if (view === 'order') { this.openCart(); return; }
    this.activeView.set(view);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  selectCategory(category: string): void {
    this.activeCategory.set(category);
    this.menuLimit.set(12);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  searchChanged(value: string): void {
    this.search = value;
    this.menuLimit.set(12);
  }
  categoryCount(category: string): number {
    return this.menu().filter((item) => item.categoryName === category).length;
  }
  setMenuLayout(layout: 'grid' | 'list'): void {
    this.menuLayout.set(layout);
    localStorage.setItem('restos_diner_menu_layout', layout);
  }
  showMoreDishes(): void {
    this.menuLimit.update((value) => value + 12);
  }
  openDish(item: MenuItemView): void {
    if (this.checkoutInProgress() || !item.available) return;
    this.quickActionsOpen.set(false);
    this.selectedDish.set(item);
    this.editingItem.set(null);
  }
  editDraftItem(item: OrderItemView): void {
    this.editingItem.set(item);
    this.selectedDish.set(null);
  }
  closeCustomization(): void {
    this.selectedDish.set(null);
    this.editingItem.set(null);
  }
  saveCustomization(selection: DishSelection): void {
    const session = this.guest()!;
    const editing = this.editingItem();
    const dish = this.selectedDish();
    if (!editing && !dish) return;
    this.loading.set(true);
    const request = editing
      ? this.api.patch<OrderSnapshot>(
          `/public/sessions/${session.sessionId}/cart/items/${editing.id}`,
          { quantity: selection.quantity, notes: selection.notes },
          session.tableToken,
          session.guestToken,
        )
      : this.api.post<OrderSnapshot>(
          `/public/sessions/${session.sessionId}/cart/items`,
          {
            menuItemId: dish!.id,
            quantity: selection.quantity,
            notes: selection.notes,
            optionIds: selection.optionIds,
          },
          session.tableToken,
          session.guestToken,
        );
    request.subscribe({
      next: (value) => {
        this.acceptSnapshot(value);
        this.loading.set(false);
        if (!editing) this.animateCartAddition(dish!);
        this.closeCustomization();
      },
      error: (err) => this.fail(err),
    });
  }
  private animateCartAddition(dish: MenuItemView): void {
    const source = document
      .querySelector('.ps-hero img,.ps-hero span')
      ?.getBoundingClientRect();
    const target = document.querySelector('.diner-cart-bar>span>b')?.getBoundingClientRect();
    if (!source || !target) {
      this.animateCartCount();
      return;
    }
    if (this.cartFlightTimer) window.clearTimeout(this.cartFlightTimer);
    const startX = source.left + source.width / 2 - 27;
    const startY = source.top + source.height / 2 - 27;
    const endX = target.left + target.width / 2 - 27;
    const endY = target.top + target.height / 2 - 27;
    const direction = endX >= startX ? 1 : -1;
    this.flyingProduct.set({
      imageUrl: dish.imageUrl,
      startX,
      startY,
      midX: startX + (endX - startX) * 0.48 + direction * 34,
      midY: Math.min(startY, endY) - 72,
      endX,
      endY,
    });
    this.cartFlightTimer = window.setTimeout(() => {
      this.flyingProduct.set(null);
      this.animateCartCount();
    }, 760);
  }
  private animateCartCount(): void {
    if (this.cartCountTimer) window.clearTimeout(this.cartCountTimer);
    this.cartCountAnimating.set(false);
    window.requestAnimationFrame(() => {
      this.cartCountAnimating.set(true);
      this.cartCountTimer = window.setTimeout(() => this.cartCountAnimating.set(false), 480);
    });
  }
  changeQuantity(item: OrderItemView, delta: number): void {
    if (item.quantity + delta < 1) {
      this.remove(item.id);
      return;
    }
    const session = this.guest()!;
    this.api
      .patch<OrderSnapshot>(
        `/public/sessions/${session.sessionId}/cart/items/${item.id}`,
        { quantity: item.quantity + delta, notes: item.notes },
        session.tableToken,
        session.guestToken,
      )
      .subscribe({ next: (value) => this.acceptSnapshot(value), error: (err) => this.fail(err) });
  }
  remove(itemId: string): void {
    if (this.removingItemId()) return;
    this.removingItemId.set(itemId);
    if (this.removeAnimationTimer) window.clearTimeout(this.removeAnimationTimer);
    this.removeAnimationTimer = window.setTimeout(() => {
      const session = this.guest()!;
      this.api
        .delete<OrderSnapshot>(
          `/public/sessions/${session.sessionId}/cart/items/${itemId}`,
          session.tableToken,
          session.guestToken,
        )
        .subscribe({
          next: (value) => this.acceptRemovalWithMotion(value),
          error: (err) => {
            this.removingItemId.set(null);
            this.fail(err);
          },
        });
    }, 360);
  }
  private acceptRemovalWithMotion(value: OrderSnapshot): void {
    const previousPositions = new Map<string, number>();
    document
      .querySelectorAll<HTMLElement>('.diner-order-card-shell[data-order-item-id]')
      .forEach((element) =>
        previousPositions.set(
          element.dataset['orderItemId'] || '',
          element.getBoundingClientRect().top,
        ),
      );
    this.removingItemId.set(null);
    this.acceptSnapshot(value);
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    window.requestAnimationFrame(() => {
      document
        .querySelectorAll<HTMLElement>('.diner-order-card-shell[data-order-item-id]')
        .forEach((element) => {
          const previousTop = previousPositions.get(element.dataset['orderItemId'] || '');
          if (
            previousTop === undefined ||
            Math.abs(previousTop - element.getBoundingClientRect().top) < 1
          )
            return;
          element.animate(
            [
              { opacity: 0.35, transform: 'translate3d(24px,0,0)' },
              { opacity: 1, transform: 'translate3d(0,0,0)' },
            ],
            { duration: 520, easing: 'cubic-bezier(.22,.72,.24,1)' },
          );
        });
    });
  }
  submitRound(): void {
    const session = this.guest()!;
    if (this.loading()) return;
    this.loading.set(true);
    this.api
      .post<OrderSnapshot>(
        `/public/sessions/${session.sessionId}/orders/submit`,
        {},
        session.tableToken,
        session.guestToken,
      )
      .subscribe({
        next: (value) => {
          this.loading.set(false);
          this.acceptSnapshot(value);
          // Enviado: se cierra el carrito y el comensal vuelve a la carta; el aviso confirma el envío.
          this.closeCart();
          this.activeView.set('menu');
          this.toast.show('Pedido enviado', 'Tu pedido ya fue enviado a cocina.', 'success', '✓');
        },
        error: (err) => this.fail(err),
      });
  }
  requestService(type: 'WAITER_CALL' | 'BILL', reason: string | null = null): void {
    const session = this.guest()!;
    if ((type === 'BILL' && this.billRequest()) || (type === 'WAITER_CALL' && this.waiterRequest()))
      return;
    this.requestingService.set(type);
    this.api
      .post<ServiceRequestView>(
        `/public/sessions/${session.sessionId}/service-requests`,
        { type, reason },
        session.tableToken,
        session.guestToken,
      )
      .subscribe({
        next: (request) => {
          this.requestingService.set(null);
          this.setServiceRequest(request);
          this.toast.show(
            type === 'BILL' ? 'Cuenta solicitada' : 'Mesero avisado',
            type === 'BILL'
              ? 'El mesero recibió tu solicitud de cuenta.'
              : reason === 'PROBLEM' ? 'Le avisamos con prioridad; ya viene alguien.' : 'Un mesero atenderá tu llamada.',
            'success',
            '✓',
          );
        },
        error: (err) => {
          this.requestingService.set(null);
          this.fail(err);
        },
      });
  }
  tagIcon(tag: string): string {
    return tagMeta(tag)?.icon || '';
  }
  tagLabel(tag: string): string {
    return tagMeta(tag)?.label || '';
  }
  selectTag(tag: string): void {
    this.tagFilter.update((current) => (current === tag ? '' : tag));
    this.menuLimit.set(12);
    this.filtersOpen.set(false);
  }
  toggleSearch(): void {
    if (this.searchOpen() || this.search) { this.searchChanged(''); this.searchOpen.set(false); return; }
    this.searchOpen.set(true);
    this.menuCompact.set(false);
    setTimeout(() => document.getElementById('dn-search-input')?.focus(), 30);
  }
  onMenuScroll(event: Event): void {
    const box = event.target as HTMLElement;
    const max = box.scrollHeight - box.clientHeight;
    // Carga automática: cerca del final se suman 12 platos más (con una pausa breve para la animación).
    if (max - box.scrollTop < 360 && !this.loadingMore && this.visibleDishes().length < this.filteredDishes().length) {
      this.loadingMore = true;
      setTimeout(() => { this.showMoreDishes(); this.loadingMore = false; }, 420);
    }
    // El rebote del borde (arriba o abajo) mueve scrollTop sin que el usuario cambie de dirección.
    const top = Math.max(0, Math.min(box.scrollTop, max));
    const delta = top - this.lastMenuScroll;
    this.lastMenuScroll = top;
    // Mientras la barra entra o sale, el alto cambia y genera desplazamientos falsos: se ignoran.
    if (Date.now() < this.menuToggleLock) return;
    let next = this.menuCompact();
    if (top < 24) next = false;
    else if (max - top < 48) return; // cerca del final: no alternar (evita el parpadeo del rebote)
    else if (delta > 8) next = true;
    else if (delta < -14) next = false;
    if (next !== this.menuCompact()) {
      this.menuCompact.set(next);
      this.menuToggleLock = Date.now() + 380;
    }
  }
  /** «+» en la tarjeta: sin opciones que elegir, se agrega directo; con opciones, se abre la ficha. */
  quickAdd(dish: MenuItemView): void {
    if (this.checkoutInProgress() || !dish.available || this.loading()) return;
    if (dish.modifierGroups?.length) { this.openDish(dish); return; }
    const session = this.guest();
    if (!session) return;
    this.loading.set(true);
    navigator.vibrate?.(10);
    this.api.post<OrderSnapshot>(`/public/sessions/${session.sessionId}/cart/items`,
      { menuItemId: dish.id, quantity: 1, notes: null, optionIds: [] }, session.tableToken, session.guestToken)
      .subscribe({
        next: (value) => {
          this.acceptSnapshot(value);
          this.loading.set(false);
          this.animateCartCount();
          this.toast.show('Agregado a tu pedido', `${dish.name} · revisa y envíalo desde «Pedido de la mesa».`, 'success', '✓', 2200);
        },
        error: (err) => this.fail(err),
      });
  }
  /** La cuenta se pide eligiendo cómo pagar; si ya se pidió, no se vuelve a abrir. */
  openBillSheet(): void {
    if (this.billRequest() || this.requestingService()) return;
    this.billSheetOpen.set(true);
  }
  requestBill(choice: BillChoice): void {
    const session = this.guest()!;
    this.requestingService.set('BILL');
    this.api
      .post<ServiceRequestView>(
        `/public/sessions/${session.sessionId}/service-requests`,
        { type: 'BILL', ...choice },
        session.tableToken,
        session.guestToken,
      )
      .subscribe({
        next: (request) => {
          this.requestingService.set(null);
          this.billSheetOpen.set(false);
          this.setServiceRequest(request);
          this.toast.show(
            'Cuenta solicitada',
            request.note
              ? `El mesero ya sabe: ${request.note}.`
              : 'El mesero recibió tu solicitud de cuenta.',
            'success',
            '🧾',
          );
        },
        error: (err) => {
          this.requestingService.set(null);
          this.fail(err);
        },
      });
  }
  // ——— Panel de ayuda: se cierra arrastrándolo hacia abajo ———
  readonly helpY = signal(0);
  readonly helpDragging = signal(false);
  readonly helpClosing = signal(false);
  private helpDrag: { y: number; time: number; dy: number; active: boolean } | null = null;
  openHelp(): void {
    this.helpY.set(0); this.helpClosing.set(false); this.helpStep.set('menu');
    this.quickActionsOpen.set(true);
    document.documentElement.classList.add('dn-sheet-open');
  }
  closeHelp(): void {
    if (!this.quickActionsOpen() || this.helpClosing()) return;
    this.helpClosing.set(true); this.helpDragging.set(false);
    this.helpY.set(window.innerHeight);
    setTimeout(() => {
      this.quickActionsOpen.set(false); this.helpClosing.set(false); this.helpY.set(0);
      document.documentElement.classList.remove('dn-sheet-open');
    }, 260);
  }
  helpTouchStart(event: TouchEvent): void {
    if (event.touches.length === 1) this.helpDrag = { y: event.touches[0].clientY, time: Date.now(), dy: 0, active: false };
  }
  helpTouchMove(event: TouchEvent): void {
    const drag = this.helpDrag;
    if (!drag) return;
    const dy = event.touches[0].clientY - drag.y;
    if (dy > 0) event.preventDefault();
    if (!drag.active) {
      if (dy < 6) return;
      drag.active = true; drag.y = event.touches[0].clientY; drag.time = Date.now();
      this.helpDragging.set(true);
      return;
    }
    drag.dy = Math.max(0, event.touches[0].clientY - drag.y);
    this.helpY.set(drag.dy);
  }
  helpTouchEnd(): void {
    const drag = this.helpDrag;
    this.helpDrag = null;
    if (!drag?.active) return;
    this.helpDragging.set(false);
    const speed = drag.dy / Math.max(1, Date.now() - drag.time);
    if (drag.dy > 90 || speed > 0.5) this.closeHelp(); else this.helpY.set(0);
  }
  /** Texto del botón de ayuda: «Ayuda» o el estado de lo que ya se pidió. */
  helpLabel(): string {
    if (this.waiterRequest()) return this.serviceButtonLabel('WAITER_CALL');
    if (this.billRequest()) return this.serviceButtonLabel('BILL');
    return 'Ayuda';
  }
  serviceButtonLabel(type: 'WAITER_CALL' | 'BILL'): string {
    const request = type === 'BILL' ? this.billRequest() : this.waiterRequest();
    if (this.requestingService() === type) return 'Enviando…';
    if (!request) return type === 'BILL' ? 'Pedir cuenta' : 'Llamar mesero';
    if (request.status === 'IN_PROGRESS')
      return type === 'BILL' ? 'Cuenta en atención' : `${request.waiterName?.split(' ')[0] || 'Mesero'} en camino`;
    return type === 'BILL' ? 'Cuenta solicitada' : 'Mesero avisado';
  }
  private setServiceRequest(request: ServiceRequestView): void {
    if (request.type === 'BILL') this.billRequest.set(request);
    if (request.type === 'WAITER_CALL') this.waiterRequest.set(request);
  }
  private clearServiceRequest(request: ServiceRequestView): void {
    if (request.type === 'BILL') this.billRequest.set(null);
    if (request.type === 'WAITER_CALL') this.waiterRequest.set(null);
  }
  /** Abre el selector en primer plano para que el usuario no dependa de mensajes fuera de vista. */
  openTableChange(): void {
    this.tableChangeOpen.set(true);
    this.error.set('');
    if (this.transferRequest()?.status === 'PENDING') return;
    this.transferRequest.set(null);
    this.loadAvailableTables();
  }
  closeTableChange(): void {
    this.tableChangeOpen.set(false);
    this.selectedTransferTable.set(null);
    this.error.set('');
  }
  private loadAvailableTables(): void {
    const source = this.guest();
    if (!source) return;
    this.loading.set(true);
    this.api
      .get<AvailableTableView[]>(
        `/public/sessions/${source.sessionId}/available-tables`,
        source.tableToken,
        source.guestToken,
      )
      .subscribe({
        next: (value) => {
          this.availableTables.set(value);
          this.selectedTransferTable.set(null);
          this.loading.set(false);
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(err.error?.message || 'No se pudieron consultar las mesas disponibles.');
        },
      });
  }
  requestSelectedTable(): void {
    const destination = this.selectedTransferTable();
    if (destination) this.requestTableTransfer(destination.qrPublicId);
  }
  requestTableTransfer(destinationQrPublicId = this.qrPublicId): void {
    const source = this.transferSource();
    if (!source) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .post<TableTransferView>(
        `/public/sessions/${source.sessionId}/table-transfers`,
        { destinationQrPublicId },
        source.tableToken,
        source.guestToken,
      )
      .subscribe({
        next: (value) => {
          const stored = { ...value, destinationQrPublicId };
          this.transferRequest.set(value);
          localStorage.setItem('restos_pending_transfer', JSON.stringify(stored));
          this.loading.set(false);
          this.startTransferPolling();
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(err.error?.message || 'No se pudo solicitar el cambio.');
        },
      });
  }
  cancelTableTransfer(): void {
    const request = this.transferRequest();
    const source = this.transferSource();
    if (!request || !source) return;
    this.loading.set(true);
    this.api
      .delete<TableTransferView>(
        `/public/table-transfers/${request.id}`,
        undefined,
        source.guestToken,
      )
      .subscribe({
        next: () => {
          if (this.transferPoll) window.clearInterval(this.transferPoll);
          this.transferRequest.set(null);
          localStorage.removeItem('restos_pending_transfer');
          this.loading.set(false);
          this.toast.show('Cambio cancelado', 'Permaneces en tu mesa actual.', 'info', '×');
          if (this.transferCandidate()) this.returnToCurrentTable();
          else this.loadAvailableTables();
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(err.error?.message || 'No se pudo cancelar la solicitud.');
          this.pollTransfer();
        },
      });
  }
  retryTableSelection(): void {
    this.transferRequest.set(null);
    this.error.set('');
    this.loadAvailableTables();
  }
  private transferSource(): GuestSession | null {
    return this.transferCandidate() || this.guest();
  }
  private restorePendingTransfer(source: GuestSession): void {
    const pending = localStorage.getItem('restos_pending_transfer');
    if (!pending) return;
    try {
      const request = JSON.parse(pending) as TableTransferView & { destinationQrPublicId?: string };
      if (request.sourceSessionId !== source.sessionId) return;
      this.transferRequest.set(request);
      this.startTransferPolling();
    } catch {
      localStorage.removeItem('restos_pending_transfer');
    }
  }
  private startTransferPolling(): void {
    if (this.transferPoll) window.clearInterval(this.transferPoll);
    this.pollTransfer();
    this.transferPoll = window.setInterval(() => this.pollTransfer(), 2500);
  }
  private pollTransfer(): void {
    const request = this.transferRequest();
    const source = this.transferSource();
    if (!request || !source) return;
    this.api
      .get<PublicTransferStatus>(
        `/public/table-transfers/${request.id}`,
        undefined,
        source.guestToken,
      )
      .subscribe({
        next: (status) => {
          if (status.status === 'PENDING') return;
          if (this.transferPoll) window.clearInterval(this.transferPoll);
          if (status.status === 'REJECTED') {
            this.transferRequest.set({ ...request, status: 'REJECTED', reason: status.reason });
            localStorage.removeItem('restos_pending_transfer');
            this.tableChangeOpen.set(true);
            return;
          }
          if (status.status === 'CANCELLED') {
            this.transferRequest.set(null);
            localStorage.removeItem('restos_pending_transfer');
            return;
          }
          if (status.sessionId && status.tableToken) {
            const moved: GuestSession = {
              sessionId: status.sessionId,
              guestId: status.guestId,
              tableToken: status.tableToken,
              guestToken: status.guestToken,
              displayName: status.displayName,
              host: status.host,
              qrPublicId: status.qrPublicId,
              tableLabel: status.destinationTableLabel,
            };
            localStorage.removeItem(`restos_guest_${source.qrPublicId}`);
            this.saveGuest(moved);
            localStorage.removeItem('restos_pending_transfer');
            location.assign(`/diner?qr=${status.qrPublicId}`);
          }
        },
        error: () => undefined,
      });
  }
  returnToCurrentTable(): void {
    const source = this.transferCandidate();
    if (source) location.assign(`/diner?qr=${source.qrPublicId}`);
  }
  async leaveTable(): Promise<void> {
    const session = this.guest()!;
    const ownDrafts = this.snapshot()?.items.some(
      (item) => item.guestId === session.guestId && item.status === 'DRAFT',
    );
    if (ownDrafts) {
      this.toast.show(
        'Productos pendientes',
        'Quita tus productos en selección antes de salir de la mesa.',
        'warning',
        '!',
      );
      return;
    }
    if (this.lastGuestWithConsumption()) {
      this.exitBlockedOpen.set(true);
      return;
    }
    const accepted = await this.dialog.confirm({
      title: 'Salir de la atención',
      message: `Dejarás de participar en ${this.tableLabel()}. Tus pedidos enviados permanecerán en la cuenta de esta mesa.`,
      confirmLabel: 'Salir de la mesa',
      tone: 'warning',
      icon: '↗',
    });
    if (!accepted) return;
    this.loading.set(true);
    this.api
      .post(
        `/public/sessions/${session.sessionId}/leave`,
        {},
        session.tableToken,
        session.guestToken,
      )
      .subscribe({
        next: () => {
          this.loading.set(false);
          this.displayName = '';
          this.resetExpiredSession('Saliste de la atención correctamente.');
        },
        error: (err) => this.fail(err),
      });
  }
  requestBeforeExit(type: 'WAITER_CALL' | 'BILL'): void {
    this.exitBlockedOpen.set(false);
    if (type === 'BILL') this.openBillSheet();
    else this.requestService(type);
  }
  // ——— Hoja del pedido: gestos para agrandar/cerrar y deslizar para quitar ———
  readonly cartOpen = signal(false);
  readonly cartY = signal(0);
  readonly cartExpanded = signal(false);
  readonly cartDragging = signal(false);
  readonly cartClosing = signal(false);
  /** Acordeón del pedido: una sola agrupación abierta (otra persona «g:id» o una ronda «r:n»). */
  readonly openGroup = signal<string | null>(null);
  /** Quita el desvanecido de abajo cuando la lista desplegada llega a su final. */
  markSubEnd(event: Event): void {
    const box = event.target as HTMLElement;
    box.classList.toggle('dn-end', box.scrollTop + box.clientHeight >= box.scrollHeight - 4);
  }
  toggleGroup(key: string): void {
    this.openGroup.update((current) => (current === key ? null : key));
    // Si la lista abierta no necesita scroll, no lleva el desvanecido de abajo.
    setTimeout(() => document.querySelectorAll('.cs-sub').forEach((box) =>
      box.classList.toggle('dn-end', box.scrollHeight <= 200)), 60);
  }
  /** Tocar fuera de una agrupación abierta la cierra. */
  collapseOnOutside(event: Event): void {
    if (!(event.target as HTMLElement).closest('.cs-group, .cs-round')) this.openGroup.set(null);
  }
  readonly myDrafts = computed(() => this.draftItems().filter((item) => item.guestId === this.guest()?.guestId));
  /** Lo que eligen los demás, agrupado por persona (plegado). */
  readonly otherDrafts = computed(() => {
    const groups = new Map<string, OrderItemView[]>();
    for (const item of this.draftItems().filter((entry) => entry.guestId !== this.guest()?.guestId))
      groups.set(item.guestId || 'staff', [...(groups.get(item.guestId || 'staff') || []), item]);
    return [...groups.entries()].map(([id, items]) => ({
      id, items,
      name: id === 'staff' ? 'Mesero' : this.snapshot()?.guests.find((person) => person.id === id)?.displayName || 'Comensal',
      count: items.reduce((sum, item) => sum + item.quantity, 0),
      total: items.reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
    }));
  });
  readonly swipe = signal<{ id: string; dx: number } | null>(null);
  private cartDrag: { y: number; time: number; mode: '' | 'down' | 'up'; dy: number; fromBody: boolean } | null = null;
  private rowDrag: { x: number; y: number; axis: '' | 'x' | 'y' } | null = null;
  openCart(): void {
    this.cartY.set(0); this.cartExpanded.set(false); this.cartClosing.set(false); this.swipe.set(null);
    this.openGroup.set(null);
    this.cartOpen.set(true);
    document.documentElement.classList.add('dn-sheet-open');
  }
  closeCart(): void {
    if (!this.cartOpen() || this.cartClosing()) return;
    this.cartClosing.set(true);
    this.cartDragging.set(false);
    this.cartY.set(window.innerHeight);
    setTimeout(() => {
      this.cartOpen.set(false); this.cartClosing.set(false); this.cartY.set(0);
      document.documentElement.classList.remove('dn-sheet-open');
    }, 260);
  }
  cartTouchStart(event: TouchEvent): void {
    if (event.touches.length !== 1) return;
    const target = event.target as HTMLElement;
    const body = target.closest('.cs-body') as HTMLElement | null;
    // Dentro de una lista desplegada con scroll propio, el gesto es de esa lista, no de la hoja.
    const sub = target.closest('.cs-sub') as HTMLElement | null;
    const innerScroll = !!sub && sub.scrollHeight > sub.clientHeight + 2;
    this.cartDrag = { y: event.touches[0].clientY, time: Date.now(), mode: '', dy: 0, fromBody: (!!body && body.scrollTop > 0) || innerScroll };
  }
  cartTouchMove(event: TouchEvent): void {
    const drag = this.cartDrag;
    if (!drag || drag.fromBody) return;
    const dy = event.touches[0].clientY - drag.y;
    if (!drag.mode) {
      if (dy > 0) event.preventDefault();
      if (Math.abs(dy) < 6) return;
      if (dy > 0) drag.mode = 'down';
      else if (!this.cartExpanded()) drag.mode = 'up';
      else { this.cartDrag = null; return; }
      drag.y = event.touches[0].clientY; drag.time = Date.now();
      this.cartDragging.set(true);
      return;
    }
    event.preventDefault();
    drag.dy = event.touches[0].clientY - drag.y;
    this.cartY.set(drag.mode === 'down' ? Math.max(0, drag.dy) : Math.max(-40, Math.min(0, drag.dy / 3)));
  }
  cartTouchEnd(): void {
    const drag = this.cartDrag;
    this.cartDrag = null;
    if (!drag?.mode) return;
    this.cartDragging.set(false);
    const speed = drag.dy / Math.max(1, Date.now() - drag.time);
    if (drag.mode === 'up') { if (drag.dy < -40 || speed < -0.4) this.cartExpanded.set(true); this.cartY.set(0); return; }
    const height = (document.querySelector('.cs') as HTMLElement | null)?.offsetHeight || window.innerHeight;
    if (this.cartExpanded() && drag.dy < height * 0.45 && speed < 0.9) { if (drag.dy > 90 || speed > 0.4) this.cartExpanded.set(false); this.cartY.set(0); return; }
    if (drag.dy > Math.min(140, height * 0.3) || speed > 0.5) this.closeCart(); else this.cartY.set(0);
  }
  /** Deslizar un producto propio hacia la izquierda lo quita (como en iPhone). */
  rowTouchStart(event: TouchEvent, item: OrderItemView): void {
    if (item.guestId !== this.guest()?.guestId || this.checkoutInProgress()) { this.rowDrag = null; return; }
    this.rowDrag = { x: event.touches[0].clientX, y: event.touches[0].clientY, axis: '' };
    this.swipeTarget = item.id;
  }
  rowTouchMove(event: TouchEvent): void {
    const drag = this.rowDrag;
    if (!drag) return;
    const dx = event.touches[0].clientX - drag.x;
    const dy = event.touches[0].clientY - drag.y;
    if (!drag.axis) {
      if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
      drag.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    if (drag.axis !== 'x') return;
    event.stopPropagation();
    event.preventDefault();
    this.swipe.set({ id: this.swipeTarget, dx: Math.min(0, Math.max(-160, dx)) });
  }
  private swipeTarget = '';
  rowTouchEnd(item: OrderItemView): void {
    const drag = this.rowDrag;
    this.rowDrag = null;
    const current = this.swipe();
    if (!drag || drag.axis !== 'x' || !current) return;
    if (current.dx < -90) {
      navigator.vibrate?.(15);
      this.swipe.set({ id: item.id, dx: -window.innerWidth });
      setTimeout(() => { this.swipe.set(null); this.remove(item.id); }, 180);
    } else this.swipe.set(null);
  }
  // ——— Pedido de la mesa: lo que falta enviar arriba, lo enviado por rondas ———
  roundCount(items: OrderItemView[]): number { return items.reduce((sum, item) => sum + item.quantity, 0); }
  readonly draftItems = computed(() => {
    const me = this.guest()?.guestId;
    return this.cartItems().filter((item) => item.status === 'DRAFT').sort((a, b) => Number(b.guestId === me) - Number(a.guestId === me));
  });
  readonly sentRounds = computed(() => {
    const rounds = new Map<number, OrderItemView[]>();
    for (const item of this.cartItems().filter((entry) => entry.status !== 'DRAFT'))
      rounds.set(item.roundNumber, [...(rounds.get(item.roundNumber) || []), item]);
    // Junta en una línea el mismo plato (misma persona, opciones, nota y estado) dentro de la ronda.
    const merge = (items: OrderItemView[]) => {
      const lines = new Map<string, OrderItemView>();
      for (const item of items) {
        const key = [item.name, item.guestId, item.modifiers, item.notes, item.status].join('|');
        const found = lines.get(key);
        lines.set(key, found ? { ...found, quantity: found.quantity + item.quantity } : item);
      }
      return [...lines.values()];
    };
    return [...rounds.entries()].sort((a, b) => b[0] - a[0]).map(([number, items]) => ({
      number, items: merge(items),
      step: Math.min(...items.map((item) => this.statusStep(item.status) || 1)),
      total: items.reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0),
    }));
  });
  roundText(step: number): string {
    return ['Recibido en cocina', 'Recibido en cocina', 'En preparación', 'Listo, ya lo traen', 'Servido en tu mesa'][step] || 'Recibido en cocina';
  }
  /** Solo se dice quién pidió cada cosa si en la mesa pidió más de una persona. */
  readonly severalOrdering = computed(() => new Set(this.cartItems().map((item) => item.guestId ?? 'staff')).size > 1);
  /** Inicial de quien eligió el plato (🤵 si lo registró el mesero). */
  iniFor(item: OrderItemView): string {
    if (!item.guestId) return '🤵';
    return this.initials(this.snapshot()?.guests.find((person) => person.id === item.guestId)?.displayName || '?');
  }
  readonly nameFlash = signal<string | null>(null);
  private nameFlashTimer = 0;
  /** Tocar la inicial muestra el nombre completo un momento. */
  flashName(item: OrderItemView): void {
    window.clearTimeout(this.nameFlashTimer);
    this.nameFlash.set(item.id);
    this.nameFlashTimer = window.setTimeout(() => this.nameFlash.set(null), 1600);
  }
  whoFor(item: OrderItemView): string {
    if (!item.guestId) return 'Lo pidió el mesero';
    return item.guestId === this.guest()?.guestId ? 'Tú' : this.guestNameFor(item.guestId);
  }
  roundPill(step: number): string {
    return ['Recibido', 'Recibido', 'Preparando', 'Listo', 'Servido'][step] || 'Recibido';
  }
  imageFor(item: OrderItemView): string | null {
    return this.menu().find((dish) => dish.name === item.name)?.imageUrl || null;
  }
  guestTotal(guestId: string): number {
    return this.cartItems()
      .filter((item) => item.guestId === guestId)
      .reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0);
  }
  itemsForGuest(guestId: string): OrderItemView[] {
    return this.cartItems().filter((item) => item.guestId === guestId);
  }
  guestSummary(guestId: string): string {
    const count =
      this.snapshot()
        ?.items.filter((item) => item.guestId === guestId)
        .reduce((sum, item) => sum + item.quantity, 0) || 0;
    return count ? `${count} producto(s)` : 'Aún sin productos';
  }
  guestNameFor(guestId: string): string {
    return guestId === this.guest()?.guestId
      ? 'Tu pedido'
      : this.snapshot()?.guests.find((person) => person.id === guestId)?.displayName || 'Comensal';
  }
  hostName(): string {
    return (
      this.snapshot()?.guests.find((person) => person.active && person.host)?.displayName ||
      'El anfitrión'
    );
  }
  initials(name: string): string {
    return name
      .split(' ')
      .map((part) => part[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }
  statusStep(status: string): number {
    return (
      ({ SUBMITTED: 1, PREPARING: 2, READY: 3, DELIVERED: 4 } as Record<string, number>)[status] ||
      0
    );
  }
  statusLabel(status: string): string {
    return (
      (
        {
          DRAFT: 'En selección',
          SUBMITTED: 'Recibido en cocina',
          PREPARING: 'Preparando',
          READY: 'Listo para servir',
          DELIVERED: 'Servido en mesa',
        } as Record<string, string>
      )[status] || status
    );
  }
  statusClass(status: string): string {
    return (
      (
        {
          DRAFT: 'neutral',
          SUBMITTED: 'neutral',
          PREPARING: 'preparing',
          READY: 'ready',
          DELIVERED: 'delivered',
        } as Record<string, string>
      )[status] || 'neutral'
    );
  }
  private notifyOrderStatus(type: string, updated: OrderSnapshot): void {
    const previous = this.snapshot();
    const expected = type.replace('ORDER_ITEM_', '');
    const item = updated.items.find(
      (current) =>
        current.status === expected &&
        previous?.items.find((old) => old.id === current.id)?.status !== current.status,
    );
    if (!item) return;
    const own = item.guestId === this.guest()?.guestId;
    const owner = updated.guests.find((person) => person.id === item.guestId)?.displayName;
    const subject = own ? `Tu ${item.name}` : `${item.name}${owner ? ' de ' + owner : ''}`;
    if (type === 'ORDER_ITEM_PREPARING')
      this.toast.show(
        'Pedido en preparación',
        `${subject} ya está siendo preparado por cocina.`,
        'info',
        '♨',
      );
    if (type === 'ORDER_ITEM_READY')
      this.toast.show(
        'Plato listo',
        `${subject} está listo; el mesero lo llevará a la mesa.`,
        'success',
        '✓',
      );
    if (type === 'ORDER_ITEM_DELIVERED')
      this.toast.show('Servido en mesa', `${subject} ya fue servido en la mesa.`, 'success', '✓');
  }
  private acceptSnapshot(value: OrderSnapshot): void {
    this.snapshot.set(value);
    this.retryDelay = 3000;
    this.waiterOverride.set(undefined);
    const session = this.guest();
    if (!session) return;
    const current = new Map(value.guests.filter((person) => person.active).map((person) => [person.id, person.displayName] as [string, string]));
    if (this.knownGuests) {
      for (const [id, name] of current) if (!this.knownGuests.has(id) && id !== session.guestId) this.toast.show(`${name} se unió a la mesa`, 'Ahora comparten el pedido.', 'info', '👋', 2600);
      for (const [id, name] of this.knownGuests) if (!current.has(id) && id !== session.guestId) this.toast.show(`${name} dejó la mesa`, 'Lo que pidió sigue en la cuenta de la mesa.', 'info', '↗', 2600);
    }
    this.knownGuests = current;
    if (this.welcomePending) {
      this.welcomePending = false;
      const others = value.guests.filter((person) => person.active && person.id !== session.guestId).map((person) => person.displayName);
      const company = !others.length ? 'Elige tus platos y envíalos cuando quieras.'
        : `Ya están ${others.length > 2 ? `${others.slice(0, 2).join(', ')} y ${others.length - 2} más` : others.join(' y ')}.`;
      this.toast.show(`¡Listo, ${session.displayName}! Estás en ${this.tableLabel() || 'tu mesa'}`, company, 'success', '✓', 3500);
    }
    const own = value.guests.find((person) => person.id === session.guestId);
    if (own && !own.active) {
      this.resetExpiredSession('Ya no formas parte de esta atención.');
      return;
    }
    if (own && own.host !== session.host) {
      const updated = { ...session, host: own.host };
      this.guest.set(updated);
      this.saveGuest(updated);
    }
  }
  private fail(err: any): void {
    this.loading.set(false);
    this.toast.show(
      'No se pudo completar',
      err.error?.message || 'Inténtalo nuevamente.',
      'error',
      '!',
    );
  }
  /**
   * Solo se olvida la identidad del comensal cuando el servidor confirma que la atención terminó o que
   * ya no pertenece a ella (401/403/404). Un corte de internet o el servidor reiniciándose no la borran:
   * se reintenta solo y, al volver la conexión, la pantalla se pone al día.
   */
  private handleSessionError(err: any): void {
    if (err.status === 409) {
      this.reviewBlocked.set(true);
      return;
    }
    if ([401, 403, 404, 410].includes(err.status)) {
      this.resetExpiredSession();
      return;
    }
    this.retryLater();
  }
  private retryTimer = 0;
  private retryDelay = 3000;
  private retryLater(): void {
    if (this.retryTimer) return;
    if (this.retryDelay === 3000) this.toast.show('Sin conexión', 'Tu pedido sigue guardado. Reintentando…', 'warning', '⟳', 3000);
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = 0;
      this.retryDelay = Math.min(this.retryDelay * 2, 30000);
      if (this.guest()) this.loadAll();
    }, this.retryDelay);
  }
  /** El mesero movió o unió la mesa: el teléfono continúa en la nueva mesa sin volver a escanear. */
  private followRelocation(move: TableRelocation): void {
    const current = this.guest();
    if (!current) return;
    localStorage.removeItem(`restos_guest_${current.qrPublicId}`);
    this.saveGuest({
      ...current,
      sessionId: move.sessionId,
      tableToken: move.tableToken,
      qrPublicId: move.qrPublicId,
      tableLabel: move.tableLabel,
      host: current.host && move.sessionId === current.sessionId,
    });
    location.assign(`/diner?qr=${move.qrPublicId}`);
  }
  private saveGuest(session: GuestSession): void {
    const stored = { ...session, tableLabel: session.tableLabel || this.tableLabel() };
    localStorage.setItem(`restos_guest_${session.qrPublicId}`, JSON.stringify(stored));
    localStorage.setItem('restos_active_guest', JSON.stringify(stored));
  }
  private clearSavedGuest(): void {
    localStorage.removeItem(`restos_guest_${this.qrPublicId}`);
    sessionStorage.removeItem(`restos_guest_${this.qrPublicId}`);
    localStorage.removeItem('restos_active_guest');
    localStorage.removeItem('restos_pending_transfer');
  }
  private completeSession(): void {
    const finished = this.guest();
    if (finished)
      this.lastVisit.set({ sessionId: finished.sessionId, guestToken: finished.guestToken });
    this.clearSavedGuest();
    this.unwatch?.();
    this.realtime.disconnect();
    this.guest.set(null);
    this.snapshot.set(null);
    this.knownGuests = null;
    this.sessionCompleted.set(true);
  }
  startNewVisit(): void {
    location.reload();
  }
  private resetExpiredSession(
    message = 'La atención anterior terminó. Ingresa tu nombre para iniciar una nueva.',
  ): void {
    this.clearSavedGuest();
    this.unwatch?.();
    this.realtime.disconnect();
    this.reviewBlocked.set(false);
    this.experienceStarted.set(false);
    this.guest.set(null);
    this.snapshot.set(null);
    this.knownGuests = null;
    this.error.set(message);
  }
  // ——— Al recuperar la conexión (desbloqueo, otra app, internet de vuelta): ponerse al día ———
  private stopResync?: () => void;
  /** «Carlos va en camino a tu mesa» (o «Tu cuenta se está preparando»), con una vibración breve. */
  private announceComing(request: ServiceRequestView): void {
    const name = request.waiterName?.split(' ')[0];
    if (request.type === 'BILL') this.toast.show('Tu cuenta se está preparando', `${name || 'El mesero'} la llevará a tu mesa.`, 'info', '🧾');
    else this.toast.show(`${name || 'El mesero'} va en camino`, 'Ya vio tu llamado y se dirige a tu mesa.', 'success', '🏃');
    try { navigator.vibrate?.([120, 60, 120]); } catch { /* sin soporte */ }
  }
  /** Recarga lo que pudo cambiar mientras la conexión estuvo caída y avisa los platos que quedaron listos. */
  private catchUp(): void {
    const session = this.guest();
    if (!session || this.sessionCompleted()) return;
    const before = new Map((this.snapshot()?.items || []).map((item) => [item.id, item.status] as [string, string]));
    this.api.get<OrderSnapshot>(`/public/sessions/${session.sessionId}/orders`, session.tableToken).subscribe({
      next: (value) => {
        // Avisa lo que pasó mientras tanto (platos listos o servidos).
        const ready = value.items.filter((item) => item.status === 'READY' && before.get(item.id) && before.get(item.id) !== 'READY');
        if (ready.length) this.toast.show(ready.length === 1 ? `${ready[0].name} está listo` : `${ready.length} platos están listos`, 'El mesero los lleva a tu mesa.', 'success', '✓');
        this.acceptSnapshot(value);
      },
      error: (err) => this.handleSessionError(err),
    });
    this.api.get<ServiceRequestView[]>(`/public/sessions/${session.sessionId}/service-requests`, session.tableToken, session.guestToken).subscribe({
      next: (requests) => {
        const call = requests.find((item) => item.type === 'WAITER_CALL') || null;
        const bill = requests.find((item) => item.type === 'BILL') || null;
        if (call?.status === 'IN_PROGRESS' && this.waiterRequest()?.status === 'PENDING') this.announceComing(call);
        if (bill?.status === 'IN_PROGRESS' && this.billRequest()?.status === 'PENDING') this.announceComing(bill);
        this.billRequest.set(bill);
        this.waiterRequest.set(call);
      },
      error: () => undefined,
    });
    this.api.get<MenuItemView[]>(`/public/sessions/${session.sessionId}/menu`, session.tableToken).subscribe({
      next: (items) => this.menu.set(items),
      error: () => undefined,
    });
  }
  ngOnDestroy(): void {
    this.stopResync?.();
    window.clearInterval(this.askTimer);
    this.unwatch?.();
    if (this.transferPoll) window.clearInterval(this.transferPoll);
    if (this.cartFlightTimer) window.clearTimeout(this.cartFlightTimer);
    if (this.cartCountTimer) window.clearTimeout(this.cartCountTimer);
    if (this.removeAnimationTimer) window.clearTimeout(this.removeAnimationTimer);
    this.realtime.disconnect();
  }
}
