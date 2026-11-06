// payment-flow.component.ts
import { Component, ElementRef, EventEmitter, Input, OnDestroy, OnInit, Output, ViewChild, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Subscription, finalize } from 'rxjs';
import { PaymentApiService } from './payment-api.service';
import { UserError, toUserError } from './payment-error.mapper';
import { currencyExponent, formatMinor, parseAmountToMinor } from './money.util';
import {
  PaymentContext,
  PaymentRequest,
  PaymentResponse,
  PreviewResponse,
  STATUS_LABELS,
  STATUS_MESSAGES,
  TerminalStatus,
  isTerminal,
} from './payment.models';

type Step = 'form' | 'confirm' | 'processing' | 'delayed' | 'result';

@Component({
  selector: 'app-payment-flow',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './payment-flow.component.html',
  styleUrl: './payment-flow.component.scss',
})
export class PaymentFlowComponent implements OnInit, OnDestroy {
  /** SUPUESTO PENDIENTE (S3): origen del contexto. */
  @Input({ required: true }) context!: PaymentContext;
  @Output() viewHistory = new EventEmitter<void>();
  @Output() sessionExpired = new EventEmitter<void>();

  @ViewChild('stepHeading') private heading?: ElementRef<HTMLElement>;
  @ViewChild('errorBox') private errorBox?: ElementRef<HTMLElement>;

  private readonly api = inject(PaymentApiService);
  private readonly fb = inject(FormBuilder);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly step = signal<Step>('form');
  readonly busy = signal(false);
  readonly error = signal<UserError | null>(null);
  readonly preview = signal<PreviewResponse | null>(null);
  readonly payment = signal<PaymentResponse | null>(null);

  readonly terminalStatus = computed<TerminalStatus | null>(() => {
    const s = this.payment()?.status;
    return isTerminal(s) ? s : null;
  });
  readonly resultLabel = computed(() => {
    const s = this.terminalStatus();
    return s ? STATUS_LABELS[s] : '';
  });
  readonly resultMessage = computed(() => {
    const s = this.terminalStatus();
    return s ? STATUS_MESSAGES[s] : '';
  });
  readonly liveMessage = computed(() => {
    if (this.step() === 'form' && this.busy()) return 'Verificando monto y destino…';
    if (this.step() === 'confirm' && this.busy()) return 'Enviando el pago…';
    if (this.step() === 'processing') return 'Confirmando el resultado de tu pago…';
    return '';
  });

  readonly form = this.fb.nonNullable.group({
    destinationIdentifier: ['', [Validators.required, Validators.pattern(/\S/)]],
    amount: ['', [Validators.required, (c: AbstractControl) => this.validateAmount(c)]],
  });

  private request: PaymentRequest | null = null;
  /** Solo en memoria. Se reutiliza ante errores ambiguos; se renueva en cada intento nuevo. */
  private idempotencyKey: string | null = null;
  private pollSub?: Subscription;

  private get exponent(): number {
    return currencyExponent(this.context.currency);
  }

  ngOnInit(): void {
    this.form.controls.amount.updateValueAndValidity();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  // ---------- Validación visible ----------

  private validateAmount(c: AbstractControl): ValidationErrors | null {
    const v = String(c.value ?? '');
    if (!v.trim()) return null; // lo cubre "required"
    const minor = parseAmountToMinor(v, this.context ? this.exponent : 2);
    if (minor === null) return { amountFormat: true };
    if (minor <= 0) return { amountPositive: true };
    return null;
  }

  destinationError(): string | null {
    const c = this.form.controls.destinationIdentifier;
    if (!(c.touched && c.errors)) return null;
    if (c.errors['required'] || c.errors['pattern']) return 'Ingresa el destino del pago.';
    if (c.errors['server']) return 'Revisa este dato: no fue aceptado.';
    return 'Valor no válido.';
  }

  amountError(): string | null {
    const c = this.form.controls.amount;
    if (!(c.touched && c.errors)) return null;
    if (c.errors['required']) return 'Ingresa el monto.';
    if (c.errors['amountFormat'])
      return `Usa solo números con hasta ${this.exponent} decimales. Ejemplo: 12.50`;
    if (c.errors['amountPositive']) return 'El monto debe ser mayor que cero.';
    if (c.errors['server']) return 'El monto no está dentro de los límites permitidos para este pago.';
    return 'Valor no válido.';
  }

  formatAmount(minor: number | undefined, currency: string | undefined): string {
    return minor === undefined || !currency ? '' : formatMinor(minor, currency);
  }

  // ---------- Acciones ----------

  submitForm(): void {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focusFirstInvalid();
      return;
    }
    const minor = parseAmountToMinor(this.form.controls.amount.value, this.exponent);
    if (minor === null) return;

    this.request = {
      providerId: this.context.providerId,
      channel: this.context.channel,
      destination: {
        type: this.context.destinationType,
        identifier: this.form.controls.destinationIdentifier.value.trim(),
      },
      amountMinor: minor,
      currency: this.context.currency,
    };
    this.error.set(null);
    this.busy.set(true);
    this.api
      .preview(this.request)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (p) => {
          this.preview.set(p);
          this.step.set('confirm');
          this.focusHeading();
        },
        error: (e) => this.handleError(e, 'preview'),
      });
  }

  confirm(): void {
    if (this.busy() || !this.request) return;
    this.idempotencyKey ??= crypto.randomUUID();
    this.error.set(null);
    this.busy.set(true);
    this.api
      .create(this.request, this.idempotencyKey)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (p) => {
          this.payment.set(p);
          if (isTerminal(p.status)) {
            this.step.set('result');
            this.focusHeading();
          } else {
            this.startPolling(p.id);
          }
        },
        error: (e) => this.handleError(e, 'create'),
      });
  }

  editData(): void {
    if (this.busy()) return;
    this.idempotencyKey = null;
    this.error.set(null);
    this.step.set('form');
    this.focusHeading();
  }

  resumePolling(): void {
    const p = this.payment();
    if (!p) return;
    this.error.set(null);
    this.startPolling(p.id);
  }

  /** Nuevo intento tras FAILED/EXPIRED: clave nueva (nuevo Payment en el backend). */
  retry(): void {
    this.idempotencyKey = null;
    this.payment.set(null);
    this.error.set(null);
    this.step.set('confirm');
    this.focusHeading();
  }

  backToStart(): void {
    this.pollSub?.unsubscribe();
    this.idempotencyKey = null;
    this.request = null;
    this.preview.set(null);
    this.payment.set(null);
    this.error.set(null);
    this.form.reset();
    this.step.set('form');
    this.focusHeading();
  }

  // ---------- Internos ----------

  private startPolling(id: string): void {
    this.pollSub?.unsubscribe();
    this.step.set('processing');
    this.focusHeading();
    this.pollSub = this.api.poll(id).subscribe({
      next: (p) => this.payment.set(p),
      error: (e) => {
        this.handleError(e, 'poll');
        this.step.set('delayed');
      },
      complete: () => {
        if (isTerminal(this.payment()?.status)) {
          this.step.set('result');
        } else {
          this.step.set('delayed'); // tope de tiempo: resultado aún no disponible, no es un fallo
        }
        this.focusHeading();
      },
    });
  }

  private handleError(e: unknown, op: 'preview' | 'create' | 'poll'): void {
    const ue = toUserError(e, op);
    this.error.set(ue);
    if (op === 'create' && !ue.keepIdempotencyKey) {
      this.idempotencyKey = null;
    }
    if (op === 'preview') {
      this.applyServerFieldErrors(ue.fieldNames);
    }
    if (ue.sessionExpired) {
      this.sessionExpired.emit();
    }
    this.focusError();
  }

  private applyServerFieldErrors(names: string[]): void {
    for (const raw of names) {
      const n = raw.toLowerCase();
      const ctrl = n.includes('amount')
        ? this.form.controls.amount
        : n.includes('destination') || n.includes('identifier')
          ? this.form.controls.destinationIdentifier
          : null;
      if (ctrl) {
        ctrl.setErrors({ ...(ctrl.errors ?? {}), server: true });
        ctrl.markAsTouched();
      }
    }
  }

  private focusHeading(): void {
    setTimeout(() => this.heading?.nativeElement.focus());
  }

  private focusError(): void {
    setTimeout(() => this.errorBox?.nativeElement.focus());
  }

  private focusFirstInvalid(): void {
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('input.ng-invalid');
    el?.focus();
  }
}