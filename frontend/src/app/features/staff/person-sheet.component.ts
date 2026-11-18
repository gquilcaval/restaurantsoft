import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { DialogService } from '../../shared/ui/dialog.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ToastService } from '../../shared/ui/toast.service';
import { initials, limaDate } from './attendance.models';
import { OTHER_JOBS, Post, POSTS, StaffPerson } from './team.models';

/**
 * Ficha de una persona del equipo. Para registrar a alguien solo hacen falta su nombre y su puesto;
 * el PIN se pone aquí mismo y lo demás (correo, documento, celular, fecha de ingreso) es opcional.
 * En el celular es una hoja inferior que se cierra deslizándola hacia abajo.
 */
@Component({
  selector: 'app-person-sheet',
  imports: [FormsModule, IconComponent],
  templateUrl: './person-sheet.component.html',
  styleUrls: ['./attendance-sheet.component.scss', './person-sheet.component.scss', './person-sheet-fields.scss'],
})
export class PersonSheetComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);

  /** null = persona nueva. */
  readonly person = input<StaffPerson | null>(null);
  readonly saved = output<void>();
  readonly closed = output<void>();

  readonly posts = POSTS;
  readonly otherJobs = OTHER_JOBS;
  readonly initials = initials;

  readonly name = signal('');
  readonly post = signal<Post | null>(null);
  readonly job = signal('');
  readonly pin = signal('');
  /** Al editar a alguien que ya tiene PIN, el campo aparece solo si se quiere cambiar. */
  readonly changingPin = signal(false);
  readonly email = signal('');
  readonly password = signal('');
  readonly document = signal('');
  readonly phone = signal('');
  readonly hireDay = signal('');
  readonly hireMonth = signal('');
  readonly hireYear = signal('');
  readonly canCounter = signal(false);
  readonly canReservations = signal(false);
  readonly section = signal<'' | 'phone' | 'more'>('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly dragY = signal(0);
  readonly dragging = signal(false);
  readonly closing = signal(false);
  private startY = 0;

  /** Usa el sistema (mesero, cocina o encargado); «otro puesto» solo marca asistencia. */
  readonly access = computed(() => !!this.post() && this.post() !== 'STAFF');
  readonly withEmail = computed(() => this.access() && !!this.email().trim());
  readonly hasPin = computed(() => !!this.person()?.hasPin);
  readonly showPinField = computed(() => !this.hasPin() || this.changingPin());
  /** Quien usa el sistema necesita una forma de entrar: el PIN o un correo con contraseña. */
  readonly pinRequired = computed(() => this.access() && !this.withEmail() && !this.hasPin());
  readonly hireDate = computed(() => {
    const [day, month, year] = [this.hireDay(), this.hireMonth(), this.hireYear()].map((value) => String(value ?? '').trim());
    if (!day && !month && !year) return null;
    const date = `${year.padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
    const parsed = new Date(date + 'T12:00:00Z');
    const valid = /^\d{4}$/.test(year) && !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date && date <= limaDate(Date.now());
    return valid ? date : 'invalid';
  });
  /** Lo que falta para poder guardar, dicho en pocas palabras; vacío si está todo. */
  readonly missing = computed(() => {
    if (this.name().trim().length < 2) return 'Escribe el nombre.';
    if (!this.post()) return 'Elige su puesto.';
    const pin = this.pin().trim();
    if (pin && !/^\d{4,6}$/.test(pin)) return 'El PIN lleva de 4 a 6 números.';
    if (this.pinRequired() && !pin) return 'Ponle un PIN para que pueda entrar.';
    if (this.withEmail() && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.email().trim())) return 'Revisa el correo.';
    if (this.withEmail() && !this.person()?.hasPassword && this.password().length < 8) return 'La contraseña lleva 8 caracteres o más.';
    if (this.password() && this.password().length < 8) return 'La contraseña lleva 8 caracteres o más.';
    if (this.access() && this.password() && !this.withEmail()) return 'Para usar contraseña, escribe también el correo.';
    if (this.document().trim() && !/^[A-Za-z0-9]{8,12}$/.test(this.document().trim())) return 'El DNI lleva 8 números.';
    if (this.hireDate() === 'invalid') return 'Revisa la fecha de ingreso.';
    return '';
  });

  ngOnInit(): void {
    const person = this.person();
    if (!person) return;
    this.name.set(person.fullName); this.post.set(person.role); this.job.set(person.jobTitle || '');
    this.email.set(person.email || ''); this.document.set(person.documentNumber || ''); this.phone.set(person.phone || '');
    this.canCounter.set(person.canCounter); this.canReservations.set(person.canReservations);
    if (person.hiredOn) {
      const [year, month, day] = person.hiredOn.split('-');
      this.hireYear.set(year); this.hireMonth.set(String(Number(month))); this.hireDay.set(String(Number(day)));
    }
  }

  choosePost(post: Post): void {
    // Al pasar de «otro puesto» a un puesto del sistema (o al revés), el cargo escrito ya no aplica.
    if ((post === 'STAFF') !== (this.post() === 'STAFF')) this.job.set('');
    this.post.set(post); this.error.set('');
  }
  toggleSection(key: 'phone' | 'more'): void { this.section.set(this.section() === key ? '' : key); }
  /** PIN de 4 números al azar, evitando los fáciles de adivinar (1111, 1234…). */
  suggestPin(): void {
    let pin = '';
    do { pin = String(Math.floor(1000 + Math.random() * 9000)); }
    while (new Set(pin).size < 3 || '0123456789'.includes(pin) || '9876543210'.includes(pin));
    this.pin.set(pin); this.error.set('');
  }
  setToday(): void {
    const [year, month, day] = limaDate(Date.now()).split('-');
    this.hireYear.set(year); this.hireMonth.set(String(Number(month))); this.hireDay.set(String(Number(day)));
  }

  save(): void {
    if (this.missing() || this.saving()) return;
    const person = this.person(); const post = this.post()!;
    const body = {
      fullName: this.name().trim(), role: post, active: person?.active ?? true,
      email: this.withEmail() ? this.email().trim() : null, password: this.withEmail() && this.password() ? this.password() : null,
      pin: this.pin().trim() || null,
      canCounter: post === 'WAITER' && this.canCounter(), canReservations: post === 'WAITER' && this.canReservations(),
      documentNumber: this.document().trim() || null, phone: this.phone().trim() || null,
      jobTitle: this.job().trim() || null, hiredOn: this.hireDate(),
    };
    this.send(person ? this.api.patch(`/admin/operations/users/${person.id}`, body) : this.api.post('/admin/operations/users', body),
      person ? 'Cambios guardados.' : `${this.firstName()} ya está en el equipo.`);
  }

  /** Deja de trabajar aquí (o vuelve): no se borra, para conservar su historial. */
  async setActive(active: boolean): Promise<void> {
    const person = this.person();
    if (!person) return;
    if (!active) {
      const accepted = await this.dialog.confirm({
        title: `¿${this.firstName()} ya no trabaja aquí?`,
        message: 'Dejará de aparecer en la tablet y no podrá entrar al sistema. Su historial se conserva y puedes reactivarlo cuando quieras.',
        confirmLabel: 'Sí, ya no trabaja', tone: 'danger', icon: '!',
      });
      if (!accepted) return;
    }
    const body = {
      fullName: person.fullName, role: person.role, active, email: person.email, password: null, pin: null,
      canCounter: person.canCounter, canReservations: person.canReservations, documentNumber: person.documentNumber,
      phone: person.phone, jobTitle: person.jobTitle, hiredOn: person.hiredOn,
    };
    this.send(this.api.patch(`/admin/operations/users/${person.id}`, body),
      active ? `${this.firstName()} vuelve al equipo.` : `${this.firstName()} ya no figura en el equipo.`);
  }

  async removePin(): Promise<void> {
    const person = this.person();
    if (!person) return;
    const accepted = await this.dialog.confirm({
      title: 'Quitar PIN', message: `${this.firstName()} no podrá marcar asistencia ni entrar en la tablet hasta que le pongas otro.`,
      confirmLabel: 'Quitar PIN', tone: 'danger', icon: '!',
    });
    if (accepted) this.send(this.api.put(`/admin/operations/users/${person.id}/pin`, { pin: null }), 'PIN retirado.');
  }

  firstName(): string { return this.name().trim().split(/\s+/)[0] || 'La persona'; }

  close(): void {
    if (this.closing()) return;
    this.closing.set(true);
    setTimeout(() => this.closed.emit(), 220);
  }
  onTouchStart(event: TouchEvent): void { this.startY = event.touches[0].clientY; this.dragging.set(true); }
  onTouchMove(event: TouchEvent): void {
    if (!this.dragging()) return;
    const delta = event.touches[0].clientY - this.startY;
    if (delta > 0) event.preventDefault();
    this.dragY.set(Math.max(0, delta));
  }
  onTouchEnd(): void {
    this.dragging.set(false);
    if (this.dragY() > 110) this.close(); else this.dragY.set(0);
  }

  private send(request: ReturnType<ApiService['post']>, done: string): void {
    this.saving.set(true); this.error.set('');
    request.subscribe({
      next: () => { this.saving.set(false); this.toast.show('Listo', done, 'success', '✓'); this.saved.emit(); this.close(); },
      error: (err) => { this.saving.set(false); this.error.set(err.error?.message || 'No se pudo guardar. Revisa tu conexión.'); },
    });
  }
}
