import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { IconComponent } from '../../shared/ui/icon.component';

type DemoRole = 'ADMIN' | 'WAITER' | 'KITCHEN';

/**
 * Página de presentación del producto con acceso directo a la demo. Si la demo está desactivada
 * (instalación de un restaurante), la raíz lleva directamente al ingreso del personal.
 */
@Component({
  imports: [RouterLink, IconComponent],
  templateUrl: './landing.page.html',
  styleUrls: [
    './landing.page.scss',
    './landing-sections.scss',
    './landing-plans.scss',
    './landing-mockups.scss',
    './landing-phone.scss',
    './landing-halo.scss',
  ],
})
export class LandingPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  /** Número de WhatsApp comercial (solo dígitos, con código de país). Vacío = el botón lleva a la demo. */
  readonly salesWhatsapp = '';
  readonly ready = signal(false);
  readonly starting = signal<DemoRole | 'DINER' | null>(null);
  readonly error = signal('');
  readonly year = new Date().getFullYear();

  readonly features = [
    {
      icon: 'grid',
      title: 'Carta QR que vende',
      text: 'Fotos grandes, «lo más pedido hoy», etiquetas y pedidos directos a cocina desde el celular del cliente.',
    },
    {
      icon: 'map',
      title: 'Salón en vivo',
      text: 'Plano de tu local con mesas animadas: quién llama, quién pide la cuenta y qué plato está listo.',
    },
    {
      icon: 'chef',
      title: 'Cocina inteligente',
      text: 'Prioridad automática, alertas de demora por estación y aviso para sacar juntos los platos de una mesa.',
    },
    {
      icon: 'receipt',
      title: 'Cobro sin fricción',
      text: 'Cuenta dividida desde el celular, propinas, descuentos, puntos y cierre de caja ordenado.',
    },
    {
      icon: 'package',
      title: 'Inventario predictivo',
      text: '«Al ritmo de hoy, el pescado se acaba a las 2:30 pm» y lista de compras lista para WhatsApp.',
    },
    {
      icon: 'flame',
      title: 'Modo TV y reportes',
      text: 'Ventas en vivo, mapa de calor de mesas, repetición del día y comparativo entre sucursales.',
    },
    {
      icon: 'hourglass',
      title: 'Lista de espera y reservas',
      text: 'El cliente sigue su turno desde el celular y recibe el aviso cuando su mesa está lista.',
    },
    {
      icon: 'trophy',
      title: 'Equipo motivado',
      text: 'Meta del día con celebración, ranking semanal con medallas y calificaciones de los clientes.',
    },
  ];

  readonly steps = [
    { title: 'Carga tu carta', text: 'Desde Excel o a mano, con fotos, opciones y recetas.' },
    {
      title: 'Diseña tu salón',
      text: 'Elige una plantilla, ubica tus mesas e imprime los QR en PDF.',
    },
    {
      title: 'Empieza a atender',
      text: 'Tus clientes piden desde la mesa y tu equipo lo ve todo en tiempo real.',
    },
  ];

  readonly plans = [
    {
      name: 'Esencial',
      price: 'S/ 149',
      period: '/mes',
      note: 'Para cafeterías y locales pequeños',
      items: ['Carta QR y pedidos', 'Salón, cocina y caja', 'Hasta 15 mesas', 'Reportes básicos'],
    },
    {
      name: 'Profesional',
      price: 'S/ 249',
      period: '/mes',
      note: 'El favorito de los restaurantes',
      featured: true,
      items: [
        'Todo lo de Esencial',
        'Plano en vivo y Modo TV',
        'Inventario predictivo',
        'Lista de espera y reservas',
        'Ranking y metas del equipo',
      ],
    },
    {
      name: 'Cadena',
      price: 'A medida',
      period: '',
      note: 'Para varias sucursales',
      items: [
        'Todo lo de Profesional',
        'Comparativo entre sucursales',
        'Soporte prioritario',
        'Acompañamiento en la puesta en marcha',
      ],
    },
  ];

  readonly faqs = [
    {
      q: '¿Tengo que instalar algo?',
      a: 'No. Funciona en el navegador de cualquier tablet, celular o computadora. Tus clientes solo escanean el QR de la mesa.',
    },
    {
      q: '¿Qué pasa si se cae el internet?',
      a: 'Las pantallas se reconectan solas y recuperan los pedidos al volver la señal. Recomendamos un plan de datos de respaldo en la caja.',
    },
    {
      q: '¿Emite boletas y facturas electrónicas?',
      a: 'La integración con SUNAT está en desarrollo. Mientras tanto puedes registrar tus cobros y exportar los reportes a Excel.',
    },
    {
      q: '¿Puedo probarlo antes de contratar?',
      a: 'Sí: la demo de arriba es un restaurante completo con datos de ejemplo. Entra como dueño, mesero o cocina.',
    },
  ];

  ngOnInit(): void {
    this.api.get<{ enabled: boolean }>('/public/demo').subscribe({
      next: (value) => (value.enabled ? this.ready.set(true) : this.router.navigateByUrl('/login')),
      error: () => this.router.navigateByUrl('/login'),
    });
  }

  /** Entra a la demo con el rol elegido y abre la pantalla que mejor la muestra. */
  startDemo(role: DemoRole): void {
    if (this.starting()) return;
    this.starting.set(role);
    this.error.set('');
    this.auth.startDemo(role).subscribe({
      next: () =>
        this.router.navigateByUrl({ ADMIN: '/live', WAITER: '/waiter', KITCHEN: '/kitchen' }[role]),
      error: () => {
        this.starting.set(null);
        this.error.set('No pudimos abrir la demo. Intenta nuevamente en unos segundos.');
      },
    });
  }

  /** Abre la carta QR de una mesa libre de la demo, tal como la ve el cliente. */
  openDinerDemo(): void {
    if (this.starting()) return;
    this.starting.set('DINER');
    this.api.post<{ dinerQr: string | null }>('/public/demo/start', { role: 'ADMIN' }).subscribe({
      next: (value) => {
        this.starting.set(null);
        if (value.dinerQr) window.open(`/diner?qr=${value.dinerQr}`, '_blank', 'noopener');
      },
      error: () => this.starting.set(null),
    });
  }

  contact(): void {
    if (!this.salesWhatsapp) {
      this.startDemo('ADMIN');
      return;
    }
    const text = encodeURIComponent('Hola, quiero conocer RestOS para mi restaurante.');
    window.open(`https://wa.me/${this.salesWhatsapp}?text=${text}`, '_blank', 'noopener');
  }

  scrollTo(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
