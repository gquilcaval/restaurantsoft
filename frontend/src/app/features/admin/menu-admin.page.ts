import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { ImageUploadService } from '../../core/api/image-upload.service';
import { IngredientView, ModifierGroupView, RecipeView, UNIT_LABELS } from '../../core/api/models';
import { AppShellComponent } from '../../shared/ui/app-shell.component';
import { DialogService } from '../../shared/ui/dialog.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { ToastService } from '../../shared/ui/toast.service';
import { MENU_TAGS } from '../../shared/ordering/menu-tags';

interface Category { id:string; name:string; displayOrder:number; active:boolean; stationId:string|null; }
interface Station { id:string; name:string; displayOrder:number; active:boolean; }
interface MenuProduct {
  id:string; categoryId:string; categoryName:string; name:string; description:string|null; price:number;
  imageUrl:string|null; active:boolean; available:boolean; modifierGroups:number; tags:string[];
}
interface OptionDraft { name:string; priceDelta:number; ingredientId:string; ingredientQuantity:number; }
interface GroupDraft { name:string; required:boolean; maxSelect:number; options:OptionDraft[]; }

/** Catálogo administrable del restaurante: categorías, productos, fotos, opciones y disponibilidad. */
@Component({
  imports: [AppShellComponent, FormsModule, CurrencyPipe, DecimalPipe, IconComponent],
  templateUrl: './menu-admin.page.html',
  styleUrls: ['./menu-admin.scss', './menu-admin-grid.scss', './menu-admin-editor.scss', './menu-admin-cards.scss', './menu-admin-extra.scss'],})
export class MenuAdminPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly uploads = inject(ImageUploadService);
  private readonly dialog = inject(DialogService);
  private readonly toast = inject(ToastService);
  readonly selectedCategory = signal('');
  readonly search = signal('');
  readonly filter = signal<'ALL'|'AVAILABLE'|'SOLD_OUT'|'HIDDEN'|'NO_PHOTO'>('ALL');
  readonly panel = signal<'NONE'|'PRODUCT'>('NONE');
  readonly editorTab = signal<'DATA'|'OPTIONS'|'RECIPE'>('DATA');
  /** Productos de la categoría elegida, con búsqueda y filtro rápido. */
  readonly visibleProducts = computed(() => {
    const term = this.search().trim().toLocaleLowerCase(); const category = this.selectedCategory(); const filter = this.filter();
    return this.products().filter(item => (!category || item.categoryId === category)
      && (!term || item.name.toLocaleLowerCase().includes(term) || (item.description || '').toLocaleLowerCase().includes(term))
      && (filter === 'ALL' || (filter === 'AVAILABLE' && item.active && item.available) || (filter === 'SOLD_OUT' && item.active && !item.available)
        || (filter === 'HIDDEN' && !item.active) || (filter === 'NO_PHOTO' && !item.imageUrl)));
  });
  /** Cifras del catálogo con acciones sugeridas. */
  readonly stats = computed(() => {
    const list = this.products();
    return { total: list.length, available: list.filter(i => i.active && i.available).length, soldOut: list.filter(i => i.active && !i.available).length,
      hidden: list.filter(i => !i.active).length, noPhoto: list.filter(i => !i.imageUrl).length, noDescription: list.filter(i => !i.description).length,
      withOptions: list.filter(i => i.modifierGroups > 0).length };
  });
  countIn(categoryId: string): number { return this.products().filter(item => item.categoryId === categoryId).length; }
  categoryLabel(categoryId: string): string { return this.categories().find(item => item.id === categoryId)?.name || ''; }
  newProduct(): void { this.resetProduct(); if (this.selectedCategory()) this.productCategoryId = this.selectedCategory(); this.editorTab.set('DATA'); this.panel.set('PRODUCT'); }
  closePanel(): void { this.panel.set('NONE'); this.resetProduct(); }
  showFilter(filter: 'ALL'|'AVAILABLE'|'SOLD_OUT'|'HIDDEN'|'NO_PHOTO'): void { this.filter.set(filter); this.selectedCategory.set(''); }
  readonly stations = signal<Station[]>([]);
  readonly menuTags = MENU_TAGS;
  /** Etiquetas del producto en edición (picante, vegano…). */
  productTags = new Set<string>();
  tagIcon(tag:string):string{return MENU_TAGS.find(item=>item.value===tag)?.icon||'';}
  toggleTag(tag:string):void{const next=new Set(this.productTags);if(next.has(tag))next.delete(tag);else next.add(tag);this.productTags=next;}
  readonly categories = signal<Category[]>([]); readonly products = signal<MenuProduct[]>([]);
  readonly categoryForm = signal(false); readonly message = signal(''); readonly error = signal('');
  readonly uploading = signal(false);
  readonly groups = signal<GroupDraft[]>([]);
  readonly savingGroups = signal(false);
  readonly togglingId = signal<string|null>(null);
  readonly ingredients = signal<IngredientView[]>([]);
  readonly recipe = signal<RecipeView|null>(null);
  readonly recipeLines = signal<{ingredientId:string;quantity:number}[]>([]);
  readonly savingRecipe = signal(false);
  editingCategoryId = ''; categoryName = ''; categoryOrder = 0; categoryActive = true; categoryStationId = '';
  editingProductId = ''; productCategoryId = ''; productName = ''; productDescription = '';
  productPrice = 0; productImageUrl = ''; productActive = true;

  ngOnInit(): void { this.load(); }
  activeCategories(): Category[] { return this.categories().filter(value => value.active || value.id === this.productCategoryId); }
  load(): void {
    this.api.get<Category[]>('/admin/menu/categories').subscribe(value => { this.categories.set(value); if(!this.productCategoryId&&value.length)this.productCategoryId=value[0].id; });
    this.api.get<MenuProduct[]>('/admin/menu/items').subscribe(value => this.products.set(value));
    this.api.get<Station[]>('/admin/menu/stations').subscribe(value => this.stations.set(value));
    this.api.get<IngredientView[]>('/admin/inventory/ingredients').subscribe(value => this.ingredients.set(value.filter(item => item.active)));
  }
  stationName(id:string|null):string{return this.stations().find(station=>station.id===id)?.name||'';}
  /** Las estaciones separan la preparación: cada pantalla de cocina puede mostrar solo la suya. */
  async editStation(station?:Station):Promise<void>{
    const name=await this.dialog.prompt({title:station?'Renombrar estación':'Nueva estación',message:'Ej. Cocina caliente, Barra, Postres. Asigna cada categoría a su estación para separar las comandas.',inputLabel:'Nombre',initialValue:station?.name||'',maxLength:60,required:true,confirmLabel:'Guardar',icon:'♨'});
    if(!name)return;
    const body={name,displayOrder:station?.displayOrder??this.stations().length,active:true};
    const request=station?this.api.patch(`/admin/menu/stations/${station.id}`,body):this.api.post('/admin/menu/stations',body);
    request.subscribe({next:()=>{this.notify('Estación guardada.');this.load();},error:(err:any)=>this.fail(err.error?.message||'No se pudo guardar la estación.')});
  }
  newCategory(): void { this.editingCategoryId='';this.categoryName='';this.categoryOrder=this.categories().length;this.categoryActive=true;this.categoryStationId='';this.categoryForm.set(true); }
  editCategory(value:Category):void{this.editingCategoryId=value.id;this.categoryName=value.name;this.categoryOrder=value.displayOrder;this.categoryActive=value.active;this.categoryStationId=value.stationId||'';this.categoryForm.set(true);}
  saveCategory():void{const body={name:this.categoryName,displayOrder:this.categoryOrder,active:this.categoryActive,stationId:this.categoryStationId||null};const request=this.editingCategoryId?this.api.patch(`/admin/menu/categories/${this.editingCategoryId}`,body):this.api.post('/admin/menu/categories',body);request.subscribe({next:()=>{this.notify('Categoría guardada.');this.categoryForm.set(false);this.load();},error:(err:any)=>this.fail(err.error?.message||'No se pudo guardar la categoría.')});}

  editProduct(value:MenuProduct):void{this.productTags=new Set(value.tags||[]);
    if(this.editingProductId!==value.id)this.editorTab.set('DATA');this.editingProductId=value.id;this.productCategoryId=value.categoryId;this.productName=value.name;this.productDescription=value.description||'';
    this.productPrice=Number(value.price);this.productImageUrl=value.imageUrl||'';this.productActive=value.active;
    this.api.get<ModifierGroupView[]>(`/admin/menu/items/${value.id}/modifiers`).subscribe(groups=>this.groups.set(groups.map(group=>({
      name:group.name,required:group.minSelect>0,maxSelect:group.maxSelect,
      options:group.options.map(option=>({name:option.name,priceDelta:Number(option.priceDelta),ingredientId:option.ingredientId||'',ingredientQuantity:Number(option.ingredientQuantity)||0}))}))));
    this.recipe.set(null);this.recipeLines.set([]);
    this.api.get<RecipeView>(`/admin/inventory/recipes/${value.id}`).subscribe(recipe=>{this.recipe.set(recipe);this.recipeLines.set(recipe.lines.map(line=>({ingredientId:line.ingredientId,quantity:Number(line.quantity)})));});
    this.panel.set('PRODUCT');
  }
  resetProduct():void{this.productTags=new Set();this.editingProductId='';this.productName='';this.productDescription='';this.productPrice=0;this.productImageUrl='';this.productActive=true;this.groups.set([]);if(this.categories().length)this.productCategoryId=this.categories()[0].id;}
  saveProduct():void{const body={categoryId:this.productCategoryId,name:this.productName,description:this.productDescription||null,price:this.productPrice,imageUrl:this.productImageUrl||null,active:this.productActive,tags:[...this.productTags]};const editing=!!this.editingProductId;const request=editing?this.api.patch<MenuProduct>(`/admin/menu/items/${this.editingProductId}`,body):this.api.post<MenuProduct>('/admin/menu/items',body);request.subscribe({next:product=>{this.load();if(editing){this.notify('Producto guardado.');return;}
    // Un producto nuevo queda abierto para que se le puedan agregar opciones de inmediato.
    this.notify('Producto creado. Ahora puedes agregarle opciones y extras.');this.editProduct(product);},error:(err:any)=>this.fail(err.error?.message||'No se pudo guardar el producto.')});}

  async uploadImage(event:Event):Promise<void>{
    const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';
    if(!file)return;
    this.uploading.set(true);this.error.set('');
    try{this.productImageUrl=await this.uploads.upload(file);this.notify('Foto cargada. Guarda el producto para publicarla.');}
    catch(err:any){this.fail(err?.error?.message||err?.message||'No se pudo subir la foto.');}
    finally{this.uploading.set(false);}
  }

  /** Agotado aplica solo a la sucursal actual y no oculta el producto de la carta. */
  toggleAvailability(product:MenuProduct,event:Event):void{
    event.stopPropagation();
    this.togglingId.set(product.id);
    this.api.put(`/staff/menu/items/${product.id}/availability`,{available:!product.available}).subscribe({
      next:()=>{this.togglingId.set(null);this.products.update(list=>list.map(value=>value.id===product.id?{...value,available:!product.available}:value));},
      error:(err:any)=>{this.togglingId.set(null);this.fail(err.error?.message||'No se pudo cambiar la disponibilidad.');}
    });
  }

  addGroup():void{this.groups.update(list=>[...list,{name:'',required:false,maxSelect:1,options:[{name:'',priceDelta:0,ingredientId:'',ingredientQuantity:0}]}]);}
  removeGroup(index:number):void{this.groups.update(list=>list.filter((_,position)=>position!==index));}
  addOption(group:GroupDraft):void{group.options.push({name:'',priceDelta:0,ingredientId:'',ingredientQuantity:0});this.groups.update(list=>[...list]);}
  removeOption(group:GroupDraft,index:number):void{group.options.splice(index,1);this.groups.update(list=>[...list]);}
  saveGroups():void{
    if(!this.editingProductId)return;
    const payload=this.groups().map(group=>{
      const options=group.options.filter(option=>option.name.trim()).map(option=>{
        const consumes=!!option.ingredientId&&Number(option.ingredientQuantity)>0;
        return {name:option.name.trim(),priceDelta:Number(option.priceDelta)||0,ingredientId:consumes?option.ingredientId:null,ingredientQuantity:consumes?Number(option.ingredientQuantity):null};
      });
      const maxSelect=Math.max(1,Math.min(Number(group.maxSelect)||1,options.length||1));
      return {name:group.name.trim(),minSelect:group.required?1:0,maxSelect,options};
    });
    const invalid=payload.find(group=>!group.name||!group.options.length);
    if(invalid){this.fail('Cada grupo necesita un nombre y al menos una opción.');return;}
    this.savingGroups.set(true);
    this.api.put<ModifierGroupView[]>(`/admin/menu/items/${this.editingProductId}/modifiers`,payload).subscribe({
      next:()=>{this.savingGroups.set(false);this.notify('Opciones guardadas.');this.load();},
      error:(err:any)=>{this.savingGroups.set(false);this.fail(err.error?.message||'No se pudieron guardar las opciones.');}
    });
  }
  /** Costo estimado con los costos actuales de los insumos, antes de guardar la receta. */
  recipeCost():number{return this.recipeLines().reduce((sum,line)=>sum+(Number(this.ingredient(line.ingredientId)?.costPerUnit)||0)*(Number(line.quantity)||0),0);}
  foodCostPercent():number{return this.productPrice>0?this.recipeCost()/this.productPrice*100:0;}
  ingredient(id:string):IngredientView|undefined{return this.ingredients().find(item=>item.id===id);}
  unitLabel(id:string):string{const unit=this.ingredient(id)?.unit;return unit?UNIT_LABELS[unit]:'';}
  addRecipeLine():void{const used=new Set(this.recipeLines().map(line=>line.ingredientId));const next=this.ingredients().find(item=>!used.has(item.id));if(!next){this.fail('Registra más insumos en Inventario para agregarlos a la receta.');return;}this.recipeLines.update(lines=>[...lines,{ingredientId:next.id,quantity:0}]);}
  removeRecipeLine(index:number):void{this.recipeLines.update(lines=>lines.filter((_,position)=>position!==index));}
  touchRecipe():void{this.recipeLines.update(lines=>[...lines]);}
  saveRecipe():void{
    if(!this.editingProductId)return;
    const lines=this.recipeLines().filter(line=>line.ingredientId&&Number(line.quantity)>0);
    this.savingRecipe.set(true);
    this.api.put<RecipeView>(`/admin/inventory/recipes/${this.editingProductId}`,lines).subscribe({
      next:recipe=>{this.savingRecipe.set(false);this.recipe.set(recipe);this.recipeLines.set(recipe.lines.map(line=>({ingredientId:line.ingredientId,quantity:Number(line.quantity)})));this.notify('Receta guardada. El stock se descontará al enviar este plato a cocina.');},
      error:(err:any)=>{this.savingRecipe.set(false);this.fail(err.error?.message||'No se pudo guardar la receta.');}
    });
  }
  private notify(text:string):void{this.toast.show('Listo',text,'success','✓');}
  private fail(text:string):void{this.toast.show('No se pudo completar',text,'error','!');}
}
