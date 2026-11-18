import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { CountUpDirective } from '../../shared/ui/count-up.directive';
import { IconComponent } from '../../shared/ui/icon.component';
import { InfoTipComponent } from '../../shared/ui/info-tip.component';
import { ToastService } from '../../shared/ui/toast.service';
import { businessDate, localTimestamp, paymentMethodLabel, SalesReport } from './management.models';
import { WaiterRankingComponent } from './waiter-ranking.component';

interface BranchComparison { from:string; to:string; timezone:string; total:number; branches:{branchId:string;name:string;collected:number;payments:number;sessions:number;averageTicket:number;tips:number;counterOrders:number;voids:number;discounts:number;inventoryCost:number;grossMargin:number;topProduct:string|null}[]; }
type Preset='TODAY'|'YESTERDAY'|'WEEK'|'MONTH30'|'MONTH'|'CUSTOM';
type Tab='SUMMARY'|'PRODUCTS'|'LEDGER'|'BRANCHES';
interface Bar { key:string; label:string; tip:string; amount:number; ratio:number; best:boolean; showLabel:boolean; day?:string; }
interface Filters { from:string; to:string; method:string; unassigned:boolean; }
const TONES:Record<string,string>={CASH:'#44d62c',CARD:'#4f8cff',YAPE:'#a46bff',PLIN:'#2dd4bf'};

/** Reportes por pestañas: el período se elige una vez y se comparan sus resultados con el período anterior. */
@Component({imports:[AppShellComponent,FormsModule,RouterLink,CountUpDirective,IconComponent,WaiterRankingComponent,InfoTipComponent],templateUrl:'./reports.page.html',styleUrls:['./reports.scss','./reports-hero.scss','./reports-cards.scss','./reports-ledger.scss','./reports-motion.scss']})
export class ReportsPage implements OnInit,OnDestroy {
  private readonly api=inject(ApiService);
  private readonly http=inject(HttpClient);
  private readonly toast=inject(ToastService);
  private readonly route=inject(ActivatedRoute);
  private readonly auth=inject(AuthService);
  readonly report=signal<SalesReport|null>(null);
  readonly previous=signal<SalesReport|null>(null);
  readonly branches=signal<BranchComparison|null>(null);
  readonly branchesLoading=signal(false);
  readonly loading=signal(false);
  readonly exporting=signal(false);
  readonly error=signal('');
  readonly tab=signal<Tab>('SUMMARY');
  readonly preset=signal<Preset>('TODAY');
  readonly chart=signal<'DAYS'|'HOURS'>('DAYS');
  readonly methodLabel=paymentMethodLabel;
  readonly presets:{key:Preset;label:string}[]=[{key:'TODAY',label:'Hoy'},{key:'YESTERDAY',label:'Ayer'},{key:'WEEK',label:'7 días'},{key:'MONTH30',label:'30 días'},{key:'MONTH',label:'Este mes'}];
  readonly methods=['ALL','CASH','CARD','YAPE','PLIN'];
  readonly medals=['🥇','🥈','🥉'];
  from=businessDate();to=businessDate();method='ALL';unassigned=false;
  private applied:Filters={from:this.from,to:this.to,method:'ALL',unassigned:false};
  private request?:Subscription;
  private previousRequest?:Subscription;
  private download?:Subscription;

  readonly singleDay=computed(()=>{const r=this.report();return !!r&&r.from===r.to;});
  /** Con un solo día la tendencia diaria no dice nada: se muestran las horas. */
  readonly chartMode=computed(()=>this.singleDay()?'HOURS':this.chart());
  readonly ticket=computed(()=>this.avgTicket(this.report()));
  readonly salesTrend=computed(()=>this.trend(this.report()?.totals.collected,this.previous()?.totals.collected));
  readonly ticketTrend=computed(()=>this.trend(this.ticket(),this.avgTicket(this.previous())));
  readonly sessionsTrend=computed(()=>this.trend(this.report()?.totals.sessions,this.previous()?.totals.sessions));
  readonly bars=computed<Bar[]>(()=>{
    const r=this.report();if(!r)return [];
    if(this.chartMode()==='HOURS'){
      const hours=r.hours||[];if(!hours.length)return [];
      const min=Math.min(...hours.map(h=>h.hour)),max=Math.max(...hours.map(h=>h.hour));
      const top=Math.max(1,...hours.map(h=>Number(h.amount)));
      const list:Bar[]=[];
      for(let hour=min;hour<=max;hour++){const h=hours.find(item=>item.hour===hour);const amount=Number(h?.amount||0);
        list.push({key:'h'+hour,label:hour+'h',tip:`${hour}:00 – ${hour+1}:00 · ${h?.payments||0} cobros`,amount,ratio:amount/top*100,best:amount===top&&amount>0,showLabel:true});}
      return list;
    }
    const top=Math.max(1,...r.days.map(d=>Number(d.amount)));const step=Math.ceil(r.days.length/12);
    return r.days.map((d,index)=>({key:d.day,label:this.dayLabel(d.day,r.days.length>10),tip:this.longDay(d.day),amount:Number(d.amount),ratio:Number(d.amount)/top*100,best:Number(d.amount)===top&&Number(d.amount)>0,showLabel:index%step===0,day:d.day}));
  });
  readonly bestDay=computed(()=>{const r=this.report();if(!r||r.days.length<2)return null;const best=[...r.days].sort((a,b)=>Number(b.amount)-Number(a.amount))[0];return Number(best.amount)>0?best:null;});
  readonly peakHour=computed(()=>{const hours=this.report()?.hours||[];if(!hours.length)return null;return [...hours].sort((a,b)=>Number(b.amount)-Number(a.amount))[0];});
  readonly donut=computed(()=>{
    const r=this.report();if(!r||!r.methods.length)return 'conic-gradient(color-mix(in srgb,var(--text) 10%,transparent) 0 100%)';
    const total=r.methods.reduce((sum,m)=>sum+Number(m.amount),0)||1;let acc=0;
    return 'conic-gradient('+r.methods.map(m=>{const start=acc;acc+=Number(m.amount)/total*100;return `${this.methodTone(m.method)} ${start}% ${acc}%`;}).join(',')+')';
  });
  readonly margin=computed(()=>{const r=this.report();if(!r||!r.inventoryCost||!r.totals.collected)return null;return Math.round((1-r.inventoryCost/r.totals.collected)*1000)/10;});
  readonly maxProduct=computed(()=>Math.max(1,...(this.report()?.products.map(p=>p.quantity)||[])));
  readonly maxZone=computed(()=>Math.max(1,...(this.report()?.zones.map(z=>Number(z.amount))||[])));
  readonly maxBranch=computed(()=>Math.max(1,...(this.branches()?.branches.map(b=>Number(b.collected))||[])));
  /** Explica en una línea para qué sirve cada pestaña. */
  readonly tabPurpose=computed(()=>({
    SUMMARY:'Cuánto vendiste, cómo cobraste y si vas mejor o peor que antes.',
    PRODUCTS:'Qué platos se piden más y qué meseros atienden mejor.',
    LEDGER:'Cada pago registrado, para revisar, buscar diferencias y cuadrar.',
    BRANCHES:'Tus locales lado a lado en el mismo período.',
  } as Record<Tab,string>)[this.tab()]);
  /** Indicadores clave contra el período anterior equivalente. */
  readonly comparison=computed(()=>{
    const now=this.report(),before=this.previous();if(!now)return [];
    const row=(label:string,a:number|null,b:number|null|undefined,format:(v:number|null)=>string)=>({label,now:format(a),before:before?format(b??null):'…',delta:before?this.trend(a,b):null});
    const count=(v:number|null)=>String(v??0);
    return [
      row('Cobrado',Number(now.totals.collected),before?.totals.collected,v=>this.money(v)),
      row('Ticket promedio',this.ticket(),this.avgTicket(before),v=>this.money(v)),
      row('Atenciones',now.totals.sessions,before?.totals.sessions,count),
      row('Propinas',Number(now.totals.tips),before?.totals.tips,v=>this.money(v)),
    ];
  });
  readonly rangeLabel=computed(()=>{const r=this.report();if(!r)return '';return r.from===r.to?this.longDay(r.from):`${this.shortDate(r.from)} – ${this.shortDate(r.to)}`;});
  previousLabel():string{const p=this.previousRange(this.applied);return p.from===p.to?this.longDay(p.from):`${this.shortDate(p.from)} – ${this.shortDate(p.to)}`;}

  ngOnInit():void{
    this.unassigned=this.route.snapshot.queryParamMap.get('unassigned')==='true';
    if(this.unassigned)this.tab.set('LEDGER');
    this.load();
  }
  ngOnDestroy():void{this.request?.unsubscribe();this.previousRequest?.unsubscribe();this.download?.unsubscribe();}
  timestamp(value:string):string{return localTimestamp(value,this.report()?.timezone||'America/Lima');}

  /** Consulta el período; si cambió, también trae el período anterior equivalente para comparar. */
  load(page=0):void {
    const filters:Filters={from:this.from,to:this.to,method:this.method,unassigned:this.unassigned};
    if(!filters.from||!filters.to||filters.from>filters.to){this.toast.show('Revisa las fechas','Selecciona una fecha inicial anterior o igual a la final.','warning');return;}
    const periodChanged=filters.from!==this.applied.from||filters.to!==this.applied.to||!this.previous();
    this.request?.unsubscribe();this.loading.set(true);this.error.set('');
    this.request=this.api.get<SalesReport>(`/admin/reports/sales?${this.query(filters)}&page=${page}`).subscribe({next:value=>{
      this.applied={...filters};this.report.set(value);this.loading.set(false);
      if(periodChanged){this.branches.set(null);this.loadPrevious();if(this.tab()==='BRANCHES')this.loadBranches();}
    },error:e=>{this.loading.set(false);this.error.set(e.error?.message||'No se pudo consultar el reporte. Inténtalo nuevamente.');}});
  }
  private loadPrevious():void {
    this.previousRequest?.unsubscribe();this.previous.set(null);
    const range=this.previousRange(this.applied);
    this.previousRequest=this.api.get<SalesReport>(`/admin/reports/sales?${this.query({...range,method:'ALL',unassigned:false})}&page=0`).subscribe({next:value=>this.previous.set(value),error:()=>this.previous.set(null)});
  }
  choosePreset(key:Preset):void {
    this.preset.set(key);if(key==='CUSTOM')return;
    const today=businessDate();const shift=(days:number)=>{const d=new Date(today+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+days);return d.toISOString().slice(0,10);};
    if(key==='TODAY'){this.from=today;this.to=today;}
    else if(key==='YESTERDAY'){this.from=shift(-1);this.to=shift(-1);}
    else if(key==='WEEK'){this.from=shift(-6);this.to=today;}
    else if(key==='MONTH30'){this.from=shift(-29);this.to=today;}
    else {this.from=today.slice(0,8)+'01';this.to=today;}
    this.load();
  }
  /** Clic en una barra diaria: abre ese día. */
  drill(bar:Bar):void {if(!bar.day||this.loading())return;this.from=bar.day;this.to=bar.day;this.preset.set('CUSTOM');this.load();}
  showTab(tab:Tab):void {this.tab.set(tab);if(tab==='BRANCHES'&&!this.branches()&&!this.branchesLoading())this.loadBranches();}
  setMethod(method:string):void {this.method=method;this.reloadLedger();}
  toggleUnassigned():void {this.unassigned=!this.unassigned;this.reloadLedger();}
  openUnassigned():void {this.tab.set('LEDGER');if(!this.unassigned){this.unassigned=true;this.reloadLedger();}}
  /** Los filtros del historial usan el período ya consultado, no fechas editadas sin aplicar. */
  private reloadLedger():void {this.from=this.applied.from;this.to=this.applied.to;this.load(0);}
  page(page:number):void {this.from=this.applied.from;this.to=this.applied.to;this.method=this.applied.method;this.unassigned=this.applied.unassigned;this.load(page);}

  /** Descarga el reporte con los filtros ya aplicados, en CSV o en Excel con varias hojas. */
  export(format:'csv'|'xlsx'='csv'):void {
    if(this.exporting()||!this.report())return;
    this.exporting.set(true);
    const path=format==='xlsx'?'sales/export.xlsx':'sales/export';
    this.download=this.http.get(`/api/admin/reports/${path}?${this.query(this.applied)}`,{responseType:'blob'}).subscribe({next:blob=>{
      this.saveFile(blob,`${format==='xlsx'?'reporte':'cobros'}-${this.applied.from}-${this.applied.to}.${format}`);this.exporting.set(false);
      this.toast.show('Reporte exportado',format==='xlsx'?'Excel con resumen, días, medios de pago, platos y cobros.':'Se descargaron todos los cobros del filtro aplicado, no solo la página visible.','success');
    },error:async e=>{
      this.exporting.set(false);let message='No se pudo exportar. Reduce el período o vuelve a intentarlo.';
      try{const body=JSON.parse(await e.error.text());message=body.message||message;}catch{}
      this.toast.show('Exportación no completada',message,'error');
    }});
  }
  /** Comparativo entre sucursales (solo para el dueño de la organización). */
  loadBranches():void{
    this.branchesLoading.set(true);
    this.api.get<BranchComparison>(`/admin/reports/branches?from=${this.applied.from}&to=${this.applied.to}`).subscribe({
      next:value=>{this.branches.set(value);this.branchesLoading.set(false);},
      error:e=>{this.branchesLoading.set(false);this.toast.show('No se pudo comparar',e.error?.message||'Inténtalo nuevamente.','error');}
    });
  }
  exportBranches():void{
    const data=this.branches();if(!data)return;
    this.http.get(`/api/admin/reports/branches/export.xlsx?from=${data.from}&to=${data.to}`,{responseType:'blob'}).subscribe({
      next:blob=>this.saveFile(blob,`sucursales-${data.from}-${data.to}.xlsx`),
      error:()=>this.toast.show('No se pudo exportar','Inténtalo nuevamente.','error')
    });
  }
  /** Hoy aún no termina: se avisa que el período anterior sí está completo. */
  includesToday():boolean{return this.report()?.to===businessDate();}
  totalPages(r:SalesReport):number{return Math.max(1,Math.ceil(r.paymentCount/r.pageSize));}
  isOwner():boolean{return this.auth.role()==='ORGANIZATION_ADMIN';}
  methodTone(method:string):string{return TONES[method]||'#ffb347';}
  methodShare(amount:number):number{const r=this.report();const total=r?.methods.reduce((sum,m)=>sum+Number(m.amount),0)||0;return total?Math.round(Number(amount)/total*100):0;}
  money(value:number|null|undefined):string{return `S/ ${Number(value||0).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2})}`;}
  compact(value:number):string{return value>=1000?`S/ ${(value/1000).toLocaleString('en-US',{maximumFractionDigits:1})}k`:`S/ ${Math.round(value)}`;}
  longDay(day:string):string{const text=new Date(day+'T12:00:00Z').toLocaleDateString('es-PE',{timeZone:'UTC',weekday:'long',day:'numeric',month:'short'});return text.charAt(0).toUpperCase()+text.slice(1);}
  private dayLabel(day:string,compact:boolean):string{const d=new Date(day+'T12:00:00Z');return compact?String(d.getUTCDate()):d.toLocaleDateString('es-PE',{timeZone:'UTC',weekday:'short'}).replace('.','')+' '+d.getUTCDate();}
  private shortDate(day:string):string{return new Date(day+'T12:00:00Z').toLocaleDateString('es-PE',{timeZone:'UTC',day:'numeric',month:'short'}).replace('.','');}
  private avgTicket(r:SalesReport|null):number|null{return r&&r.totals.sessions?Number(r.totals.collected)/r.totals.sessions:null;}
  /** Variación porcentual frente al período anterior; null si no hay base para comparar. */
  private trend(now:number|null|undefined,before:number|null|undefined):number|null{if(now==null||before==null||!Number(before))return null;return Math.round((Number(now)/Number(before)-1)*1000)/10;}
  private previousRange(f:{from:string;to:string}):{from:string;to:string}{
    const start=new Date(f.from+'T12:00:00Z'),end=new Date(f.to+'T12:00:00Z');const days=Math.round((end.getTime()-start.getTime())/86400000)+1;
    const to=new Date(start);to.setUTCDate(to.getUTCDate()-1);const from=new Date(to);from.setUTCDate(from.getUTCDate()-days+1);
    return {from:from.toISOString().slice(0,10),to:to.toISOString().slice(0,10)};
  }
  private saveFile(blob:Blob,name:string):void{const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=name;link.click();window.setTimeout(()=>URL.revokeObjectURL(url),1000);}
  private query(filters:Filters):string{return new URLSearchParams({...filters,unassigned:String(filters.unassigned)}).toString();}
}
