// app.routes.ts
import { Routes } from '@angular/router';
import { PaymentCreateComponent } from './payment-create.component';
import { PaymentDetailComponent } from './payment-detail.component';

// Rutas SUPUESTAS (S9).
export const routes: Routes = [
  { path: 'pagos/nuevo', component: PaymentCreateComponent, title: 'Nuevo pago' },
  { path: 'pagos/:paymentId', component: PaymentDetailComponent, title: 'Detalle del pago' },
  { path: '', pathMatch: 'full', redirectTo: 'pagos/nuevo' },
];