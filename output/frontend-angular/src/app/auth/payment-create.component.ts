// payment-create.component.ts
import { DatePipe } from '@angular/common';
import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { generateIdempotencyKey } from './idempotency-key';
import { formatCents, toCents } from './money';
import { CreatePaymentRequest, CreatePaymentResult, UserFacingError } from './payment.models';
import { statusInfo } from './payment-status';
import { PaymentsService } from './payments.service';

/** Validación de apoyo. El servidor valida de nuevo y manda. */
export function amountValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '').trim();
  if (value === '') {
    return null; // lo cubre Validators.required
  }
  const cents = toCents(value);
  if (cents === null) {
    return { amountFormat: true };
  }
  if (cents <= 0) {
    return { amountPositive: true };
  }
  return null;
}

@Component({
  selector: 'app-payment-create',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  templateUrl: './payment-create.component.html',
  styleUrl: './payment-create.component.scss',
})
export class PaymentCreateComponent {
  private readonly fb = inject(FormBuilder);
  private readonly payments = inject(PaymentsService);

  /** Opciones conocidas por el mock (Issue). El nombre del campo es SUPUESTO (S1). */
  readonly payees = [
    { value: 'merchant-demo', label: 'Comercio demo' },
    { value: 'contact-demo', label: 'Contacto demo' },
  ];

  readonly form = this.fb.nonNullable.group({
    payeeId: ['', [Validators.required]],
    amount: ['', [Validators.required, amountValidator]],
  });

  readonly loading = signal(false);
  readonly error = signal<UserFacingError | null>(null);
  readonly result = signal<CreatePaymentResult | null>(null);
  submitted = false;

  /** Clave y cuerpo del intento en curso. Solo en memoria. */
  private attemptKey: string | null = null;
  private attemptPayload: string | null = null;

  @ViewChild('payeeField') payeeField?: ElementRef<HTMLSelectElement>;
  @ViewChild('amountField') amountField?: ElementRef<HTMLInputElement>;
  @ViewChild('errorBox') errorBox?: ElementRef<HTMLElement>;
  @ViewChild('resultTitle') resultTitle?: ElementRef<HTMLElement>;

  get payeeError(): string | null {
    const c = this.form.controls.payeeId;
    if (!(c.touched || this.submitted) || !c.errors) {
      return null;
    }
    return 'Selecciona un destinatario.';
  }

  get amountError(): string | null {
    const c = this.form.controls.amount;
    if (!(c.touched || this.submitted) || !c.errors) {
      return null;
    }
    if (c.errors['required']) {
      return 'Ingresa el monto.';
    }
    if (c.errors['amountFormat']) {
      return 'Usa solo números con hasta 2 decimales. Ejemplo: 12.50';
    }
    if (c.errors['amountPositive']) {
      return 'El monto debe ser mayor que S/ 0.00.';
    }
    return null;
  }

  submit(): void {
    if (this.loading()) {
      return;
    }
    this.submitted = true;

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalid();
      return;
    }

    const payload = this.buildPayload();
    const serialized = JSON.stringify(payload);

    // Misma solicitud => misma clave (reintento seguro). Datos distintos => clave nueva.
    if (this.attemptKey === null || this.attemptPayload !== serialized) {
      this.attemptKey = generateIdempotencyKey();
      this.attemptPayload = serialized;
    }

    this.loading.set(true);
    this.error.set(null);

    this.payments
      .createPayment(payload, this.attemptKey)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (res) => {
          this.result.set(res);
          this.resetAttempt();
          this.focusLater(() => this.resultTitle?.nativeElement);
        },
        error: (err: UserFacingError) => {
          this.error.set(err);
          this.focusLater(() => this.errorBox?.nativeElement);
        },
      });
  }

  /** Descarta la clave actual: el próximo envío será un pago nuevo. */
  startNewPayment(): void {
    this.resetAttempt();
    this.error.set(null);
    this.result.set(null);
    this.submitted = false;
    this.form.reset({ payeeId: '', amount: '' });
    this.focusLater(() => this.payeeField?.nativeElement);
  }

  /** Para el error 'new-attempt': conserva los datos pero genera clave nueva. */
  discardAttempt(): void {
    this.resetAttempt();
    this.error.set(null);
    this.focusLater(() => this.amountField?.nativeElement);
  }

  statusLabel(status: string): string {
    return statusInfo(status).label;
  }

  statusDescription(status: string): string {
    return statusInfo(status).description;
  }

  money(cents: number, currency: string): string {
    return formatCents(cents, currency || 'PEN');
  }

  private buildPayload(): CreatePaymentRequest {
    const { payeeId, amount } = this.form.getRawValue();
    return { amountCents: toCents(amount) as number, currency: 'PEN', payeeId };
  }

  private resetAttempt(): void {
    this.attemptKey = null;
    this.attemptPayload = null;
  }

  private focusFirstInvalid(): void {
    if (this.form.controls.payeeId.invalid) {
      this.payeeField?.nativeElement.focus();
    } else if (this.form.controls.amount.invalid) {
      this.amountField?.nativeElement.focus();
    }
  }

  private focusLater(target: () => HTMLElement | undefined): void {
    setTimeout(() => target()?.focus(), 0);
  }
}