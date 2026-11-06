// onboarding.component.ts
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ONBOARDING_CONFIG, ONBOARDING_STEP_FIELDS } from './onboarding.config';
import { notAvailableError } from './onboarding-error.mapper';
import { OnboardingService } from './onboarding.service';
import { OnboardingProgress, OnboardingStep, StepFieldDefinition, StepStatus, UiError } from './onboarding.models';

interface SummaryItem {
  name?: string;
  message: string;
}

const notBlank: ValidatorFn = (c: AbstractControl): ValidationErrors | null =>
  typeof c.value === 'string' && c.value.trim().length === 0 ? { required: true } : null;

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OnboardingComponent implements OnInit {
  private readonly service = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly config = inject(ONBOARDING_CONFIG);
  private readonly fieldDefs = inject(ONBOARDING_STEP_FIELDS);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Origen pendiente de definir (SP-3). */
  @Input() acquisitionChannel?: string;

  readonly progress = signal<OnboardingProgress | null>(null);
  readonly loading = signal(false);
  readonly submitting = signal(false);
  readonly error = signal<UiError | null>(null);
  readonly summary = signal<SummaryItem[]>([]);

  readonly stepForm = new FormGroup<Record<string, FormControl<string>>>({});

  readonly steps = computed<OnboardingStep[]>(() => this.progress()?.steps ?? []);
  readonly currentStep = computed(() => this.steps().find((s) => s.status === 'CURRENT') ?? null);
  readonly position = computed(() => {
    const i = this.steps().findIndex((s) => s.status === 'CURRENT');
    return i >= 0 ? i + 1 : 0;
  });
  readonly currentFields = computed<StepFieldDefinition[]>(() => {
    const step = this.currentStep();
    return step ? (this.fieldDefs[step.stepId] ?? []) : [];
  });

  readonly viewState = computed<'loading' | 'error' | 'empty' | 'closed' | 'activating' | 'completed' | 'ready'>(() => {
    const p = this.progress();
    if (this.loading() && !p) return 'loading';
    if (this.error() && !p) return 'error';
    if (!p) return 'loading';
    if (p.steps.length === 0) return 'empty';
    if (p.status === 'CLOSED_INCOMPLETE') return 'closed';
    if (p.status === 'COMPLETED') {
      return p.walletStatus === 'ACTIVE' && p.nextAction?.type === 'GO_TO_LOW_AMOUNT_PAYMENTS'
        ? 'completed'
        : 'activating';
    }
    return 'ready';
  });

  private submitAttempted = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.summary.set([]);
    this.service
      .start(this.acquisitionChannel)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (p) => this.applyProgress(p),
        error: (e: UiError | Error) => this.showError(this.asUiError(e)),
      });
  }

  onContinue(): void {
    if (this.submitting() || !this.progress()) return;
    this.submitAttempted = true;
    this.error.set(null);
    this.stepForm.markAllAsTouched();

    if (this.currentFields().length === 0) {
      this.showError(notAvailableError());
      return;
    }
    if (this.stepForm.invalid) {
      this.summary.set(this.buildSummary());
      this.focus('[data-summary-focus]');
      return;
    }

    this.summary.set([]);
    this.submitting.set(true);
    this.service
      .submitStep(this.progress()!, this.stepForm.getRawValue())
      .pipe(
        finalize(() => this.submitting.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (p) => this.applyProgress(p),
        error: (e: UiError | Error) => {
          const ui = this.asUiError(e);
          this.applyServerFieldErrors(ui);
          this.showError(ui);
        },
      });
  }

  goToPayments(): void {
    const target = this.progress()?.nextAction?.target;
    // La ruta sale de la configuración local, nunca del servidor (SP-7).
    const route = target ? this.config.routes.targets[target] : undefined;
    if (!route) {
      this.showError({
        ...notAvailableError(),
        title: 'No pudimos abrir pagos',
        message: 'Tu wallet está activa, pero no pudimos abrir la pantalla de pagos. Inténtalo más tarde.',
      });
      return;
    }
    void this.router.navigateByUrl(route);
  }

  goToLogin(): void {
    const login = this.config.routes.login;
    if (login) void this.router.navigateByUrl(login);
  }

  get canGoToLogin(): boolean {
    return !!this.config.routes.login;
  }

  statusText(status: StepStatus): string {
    return status === 'COMPLETED' ? 'completado' : status === 'CURRENT' ? 'paso actual' : 'pendiente';
  }

  errorText(name: string): string | null {
    const control = this.stepForm.get(name);
    const def = this.currentFields().find((f) => f.name === name);
    if (!control || !def || !control.errors || !(control.touched || this.submitAttempted)) return null;
    return this.messageFor(def, control.errors);
  }

  describedBy(f: StepFieldDefinition): string | null {
    const ids: string[] = [];
    if (f.hint) ids.push(`h-${f.name}`);
    if (this.errorText(f.name)) ids.push(`e-${f.name}`);
    return ids.length ? ids.join(' ') : null;
  }

  focusField(name: string | undefined): void {
    if (!name) return;
    this.host.nativeElement.querySelector<HTMLElement>(`#f-${CSS.escape(name)}`)?.focus();
  }

  private applyProgress(p: OnboardingProgress): void {
    this.progress.set(p);
    this.error.set(null);
    this.summary.set([]);
    this.submitAttempted = false;
    this.rebuildForm(p.steps.find((s) => s.status === 'CURRENT')?.stepId);
    this.focus('[data-focus]');
  }

  private rebuildForm(stepId: string | undefined): void {
    Object.keys(this.stepForm.controls).forEach((k) => this.stepForm.removeControl(k));
    const defs = stepId ? (this.fieldDefs[stepId] ?? []) : [];
    for (const f of defs) {
      this.stepForm.addControl(f.name, this.fb.control('', this.validatorsFor(f)));
    }
  }

  private validatorsFor(f: StepFieldDefinition): ValidatorFn[] {
    const v: ValidatorFn[] = [];
    if (f.required) v.push(Validators.required, notBlank);
    if (f.minLength) v.push(Validators.minLength(f.minLength));
    if (f.maxLength) v.push(Validators.maxLength(f.maxLength));
    if (f.pattern) v.push(Validators.pattern(f.pattern));
    return v;
  }

  private messageFor(def: StepFieldDefinition, errors: ValidationErrors): string {
    if (typeof errors['server'] === 'string') return errors['server'];
    if (errors['required']) return `Ingresa ${def.label.toLowerCase()} para continuar.`;
    if (errors['minlength']) return `${def.label} debe tener al menos ${errors['minlength'].requiredLength} caracteres.`;
    if (errors['maxlength']) return `${def.label} no puede superar ${errors['maxlength'].requiredLength} caracteres.`;
    if (errors['pattern']) return `${def.label} tiene un formato no válido. Revísalo e inténtalo de nuevo.`;
    return `${def.label} no es válido.`;
  }

  private buildSummary(): SummaryItem[] {
    const items: SummaryItem[] = [];
    for (const def of this.currentFields()) {
      const msg = this.errorText(def.name);
      if (msg) items.push({ name: def.name, message: msg });
    }
    return items;
  }

  /** Errores por campo del servidor: a su control si existe; si no, al resumen. */
  private applyServerFieldErrors(ui: UiError): void {
    const items: SummaryItem[] = [];
    for (const fe of ui.fieldErrors) {
      const control = this.stepForm.get(fe.field);
      if (control) {
        control.setErrors({ server: fe.message });
        control.markAsTouched();
        items.push({ name: fe.field, message: fe.message });
      } else {
        items.push({ message: fe.message });
      }
    }
    this.summary.set(items);
  }

  private showError(ui: UiError): void {
    this.error.set(ui);
    this.focus('[data-error-focus]');
  }

  private asUiError(e: UiError | Error): UiError {
    return 'title' in e && 'fieldErrors' in e
      ? (e as UiError)
      : {
          ...notAvailableError(),
          title: 'No se pudo completar la acción',
          message: 'No pudimos completar la acción. Inténtalo de nuevo.',
          retryable: true,
        };
  }

  private focus(selector: string): void {
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus());
  }
}