// payment-detail.component.ts
import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { formatCents } from './money';
import { PaymentResponse, UserFacingError } from './payment.models';
import { statusInfo } from './payment-status';
import { PaymentsService } from './payments.service';

@Component({
  selector: 'app-payment-detail',
  standalone: true,
  imports: [RouterLink, DatePipe],
  template: `
    <main class="page">
      <h1 #title tabindex="-1">Detalle del pago</h1>

      @if (missingId()) {
        <p role="alert">Falta el identificador del pago.</p>
      } @else {
        @if (loading()) {
          <p role="status">Cargando pago…</p>
        }

        @if (error(); as err) {
          <div class="panel" role="alert" #errorBox tabindex="-1">
            <p>{{ err.message }}</p>
            @if (err.traceId) {
              <p class="help">Código de referencia: {{ err.traceId }}</p>
            }
            @if (err.action === 'retry') {
              <button type="button" class="btn" (click)="load()" [disabled]="loading()">Reintentar</button>
            }
          </div>
        }

        @if (payment(); as p) {
          <section aria-labelledby="estado">
            <h2 id="estado">Estado: {{ label(p.status) }}</h2>
            <p>{{ description(p.status) }}</p>
            <dl>
              <dt>Monto</dt>
              <dd>{{ money(p.amountCents, p.currency) }}</dd>
              <dt>Identificador</dt>
              <dd>{{ p.paymentId }}</dd>
              @if (p.createdAt) {
                <dt>Fecha</dt>
                <dd>{{ p.createdAt | date: 'dd/MM/yyyy HH:mm' }}</dd>
              }
            </dl>
            <button type="button" class="btn" (click)="load()" [disabled]="loading()">Actualizar estado</button>
          </section>
        }
      }

      <p><a routerLink="/pagos/nuevo">Hacer un pago nuevo</a></p>
    </main>
  `,
  styles: [
    `
      .page { max-width: 32rem; margin: 0 auto; padding: 1rem; color: #1a1a1a; }
      .panel { border: 2px solid #9b1c1c; background: #fdf0f0; border-radius: 6px; padding: 1rem; margin: 1rem 0; }
      .help { color: #4a4a4a; font-size: 0.9rem; }
      .btn { padding: 0.7rem 1.2rem; font-size: 1rem; border: 2px solid #0b3d91; background: #0b3d91; color: #fff; border-radius: 4px; cursor: pointer; }
      .btn[disabled] { opacity: 0.7; cursor: not-allowed; }
      :focus-visible { outline: 3px solid #ffbf47; outline-offset: 2px; }
    `,
  ],
})
export class PaymentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly payments = inject(PaymentsService);

  readonly loading = signal(false);
  readonly error = signal<UserFacingError | null>(null);
  readonly payment = signal<PaymentResponse | null>(null);
  readonly missingId = signal(false);

  @ViewChild('errorBox') errorBox?: ElementRef<HTMLElement>;

  private paymentId = '';

  ngOnInit(): void {
    this.paymentId = (this.route.snapshot.paramMap.get('paymentId') ?? '').trim();
    if (!this.paymentId) {
      this.missingId.set(true);
      return;
    }
    this.load();
  }

  load(): void {
    if (this.loading() || !this.paymentId) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.payments
      .getPayment(this.paymentId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (p) => this.payment.set(p),
        error: (err: UserFacingError) => {
          this.payment.set(null);
          this.error.set(err);
          setTimeout(() => this.errorBox?.nativeElement.focus(), 0);
        },
      });
  }

  label(status: string): string {
    return statusInfo(status).label;
  }

  description(status: string): string {
    return statusInfo(status).description;
  }

  money(cents: number, currency: string): string {
    return formatCents(cents, currency || 'PEN');
  }
}