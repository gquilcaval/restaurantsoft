import { CurrencyPipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { DialogService } from '../../shared/ui/dialog.service';

interface Organization {
  id:string; name:string; slug:string; logoUrl:string|null; primaryColor:string; defaultTheme:string;
  active:boolean; branchCount:number; userCount:number; tableCount:number; createdAt:string;
  subscription: Subscription;
  /** Módulos adicionales que se venden aparte del sistema base. */
  modules: { attendance: boolean };
}
interface Subscription {
  state:'TRIAL'|'ACTIVE'|'GRACE'|'EXPIRED'|'SUSPENDED'; plan:string; monthlyFee:number; maxBranches:number; activeBranches:number;
  trialEndsAt:string|null; paidUntil:string|null; graceEndsAt:string|null; daysLeft:number; billingNote:string|null;
}
interface SubscriptionPayment { id:string; amount:number; months:number; paidUntil:string; reference:string|null; createdAt:string; registeredBy:string; }

/** Consola del propietario de RestOS para dar de alta y supervisar restaurantes completos. */
@Component({
  imports: [AppShellComponent, FormsModule, CurrencyPipe],
  styleUrl: './platform.page.scss',
  template: `
    <app-shell title="Restaurantes" eyebrow="Administración de plataforma">
      <div class="metrics compact">
        <article class="metric-card"><span>◇</span><div><small>Restaurantes</small><strong>{{organizations().length}}</strong></div></article>
        <article class="metric-card"><span>●</span><div><small>Activos</small><strong>{{activeCount()}}</strong></div></article>
        <article class="metric-card"><span>▦</span><div><small>Sucursales</small><strong>{{branchCount()}}</strong></div></article>
        <article class="metric-card"><span>S/</span><div><small>Ingreso mensual</small><strong>{{monthlyRevenue() | currency:'PEN':'S/ '}}</strong></div></article>
        <article class="metric-card"><span>!</span><div><small>Por cobrar</small><strong>{{attentionCount()}}</strong></div></article>
      </div>
      @if (message()) { <p class="notice" [class.error]="hasError()">{{message()}}</p> }
      <article class="panel">
        <div class="panel-heading"><div><p class="eyebrow">Clientes</p><h2>Organizaciones registradas</h2></div><button class="primary" (click)="toggleForm()">{{showForm()?'Cancelar':'+ Nuevo restaurante'}}</button></div>
        @if(showForm()) {
          <form class="onboarding-form" (ngSubmit)="create()">
            <div class="form-section"><h3>Restaurante</h3><label>Nombre comercial<input name="name" [(ngModel)]="name" required maxlength="120"></label><label>Identificador web<input name="slug" [(ngModel)]="slug" placeholder="mi-restaurante" pattern="[a-z0-9-]+" required maxlength="80"></label><div class="field-pair"><label>Color principal<input type="color" name="color" [(ngModel)]="color"></label><label>Tema inicial<select name="theme" [(ngModel)]="theme"><option value="SYSTEM">Según dispositivo</option><option value="LIGHT">Claro</option><option value="DARK">Oscuro</option></select></label></div></div>
            <div class="form-section"><h3>Primera sucursal</h3><label>Nombre<input name="branchName" [(ngModel)]="branchName" required maxlength="120"></label><label>Dirección<input name="address" [(ngModel)]="address" maxlength="250"></label><label>Cantidad de mesas<input type="number" name="initialTables" [(ngModel)]="initialTables" min="1" max="100" required></label><label class="check"><input type="checkbox" name="menuTemplate" [(ngModel)]="createMenuTemplate"> Crear menú inicial editable</label></div>
            <div class="form-section"><h3>Administrador del cliente</h3><label>Nombre completo<input name="adminName" [(ngModel)]="adminName" required maxlength="120"></label><label>Correo de acceso<input type="email" name="adminEmail" [(ngModel)]="adminEmail" required maxlength="180"></label><label>Contraseña inicial<input type="password" name="adminPassword" [(ngModel)]="adminPassword" required minlength="8" maxlength="100" autocomplete="new-password"></label><button class="primary full" [disabled]="saving()">{{saving()?'Creando…':'Crear restaurante completo'}}</button></div>
          </form>
        }
        <div class="organization-table"><div class="table-header"><span>Restaurante</span><span>Operación</span><span>Estado</span><span>Suscripción</span></div>
          @for(org of organizations();track org.id){
            <div class="organization-row"><div class="org-name"><b>{{initials(org.name)}}</b><span><strong>{{org.name}}</strong><small>{{org.slug}} · desde {{createdLabel(org.createdAt)}}</small></span></div><span class="org-stats">{{org.branchCount}} suc. · {{org.tableCount}} mesas · {{org.userCount}} usuarios@if(org.modules.attendance){<i class="module-tag">+ Asistencia</i>}</span><button class="status status-button" [class]="org.active?'ready':'preparing'" (click)="toggleStatus(org)">{{org.active?'Activo':'Suspendido'}}</button><button class="subscription-chip" [attr.data-state]="org.subscription.state" (click)="openSubscription(org)"><strong>{{stateLabel(org.subscription.state)}}</strong><small>{{subscriptionDetail(org.subscription)}}</small></button></div>
          } @empty { <p class="column-empty">Todavía no hay restaurantes registrados.</p> }
        </div>
      </article>
      @if(managing(); as org){
        <div class="modal-backdrop" (click)="managing.set(null)"><section class="qr-dialog subscription-dialog" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" aria-labelledby="subscription-title">
          <button class="modal-close" (click)="managing.set(null)" aria-label="Cerrar">×</button>
          <p class="eyebrow">Suscripción</p><h2 id="subscription-title">{{org.name}}</h2>
          <p class="subscription-state" [attr.data-state]="org.subscription.state"><strong>{{stateLabel(org.subscription.state)}}</strong> · {{subscriptionDetail(org.subscription)}}</p>
          <section class="modules-box">
            <h3>Módulos adicionales</h3>
            <button type="button" class="module-switch" role="switch" [attr.aria-checked]="org.modules.attendance" [disabled]="savingModule()" (click)="toggleAttendance(org)">
              <span><strong>Asistencia del personal</strong><small>Marcación con PIN en la tablet, horarios, tardanzas, faltas y reporte en Excel.</small></span><i aria-hidden="true"></i>
            </button>
          </section>
          <form class="settings-form" (ngSubmit)="savePlan(org)">
            <div class="field-pair"><label>Plan<select name="plan" [(ngModel)]="plan"><option value="ESENCIAL">Esencial</option><option value="ESTANDAR">Estándar</option><option value="PROFESIONAL">Profesional</option></select></label><label>Mensualidad (S/)<input type="number" min="0" step="1" name="fee" [(ngModel)]="monthlyFee"></label></div>
            <div class="field-pair"><label>Sucursales permitidas<input type="number" min="1" max="50" name="maxBranches" [(ngModel)]="maxBranches"></label><label>Prueba hasta<input type="date" name="trialEndsAt" [(ngModel)]="trialEndsAt"></label></div>
            <label>Nota interna<input name="billingNote" [(ngModel)]="billingNote" maxlength="300" placeholder="Ej. paga por Yape los días 5"></label>
            <button class="secondary">Guardar plan</button>
          </form>
          <form class="settings-form subscription-payment" (ngSubmit)="registerPayment(org)">
            <h3>Registrar pago de mensualidad</h3>
            <div class="field-pair"><label>Meses<input type="number" min="1" max="24" name="months" [(ngModel)]="months" (ngModelChange)="paymentAmount=$event*monthlyFee"></label><label>Monto (S/)<input type="number" min="0" step="0.01" name="amount" [(ngModel)]="paymentAmount"></label></div>
            <label>Referencia<input name="reference" [(ngModel)]="paymentReference" maxlength="120" placeholder="N.º de operación o comprobante"></label>
            <button class="primary" [disabled]="months<1">Registrar pago y extender vigencia</button>
          </form>
          @if(payments().length){<div class="subscription-history"><h3>Pagos registrados</h3>@for(item of payments();track item.id){<div><span><strong>{{item.amount | currency:'PEN':'S/ '}}</strong> · {{item.months}} {{item.months===1?'mes':'meses'}}<small>{{item.reference||'Sin referencia'}} · {{item.registeredBy}}</small></span><span>Hasta {{item.paidUntil}}</span></div>}</div>}
        </section></div>
      }
    </app-shell>
  `
})
export class PlatformPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(DialogService);
  readonly organizations = signal<Organization[]>([]);
  readonly showForm = signal(false);
  readonly message = signal('');
  readonly hasError = signal(false);
  readonly saving = signal(false);
  name=''; slug=''; color='#44d62c'; theme='SYSTEM'; branchName='Sucursal Principal'; address='';
  adminName=''; adminEmail=''; adminPassword=''; initialTables=8; createMenuTemplate=true;

  readonly managing = signal<Organization | null>(null);
  readonly payments = signal<SubscriptionPayment[]>([]);
  plan = 'ESTANDAR'; monthlyFee = 0; maxBranches = 1; trialEndsAt = ''; billingNote = '';
  months = 1; paymentAmount = 0; paymentReference = '';
  ngOnInit(): void { this.load(); }
  monthlyRevenue(): number { return this.organizations().filter(org => ['ACTIVE','GRACE'].includes(org.subscription.state)).reduce((sum, org) => sum + Number(org.subscription.monthlyFee), 0); }
  attentionCount(): number { return this.organizations().filter(org => ['GRACE','EXPIRED'].includes(org.subscription.state) || (org.subscription.state === 'TRIAL' && org.subscription.daysLeft <= 3)).length; }
  stateLabel(state: string): string { return ({TRIAL:'Prueba',ACTIVE:'Al día',GRACE:'En gracia',EXPIRED:'Vencido',SUSPENDED:'Suspendido'} as Record<string,string>)[state] || state; }
  subscriptionDetail(sub: Subscription): string {
    if (sub.state === 'TRIAL') return `${sub.daysLeft} días restantes`;
    if (sub.state === 'ACTIVE') return `Pagado hasta ${sub.paidUntil}`;
    if (sub.state === 'GRACE') return `Se corta el ${sub.graceEndsAt}`;
    if (sub.state === 'EXPIRED') return sub.paidUntil ? `Venció el ${sub.paidUntil}` : 'Prueba terminada';
    return 'Acceso desactivado manualmente';
  }
  openSubscription(org: Organization): void {
    const sub = org.subscription;
    this.plan = sub.plan; this.monthlyFee = Number(sub.monthlyFee); this.maxBranches = sub.maxBranches;
    this.trialEndsAt = sub.trialEndsAt || ''; this.billingNote = sub.billingNote || '';
    this.months = 1; this.paymentAmount = Number(sub.monthlyFee); this.paymentReference = '';
    this.payments.set([]); this.managing.set(org);
    this.api.get<SubscriptionPayment[]>(`/platform/organizations/${org.id}/subscription/payments`).subscribe(value => this.payments.set(value));
  }
  savePlan(org: Organization): void {
    this.api.put<Subscription>(`/platform/organizations/${org.id}/subscription`, {plan:this.plan,monthlyFee:this.monthlyFee,maxBranches:this.maxBranches,trialEndsAt:this.trialEndsAt||null,billingNote:this.billingNote||null}).subscribe({
      next: value => { this.updateSubscription(org, value); this.message.set('Plan actualizado.'); this.hasError.set(false); },
      error: error => { this.hasError.set(true); this.message.set(error.error?.message || 'No se pudo guardar el plan.'); }
    });
  }
  registerPayment(org: Organization): void {
    this.api.post<Subscription>(`/platform/organizations/${org.id}/subscription/payments`, {amount:this.paymentAmount,months:this.months,reference:this.paymentReference||null}).subscribe({
      next: value => { this.updateSubscription(org, value); this.message.set(`Pago registrado. ${org.name} queda al día hasta ${value.paidUntil}.`); this.hasError.set(false); this.openSubscription({...org, subscription: value}); },
      error: error => { this.hasError.set(true); this.message.set(error.error?.message || 'No se pudo registrar el pago.'); }
    });
  }
  private updateSubscription(org: Organization, value: Subscription): void {
    this.organizations.update(list => list.map(item => item.id === org.id ? {...item, subscription: value} : item));
    const current = this.managing(); if (current?.id === org.id) this.managing.set({...current, subscription: value});
  }
  load(): void { this.api.get<Organization[]>('/platform/organizations').subscribe(value => this.organizations.set(value)); }
  readonly savingModule = signal(false);
  /** Activa o desactiva el módulo de asistencia. Al desactivarlo no se borra nada: vuelve todo si se reactiva. */
  async toggleAttendance(org: Organization): Promise<void> {
    const enable = !org.modules.attendance;
    if (!enable) {
      const accepted = await this.dialog.confirm({ title: 'Desactivar asistencia', message: `${org.name} dejará de ver la asistencia y su tablet no permitirá marcar. Las marcas y horarios se conservan por si se reactiva.`, confirmLabel: 'Desactivar', tone: 'danger', icon: '!' });
      if (!accepted) return;
    }
    this.savingModule.set(true);
    this.api.put<Organization>(`/platform/organizations/${org.id}/modules`, { attendance: enable }).subscribe({
      next: value => {
        this.savingModule.set(false);
        this.organizations.update(list => list.map(item => item.id === org.id ? { ...item, modules: value.modules } : item));
        const current = this.managing(); if (current?.id === org.id) this.managing.set({ ...current, modules: value.modules });
        this.message.set(`Asistencia ${enable ? 'activada' : 'desactivada'} para ${org.name}.`); this.hasError.set(false);
      },
      error: error => { this.savingModule.set(false); this.message.set(error.error?.message || 'No se pudo cambiar el módulo.'); this.hasError.set(true); },
    });
  }
  activeCount(): number { return this.organizations().filter(org => org.active).length; }
  branchCount(): number { return this.organizations().reduce((total, org) => total + org.branchCount, 0); }
  toggleForm():void { this.showForm.update(value => !value); this.message.set(''); }
  create(): void {
    this.saving.set(true); this.hasError.set(false);
    const body={name:this.name,slug:this.slug,primaryColor:this.color,defaultTheme:this.theme,branchName:this.branchName,address:this.address,adminName:this.adminName,adminEmail:this.adminEmail,adminPassword:this.adminPassword,initialTables:this.initialTables,createMenuTemplate:this.createMenuTemplate};
    this.api.post<Organization>('/platform/organizations', body).subscribe({next: () => {
      this.saving.set(false); this.showForm.set(false); this.message.set('Restaurante, sucursal, mesas y administrador creados correctamente.'); this.resetForm(); this.load();
    }, error: error => { this.saving.set(false); this.hasError.set(true); this.message.set(error.error?.message || 'No se pudo crear el restaurante.'); }});
  }
  async toggleStatus(org:Organization):Promise<void> {
    const action=org.active?'suspender':'reactivar';
    const accepted=await this.dialog.confirm({title:`${org.active?'Suspender':'Reactivar'} restaurante`,message:org.active?`${org.name} perderá temporalmente el acceso a su operación.`:`${org.name} recuperará el acceso a su operación.`,confirmLabel:org.active?'Suspender':'Reactivar',tone:org.active?'danger':'success',icon:org.active?'!':'✓'});
    if(!accepted)return;
    this.api.patch<Organization>(`/platform/organizations/${org.id}`,{active:!org.active}).subscribe({next:()=>{this.message.set(`Restaurante ${org.active?'suspendido':'reactivado'}.`);this.hasError.set(false);this.load();},error:error=>{this.hasError.set(true);this.message.set(error.error?.message||'No se pudo actualizar el restaurante.');}});
  }
  initials(name: string): string { return name.split(' ').map(value => value[0]).join('').slice(0,2).toUpperCase(); }
  createdLabel(value:string):string { return new Intl.DateTimeFormat('es-PE',{month:'short',year:'numeric'}).format(new Date(value)); }
  private resetForm():void { this.name='';this.slug='';this.branchName='Sucursal Principal';this.address='';this.adminName='';this.adminEmail='';this.adminPassword='';this.initialTables=8;this.createMenuTemplate=true; }
}
