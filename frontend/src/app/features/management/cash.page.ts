import { Component, HostListener, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { CashDetail, CashOverview, CashShift, localTimestamp, paymentMethodLabel } from './management.models';

type CashAction='NONE'|'MOVEMENT'|'CLOSE';
interface Denomination { value:number; label:string; coin:boolean; qty:number; }
const DENOMINATIONS:[number,string,boolean][]=[[200,'200',false],[100,'100',false],[50,'50',false],[20,'20',false],[10,'10',false],[5,'5',true],[2,'2',true],[1,'1',true],[.5,'0.50',true],[.2,'0.20',true],[.1,'0.10',true]];

/** Caja administrativa: las consultas se cancelan al salir y las escrituras conservan su clave al reintentar. */
@Component({imports:[AppShellComponent,FormsModule,IconComponent,NgTemplateOutlet,RouterLink],templateUrl:'./cash.page.html',styleUrls:['./cash.scss','./cash-panels.scss','./cash-forms.scss','./cash-history.scss','./cash-count.scss','./cash-nav.scss']})
export class CashPage implements OnInit,OnDestroy {
  private readonly api=inject(ApiService);
  private readonly toast=inject(ToastService);
  private readonly dialog=inject(DialogService);
  readonly overview=signal<CashOverview|null>(null);
  /** Turno abierto de la sucursal (null si la caja está cerrada). */
  readonly detail=signal<CashDetail|null>(null);
  /** Turno elegido en la pestaña Historial. */
  readonly past=signal<CashDetail|null>(null);
  readonly pastLoading=signal(false);
  readonly loading=signal(true);
  readonly saving=signal(false);
  readonly error=signal('');
  readonly tab=signal<'NOW'|'HISTORY'>('NOW');
  readonly pane=signal<'METHODS'|'MOVES'>('METHODS');
  readonly action=signal<CashAction>('NONE');
  readonly countMode=signal<'BILLS'|'TOTAL'>('BILLS');
  readonly methodLabel=paymentMethodLabel;
  readonly quickAmounts=[10,20,50,100];
  readonly outReasons=['Compra de insumos','Pago a proveedor','Gas o servicios','Retiro a caja fuerte'];
  readonly inReasons=['Reposición de sencillo','Aporte del dueño','Devolución de vuelto'];
  denominations:Denomination[]=this.freshDenominations();
  openingAmount:number|null=0; openingNote='';
  movementKind:'IN'|'OUT'='OUT'; movementAmount:number|null=null; movementReason='';
  counted:number|null=null; closingNote='';
  private pastId:string|null=null;
  private read?:Subscription;
  private detailRead?:Subscription;
  private write?:Subscription;
  private poll?:number;
  private openKey={id:'',payload:''};
  private movementKey={id:'',payload:''};
  ngOnInit():void { this.load(); this.poll=window.setInterval(()=>{if(!document.hidden&&!this.saving()&&!this.loading())this.load(false);},30000); }
  ngOnDestroy():void { this.read?.unsubscribe();this.detailRead?.unsubscribe();this.write?.unsubscribe();window.clearInterval(this.poll); }

  /** Atajos: S salida, E entrada, C cerrar, H historial, A caja actual, Esc vuelve. */
  @HostListener('document:keydown',['$event'])
  shortcuts(event:KeyboardEvent):void {
    if(document.querySelector('app-dialog-host [role=dialog]'))return;
    if(event.key==='Escape'){if(this.action()!=='NONE'&&!this.saving()){this.action.set('NONE');event.preventDefault();}return;}
    const target=event.target instanceof Element?event.target:null;
    if(event.ctrlKey||event.metaKey||event.altKey||target?.closest('input,textarea,select,[contenteditable]'))return;
    const key=event.key.toLowerCase();
    const open=!!this.detail()&&!this.error()&&!this.saving();
    if(key==='h'){this.showTab('HISTORY');}
    else if(key==='a'){this.showTab('NOW');}
    else if(key==='s'&&open){this.showTab('NOW');this.showMovement('OUT');}
    else if(key==='e'&&open){this.showTab('NOW');this.showMovement('IN');}
    else if(key==='c'&&open){this.showTab('NOW');this.showClose();}
    else return;
    event.preventDefault();
  }

  timestamp(value:string):string{return localTimestamp(value,this.overview()?.timezone||'America/Lima');}
  load(showLoading=true):void {
    this.read?.unsubscribe();
    if(showLoading)this.loading.set(true);
    this.error.set('');
    this.read=this.api.get<CashOverview>('/admin/cash').subscribe({next:value=>{
      // Si otro administrador cerró o abrió turno, no reutiliza un formulario del turno anterior.
      if(this.detail()?.shift.id!==value.active?.shift.id)this.action.set('NONE');
      this.overview.set(value);this.detail.set(value.active);this.loading.set(false);
      if(this.tab()==='HISTORY')this.refreshPast(value);
    },error:()=>{this.loading.set(false);this.error.set('No se pudo actualizar la caja. Reintenta antes de registrar movimientos.');}});
  }
  showTab(tab:'NOW'|'HISTORY'):void {
    if(this.tab()===tab)return;
    this.tab.set(tab);
    const data=this.overview();
    if(tab==='HISTORY'&&data)this.refreshPast(data);
  }
  /** Mantiene el turno elegido o abre el más reciente al entrar al historial. */
  private refreshPast(data:CashOverview):void {
    const id=this.pastId&&data.history.some(item=>item.id===this.pastId)?this.pastId:data.history[0]?.id;
    if(id)this.selectPast(id,false);else this.past.set(null);
  }
  selectPast(id:string,showLoading=true):void {
    this.pastId=id;
    const active=this.detail();
    if(active?.shift.id===id){this.detailRead?.unsubscribe();this.past.set(active);this.pastLoading.set(false);return;}
    this.detailRead?.unsubscribe();
    if(showLoading||this.past()?.shift.id!==id)this.pastLoading.set(true);
    this.detailRead=this.api.get<CashDetail>(`/admin/cash/shifts/${id}`).subscribe({next:value=>{this.past.set(value);this.pastLoading.set(false);},error:()=>{this.pastLoading.set(false);this.toast.show('No se pudo consultar este turno','Revisa la conexión e inténtalo nuevamente.','error');}});
  }
  openInHistory(id:string):void {this.pastId=id;if(this.tab()==='HISTORY')this.selectPast(id);else this.showTab('HISTORY');}

  /** Ícono y color de cada medio de pago para leer el desglose de un vistazo. */
  methodIcon(method:string):string{return ({CASH:'money',CARD:'card',YAPE:'smartphone',PLIN:'smartphone'} as Record<string,string>)[method]||'coins';}
  diffIcon(diff:number|null):string{return diff===null?'coins':diff===0?'check':diff>0?'arrow-up':'arrow-down';}
  methodTone(method:string):string{return ({CASH:'green',CARD:'blue',YAPE:'violet',PLIN:'teal'} as Record<string,string>)[method]||'amber';}
  totalCollected(detail:CashDetail):number{return detail.methods.reduce((sum,item)=>sum+Number(item.amount),0);}
  methodRatio(detail:CashDetail,amount:number):number{const total=this.totalCollected(detail);return total>0?Number(amount)/total*100:0;}
  /** Diferencia mientras se cuenta el efectivo: positiva sobra, negativa falta. */
  liveDifference(detail:CashDetail):number|null{return this.counted===null||!Number.isFinite(this.counted)?null:Math.round((this.counted-detail.expectedCash)*100)/100;}
  historyDifference(shift:CashShift):number|null{return shift.countedAmount===null||shift.expectedAmount===null?null:Math.round((Number(shift.countedAmount)-Number(shift.expectedAmount))*100)/100;}
  resultState(diff:number|null):string{return diff===null?'none':diff===0?'ok':diff>0?'over':'short';}
  resultText(diff:number|null):string{return diff===null?'Sin arqueo':diff===0?'La caja cuadró':diff>0?'Sobraron '+this.money(diff):'Faltaron '+this.money(-diff);}
  /** Último turno cerrado, para mostrarlo mientras la caja está cerrada. */
  lastClosed():CashShift|null{return this.overview()?.history.find(item=>!!item.closedAt)||null;}
  duration(from:string,to:string|null):string{const minutes=Math.max(0,Math.round(((to?new Date(to).getTime():Date.now())-new Date(from).getTime())/60000));const h=Math.floor(minutes/60);return h?`${h} h ${minutes%60} min`:`${minutes} min`;}
  money(value:number|null|undefined):string{return `S/ ${Number(value||0).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2})}`;}
  validAmount(value:number|null,positive=false):boolean{return value!==null&&Number.isFinite(value)&&value>=(positive ? .01 : 0)&&value<=9999999999.99&&Math.abs(value*100-Math.round(value*100))<.0001;}

  showMovement(kind:'IN'|'OUT'):void {
    if(this.action()!=='MOVEMENT'){this.movementAmount=null;this.movementReason='';}
    this.movementKind=kind;this.action.set('MOVEMENT');this.focusAmount();
  }
  showClose():void {
    if(this.action()!=='CLOSE'){this.counted=null;this.closingNote='';this.denominations=this.freshDenominations();}
    this.action.set('CLOSE');this.focusAmount();
  }
  addAmount(value:number):void {this.movementAmount=Math.round(((this.movementAmount||0)+value)*100)/100;}
  setCountMode(mode:'BILLS'|'TOTAL'):void {this.countMode.set(mode);if(mode==='BILLS')this.syncCounted();this.focusAmount();}
  step(item:Denomination,delta:number):void {item.qty=Math.max(0,(item.qty||0)+delta);this.syncCounted();}
  setQty(item:Denomination,value:number|null):void {item.qty=Math.max(0,Math.floor(Number(value)||0));this.syncCounted();}
  billsTotal(coins:boolean):number{return Math.round(this.denominations.filter(item=>item.coin===coins).reduce((sum,item)=>sum+item.value*item.qty,0)*100)/100;}
  /** El conteo por billetes y monedas alimenta el mismo campo que se envía al servidor. */
  private syncCounted():void {const any=this.denominations.some(item=>item.qty>0);this.counted=any?Math.round(this.denominations.reduce((sum,item)=>sum+item.value*item.qty,0)*100)/100:null;}
  private freshDenominations():Denomination[]{return DENOMINATIONS.map(([value,label,coin])=>({value,label,coin,qty:0}));}
  private focusAmount():void {setTimeout(()=>(document.querySelector('.cs-work [data-autofocus]') as HTMLElement|null)?.focus(),60);}

  open():void {
    if(this.saving()||!this.validAmount(this.openingAmount))return;
    const payload={amount:this.openingAmount,note:this.openingNote.trim()};
    this.submit('/admin/cash/open',{...payload,id:this.key(this.openKey,payload)},'Caja abierta');
  }
  movement():void {
    const id=this.detail()?.shift.id;
    if(!id||this.saving()||!this.validAmount(this.movementAmount,true)||!this.movementReason.trim())return;
    const payload={kind:this.movementKind,amount:this.movementAmount,reason:this.movementReason.trim()};
    this.submit(`/admin/cash/shifts/${id}/movements`,{...payload,id:this.key(this.movementKey,{shift:id,...payload})},'Movimiento registrado',()=>this.pane.set('MOVES'));
  }
  async close():Promise<void> {
    const detail=this.detail();
    if(!detail||this.saving()||!this.validAmount(this.counted))return;
    // El servidor vuelve a calcular el saldo bajo bloqueo; esta comparación es solo ayuda visual.
    if(Math.round((this.counted!-detail.expectedCash)*100)!==0&&!this.closingNote.trim())return;
    const body={counted:this.counted,note:this.closingNote.trim()};
    this.saving.set(true);
    const accepted=await this.dialog.confirm({title:'Cerrar y arquear caja',message:`Contaste ${this.money(this.counted)}. Se guardará el arqueo y el turno quedará cerrado, sin posibilidad de editar sus movimientos. Los pagos posteriores no pertenecerán a este turno.`,confirmLabel:'Confirmar cierre',tone:'warning'});
    this.saving.set(false);
    if(accepted)this.submit(`/admin/cash/shifts/${detail.shift.id}/close`,body,'Caja cerrada');
  }
  private key(target:{id:string;payload:string},body:unknown):string {
    const payload=JSON.stringify(body);
    if(target.payload!==payload||!target.id){target.payload=payload;target.id=this.uuid();}
    return target.id;
  }
  /** Funciona también por HTTP en la red local, donde randomUUID puede no estar disponible. */
  private uuid():string { const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;const h=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; }
  private submit(path:string,body:unknown,title:string,after?:()=>void):void {
    this.saving.set(true);
    this.write=this.api.post<CashDetail>(path,body).subscribe({next:value=>{
      this.saving.set(false);this.action.set('NONE');
      this.detail.set(value.shift.closedAt?null:value);
      this.openKey={id:'',payload:''};this.movementKey={id:'',payload:''};
      after?.();
      this.toast.show(title,'El registro quedó guardado en esta sucursal.','success');this.load(false);
    },error:e=>{this.saving.set(false);this.toast.show('No se pudo completar',e.error?.message||'Revisa la conexión e inténtalo nuevamente.','error');this.load(false);}});
  }
}
