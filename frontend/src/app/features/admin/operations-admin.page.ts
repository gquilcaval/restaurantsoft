import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import QRCode from 'qrcode';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { BrandDialogComponent } from './brand/brand-dialog.component';
import { PrintingSettingsComponent } from './printing/printing-settings.component';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { DialogService } from '../../shared/ui/dialog.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ToastService } from '../../shared/ui/toast.service';

interface AdminTable { id:string;label:string;capacity:number;qrPublicId:string;active:boolean;occupied:boolean;zoneId:string|null; }
interface Zone { id:string;name:string;color:string;displayOrder:number;open:boolean;active:boolean;tables:number;occupied:number;waiterIds:string[]; }
interface AdminUser { id:string;fullName:string;email:string|null;role:string;active:boolean;hasPin:boolean;canCounter:boolean;canReservations:boolean;documentNumber:string|null;phone:string|null;jobTitle:string|null;hiredOn:string|null;systemAccess:boolean; }
interface StaffDevice { id:string;name:string;createdAt:string;lastUsedAt:string|null;createdBy:string; }
interface Branch { id:string;name:string;address:string|null;active:boolean;tableCount:number;userCount:number;openSessionCount:number; }

/** Pantalla de configuración diaria para mesas, QR y cuentas del personal. */
@Component({
  imports:[AppShellComponent,FormsModule,IconComponent,BrandDialogComponent,RouterLink,PrintingSettingsComponent],
  templateUrl:'./operations-admin.page.html',
  styleUrls:['./operations-admin.page.scss','./settings-hub.scss','./settings-editor.scss','./settings-parts.scss','./settings-perms.scss'],
})
export class OperationsAdminPage implements OnInit {
  private readonly route=inject(ActivatedRoute);
  private readonly api=inject(ApiService);private readonly dialog=inject(DialogService);readonly auth=inject(AuthService);private readonly toast=inject(ToastService);readonly tables=signal<AdminTable[]>([]);readonly users=signal<AdminUser[]>([]);readonly branches=signal<Branch[]>([]);readonly message={set:(text:string)=>{if(text)this.toast.show(/no se pudo|debe|bloqueó|no permite/i.test(text)?'No se pudo completar':'Listo',text,/no se pudo|debe|bloqueó|no permite/i.test(text)?'error':'success',/no se pudo|debe|bloqueó|no permite/i.test(text)?'!':'✓');}};
  readonly tableForm=signal(false);readonly qrPreview=signal<{table:AdminTable;link:string;dataUrl:string}|null>(null);publicBaseUrl=location.origin;
  editingTableId='';tableLabel='';tableCapacity=4;tableActive=true;tableZoneId='';
  readonly zones=signal<Zone[]>([]);readonly zoneForm=signal(false);readonly zoneAssign=signal<{zone:Zone;kind:'tables'|'waiters'}|null>(null);
  assigned=new Set<string>();
  editingZoneId='';zoneFormName='';zoneColor='#55758A';zoneOrder=0;zoneOpen=true;zoneActive=true;
  readonly branchForm=signal(false);editingBranchId='';branchName='';branchAddress='';branchTables=8;branchActive=true;
  readonly devices=signal<StaffDevice[]>([]);
  /** Sección del centro de ajustes. */
  readonly section=signal<'BRAND'|'TABLES'|'ZONES'|'DEVICES'|'PRINTERS'|'BRANCHES'>('TABLES');
  readonly sections=computed(()=>[
    ...(this.auth.role()==='ORGANIZATION_ADMIN'?[{key:'BRAND' as const,label:'Marca y carta',icon:'palette',hint:'Logo, color y estilo del QR',count:null as number|null}]:[]),
    {key:'TABLES' as const,label:'Mesas y QR',icon:'chair',hint:'Nombre, capacidad y código QR',count:this.tables().filter(t=>t.active).length},
    {key:'ZONES' as const,label:'Zonas',icon:'map',hint:'Salón, terraza y quién atiende',count:this.zones().filter(z=>z.active).length},
    {key:'DEVICES' as const,label:'Tablets',icon:'phone',hint:'Ingreso rápido con PIN',count:this.devices().length},
    {key:'PRINTERS' as const,label:'Impresoras',icon:'receipt',hint:'Comandas en cocina y barra',count:null as number|null},
    ...(this.auth.role()==='ORGANIZATION_ADMIN'?[{key:'BRANCHES' as const,label:'Sucursales',icon:'store',hint:'Tus locales',count:this.branches().length}]:[]),
  ]);
  readonly search=signal('');
  readonly visibleTables=computed(()=>{const t=this.search().trim().toLowerCase();return this.tables().filter(x=>!t||x.label.toLowerCase().includes(t));});
  readonly zoneColors=['#2F8F9D','#E4572E','#765F93','#4F7D56','#B84A45','#C49A2C','#55758A','#4F8CFF','#EC4899'];
  /** Asignación de la zona abierta en el editor (mesas y meseros como chips). */
  zoneTablesSel=new Set<string>();zoneWaitersSel=new Set<string>();
  showSection(key:'BRAND'|'TABLES'|'ZONES'|'DEVICES'|'PRINTERS'|'BRANCHES'):void{this.section.set(key);this.search.set('');this.tableForm.set(false);this.zoneForm.set(false);this.branchForm.set(false);this.qrPreview.set(null);}
  editingTable():AdminTable|undefined{return this.tables().find(t=>t.id===this.editingTableId);}
  editingZone():Zone|undefined{return this.zones().find(z=>z.id===this.editingZoneId);}
  toggleZoneTable(id:string):void{const next=new Set(this.zoneTablesSel);if(next.has(id))next.delete(id);else next.add(id);this.zoneTablesSel=next;}
  toggleZoneWaiter(id:string):void{const next=new Set(this.zoneWaitersSel);if(next.has(id))next.delete(id);else next.add(id);this.zoneWaitersSel=next;}
  /** Guarda en un paso las mesas y los meseros elegidos para la zona. */
  saveZoneAssignments():void{const zone=this.editingZone();if(!zone)return;
    this.api.put(`/admin/zones/${zone.id}/tables`,{ids:[...this.zoneTablesSel]}).subscribe({next:()=>this.api.put(`/admin/zones/${zone.id}/waiters`,{ids:[...this.zoneWaitersSel]}).subscribe({next:()=>{this.message.set('Mesas y meseros de la zona actualizados.');this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar.')}),error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar.')});}
  readonly thisDeviceAuthorized=signal(this.hasDeviceToken());
  ngOnInit():void{const wanted=({brand:'BRAND',tables:'TABLES',zones:'ZONES',devices:'DEVICES',printers:'PRINTERS',branches:'BRANCHES'} as const)[this.route.snapshot.queryParamMap.get('s') as 'brand']; if(wanted&&this.sections().some(x=>x.key===wanted))this.section.set(wanted);this.api.get<{baseUrl:string}>('/public/config').subscribe(v=>this.publicBaseUrl=v.baseUrl.replace(/\/$/,''));this.load();}
  load():void{this.api.get<AdminTable[]>('/admin/operations/tables').subscribe(v=>this.tables.set(v));this.api.get<AdminUser[]>('/admin/operations/users').subscribe(v=>this.users.set(v));if(this.auth.role()==='ORGANIZATION_ADMIN')this.api.get<Branch[]>('/admin/branches').subscribe(v=>this.branches.set(v));this.api.get<StaffDevice[]>('/admin/devices').subscribe(v=>this.devices.set(v));this.api.get<Zone[]>('/admin/zones').subscribe(v=>this.zones.set(v));}
  activeZones():Zone[]{return this.zones().filter(zone=>zone.active);}
  zoneColorOf(id:string|null):string{return this.zones().find(zone=>zone.id===id)?.color||'#8b95a7';}
  zoneName(id:string|null):string{return this.zones().find(zone=>zone.id===id)?.name||'';}
  zoneStaff():AdminUser[]{return this.users().filter(user=>user.active&&(user.role==='WAITER'||user.role==='BRANCH_MANAGER'));}
  waiterNames(zone:Zone):string{const names=this.users().filter(user=>zone.waiterIds.includes(user.id)).map(user=>user.fullName.split(' ')[0]);return names.length?names.join(', '):'sin meseros asignados';}
  editZone(zone?:Zone):void{this.editingZoneId=zone?.id||'';this.zoneFormName=zone?.name||'';this.zoneColor=zone?.color||'#55758A';this.zoneOrder=zone?.displayOrder??this.zones().length;this.zoneOpen=zone?.open??true;this.zoneActive=zone?.active??true;this.zoneTablesSel=new Set(zone?this.tables().filter(t=>t.zoneId===zone.id).map(t=>t.id):[]);this.zoneWaitersSel=new Set(zone?.waiterIds||[]);this.zoneForm.set(true);}
  saveZone():void{const body={name:this.zoneFormName,color:this.zoneColor,displayOrder:this.zoneOrder,open:this.zoneOpen,active:this.zoneActive};const request=this.editingZoneId?this.api.patch(`/admin/zones/${this.editingZoneId}`,body):this.api.post('/admin/zones',body);request.subscribe({next:()=>{this.message.set('Zona guardada.');this.zoneForm.set(false);this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar la zona.')});}
  /** Abrir o cerrar una zona en el día (por ejemplo, la terraza cuando llueve). */
  toggleZone(zone:Zone):void{this.api.patch(`/admin/zones/${zone.id}`,{name:zone.name,color:zone.color,displayOrder:zone.displayOrder,open:!zone.open,active:zone.active}).subscribe({next:()=>{this.message.set(`${zone.name} ${zone.open?'cerrada':'abierta'}.`);this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo cambiar la zona.')});}
  /** Abre la asignación con la selección actual de la zona ya marcada. */
  openAssign(zone:Zone,kind:'tables'|'waiters'):void{this.assigned=new Set(kind==='tables'?this.tables().filter(table=>table.zoneId===zone.id).map(table=>table.id):zone.waiterIds);this.zoneAssign.set({zone,kind});}
  toggleAssigned(id:string):void{if(this.assigned.has(id))this.assigned.delete(id);else this.assigned.add(id);}
  saveAssignment():void{const assign=this.zoneAssign();if(!assign)return;this.api.put(`/admin/zones/${assign.zone.id}/${assign.kind}`,{ids:[...this.assigned]}).subscribe({next:()=>{this.message.set(assign.kind==='tables'?'Mesas de la zona actualizadas.':'Meseros de la zona actualizados.');this.zoneAssign.set(null);this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar.')});}
  async authorizeDevice():Promise<void>{
    const name=await this.dialog.prompt({title:'Autorizar este equipo',message:'Dale un nombre para reconocerlo, por ejemplo «Tablet salón» o «PC caja». Solo debes hacerlo en equipos que se quedan en el local.',inputLabel:'Nombre del equipo',initialValue:'Tablet salón',maxLength:80,required:true,confirmLabel:'Autorizar',icon:'▭'});
    if(!name)return;
    this.api.post<{deviceToken:string}>('/admin/devices',{name}).subscribe({next:value=>{try{localStorage.setItem('restos_device_token',value.deviceToken);}catch{this.message.set('El navegador no permite guardar la autorización en este equipo.');return;}this.thisDeviceAuthorized.set(true);this.message.set('Equipo autorizado. Al cerrar sesión aparecerá el ingreso con PIN.');this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo autorizar el equipo.')});
  }
  forgetDevice():void{try{localStorage.removeItem('restos_device_token');}catch{/* sin almacenamiento */}this.thisDeviceAuthorized.set(false);this.message.set('Este equipo volverá a pedir correo y contraseña. Para retirarle el acceso por completo, desautorízalo en la lista.');}
  async revokeDevice(device:StaffDevice):Promise<void>{const accepted=await this.dialog.confirm({title:'Desautorizar equipo',message:`${device.name} dejará de mostrar el ingreso con PIN de inmediato.`,confirmLabel:'Desautorizar',tone:'danger',icon:'!'});if(!accepted)return;this.api.delete(`/admin/devices/${device.id}`).subscribe({next:()=>{this.message.set('Equipo desautorizado.');this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo desautorizar el equipo.')});}
  dateLabel(value:string):string{return new Intl.DateTimeFormat('es-PE',{day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'}).format(new Date(value));}
  private hasDeviceToken():boolean{try{return !!localStorage.getItem('restos_device_token');}catch{return false;}}
  newTable():void{this.qrPreview.set(null);this.editingTableId='';this.tableLabel='';this.tableCapacity=4;this.tableActive=true;this.tableZoneId='';this.tableForm.set(true);}
  editTable(v:AdminTable):void{this.editingTableId=v.id;this.tableLabel=v.label;this.tableCapacity=v.capacity;this.tableActive=v.active;this.tableZoneId=v.zoneId||'';this.tableForm.set(true);this.showQr(v);}
  saveTable():void{const body={label:this.tableLabel,capacity:this.tableCapacity,active:this.tableActive,zoneId:this.tableZoneId||null};const request=this.editingTableId?this.api.patch(`/admin/operations/tables/${this.editingTableId}`,body):this.api.post('/admin/operations/tables',body);request.subscribe({next:()=>{this.message.set('Mesa guardada.');this.tableForm.set(false);this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar la mesa.')});}
  async showQr(table:AdminTable):Promise<void>{const link=`${this.publicBaseUrl}/diner?qr=${table.qrPublicId}`;this.qrPreview.set({table,link,dataUrl:await QRCode.toDataURL(link,{width:320,margin:2,errorCorrectionLevel:'M'})});}
  downloadQr():void{const value=this.qrPreview();if(!value)return;const link=document.createElement('a');link.href=value.dataUrl;link.download=`${value.table.label.replace(/\s+/g,'-').toLowerCase()}-qr.png`;link.click();}
  printQr():void{const value=this.qrPreview();if(!value)return;const printWindow=window.open('','_blank','width=520,height=650');if(!printWindow){this.message.set('El navegador bloqueó la ventana de impresión.');return;}const doc=printWindow.document;doc.body.style.cssText='font-family:sans-serif;text-align:center;padding:30px';const title=doc.createElement('h1');title.textContent=value.table.label;const image=doc.createElement('img');image.src=value.dataUrl;image.style.width='360px';const instruction=doc.createElement('p');instruction.textContent='Escanea para ver el menú';doc.body.append(title,image,instruction);image.onload=()=>printWindow.print();}
  async regenerateQr():Promise<void>{const value=this.qrPreview();if(!value)return;const accepted=await this.dialog.confirm({title:'Regenerar código QR',message:'El código anterior dejará de funcionar inmediatamente y deberá reemplazarse en la mesa.',confirmLabel:'Regenerar QR',tone:'danger',icon:'↻'});if(!accepted)return;this.api.post<AdminTable>(`/admin/operations/tables/${value.table.id}/regenerate-qr`,{}).subscribe({next:table=>{this.message.set('QR regenerado. Debes reemplazar el código impreso anterior.');this.load();this.showQr(table);},error:(e:any)=>this.message.set(e.error?.message||'No se pudo regenerar el QR.')});}
  newBranch():void{this.editingBranchId='';this.branchName='';this.branchAddress='';this.branchTables=8;this.branchActive=true;this.branchForm.set(true);}
  editBranch(branch:Branch):void{this.editingBranchId=branch.id;this.branchName=branch.name;this.branchAddress=branch.address||'';this.branchActive=branch.active;this.branchForm.set(true);}
  saveBranch():void{const request=this.editingBranchId?this.api.patch(`/admin/branches/${this.editingBranchId}`,{name:this.branchName,address:this.branchAddress,active:this.branchActive}):this.api.post('/admin/branches',{name:this.branchName,address:this.branchAddress,initialTables:this.branchTables});request.subscribe({next:()=>{this.message.set('Sucursal guardada.');this.branchForm.set(false);this.load();},error:(e:any)=>this.message.set(e.error?.message||'No se pudo guardar la sucursal.')});}
}
