import { Routes } from '@angular/router';
import { roleGuard, permissionGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./features/landing/landing.page').then(m => m.LandingPage) },
  { path: 'login', loadComponent: () => import('./features/auth/login.page').then(m => m.LoginPage) },
  { path: 'waitlist', loadComponent: () => import('./features/waitlist/waitlist.page').then(m => m.WaitlistPage) },
  { path: 'reservar', loadComponent: () => import('./features/booking/booking.page').then(m => m.BookingPage) },
  { path: 'diner', loadComponent: () => import('./features/diner/diner.page').then(m => m.DinerPage) },
  { path: 'waiter', canActivate:[roleGuard(['WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN'])], loadComponent: () => import('./features/waiter/waiter.page').then(m => m.WaiterPage) },
  { path: 'orders', canActivate:[roleGuard(['WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN']), permissionGuard('counter')], loadComponent: () => import('./features/counter/counter.page').then(m => m.CounterPage) },
  { path: 'audit', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/management/audit.page').then(m => m.AuditPage) },
  { path: 'inventory', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/inventory/inventory.page').then(m => m.InventoryPage) },
  { path: 'personal', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/staff/staff.page').then(m => m.StaffPage) },
  { path: 'asistencia', loadComponent: () => import('./features/attendance/clock.page').then(m => m.ClockPage) },
  { path: 'customers', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/customers/customers.page').then(m => m.CustomersPage) },
  { path: 'reservations', canActivate:[roleGuard(['WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN']), permissionGuard('reservations')], loadComponent: () => import('./features/reservations/reservations.page').then(m => m.ReservationsPage) },
  { path: 'setup', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/setup/setup.page').then(m => m.SetupPage) },
  { path: 'kitchen', canActivate:[roleGuard(['KITCHEN','BRANCH_MANAGER','ORGANIZATION_ADMIN'])], loadComponent: () => import('./features/kitchen/kitchen.page').then(m => m.KitchenPage) },
  { path: 'admin', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/admin/admin.page').then(m => m.AdminPage) },
  { path: 'menu', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/admin/menu-admin.page').then(m => m.MenuAdminPage) },
  { path: 'configuration', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/admin/operations-admin.page').then(m => m.OperationsAdminPage) },
  { path: 'live', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/live/live.page').then(m => m.LivePage) },
  { path: 'floor', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/floor/floor-editor.page').then(m => m.FloorEditorPage) },
  { path: 'cash', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/management/cash.page').then(m => m.CashPage) },
  { path: 'reports', canActivate:[roleGuard(['ORGANIZATION_ADMIN','BRANCH_MANAGER'])], loadComponent: () => import('./features/management/reports.page').then(m => m.ReportsPage) },
  { path: 'platform', canActivate:[roleGuard(['PLATFORM_ADMIN'])], loadComponent: () => import('./features/platform/platform.page').then(m => m.PlatformPage) },
  { path: '**', redirectTo: 'login' }
];
