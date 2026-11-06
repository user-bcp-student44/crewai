// onboarding.component.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ONBOARDING_CONFIG, ONBOARDING_STEP_FIELDS } from './onboarding.config';
import { OnboardingComponent } from './onboarding.component';
import { OnboardingService } from './onboarding.service';
import { OnboardingProgress, UiError } from './onboarding.models';

const inProgress: OnboardingProgress = {
  sessionId: 's1',
  status: 'IN_PROGRESS',
  totalSteps: 3,
  currentStepIndex: 2,
  currentStepId: 'IDENTITY_DOCUMENT',
  steps: [
    { stepId: 'PERSONAL_DATA', order: 1, label: 'Datos personales', required: true, status: 'COMPLETED' },
    { stepId: 'IDENTITY_DOCUMENT', order: 2, label: 'Documento', required: true, status: 'CURRENT' },
    { stepId: 'CONFIRMATION', order: 3, label: 'Confirmación', required: true, status: 'PENDING' },
  ],
  version: 2,
  walletStatus: 'PENDING',
  nextAction: { type: 'CONTINUE_STEP', target: 'IDENTITY_DOCUMENT' },
};

const completed: OnboardingProgress = {
  ...inProgress,
  status: 'COMPLETED',
  walletStatus: 'ACTIVE',
  steps: inProgress.steps.map((s) => ({ ...s, status: 'COMPLETED' as const })),
  nextAction: { type: 'GO_TO_LOW_AMOUNT_PAYMENTS', target: 'LOW_AMOUNT_PAYMENTS_ENTRY' },
};

const serverError: UiError = {
  status: 500,
  title: 'Problema temporal',
  message: 'Tuvimos un problema de nuestro lado. Inténtalo de nuevo en unos minutos.',
  fieldErrors: [],
  retryable: true,
  requiresLogin: false,
  refreshRequired: false,
};

describe('OnboardingComponent', () => {
  let fixture: ComponentFixture<OnboardingComponent>;
  let service: jasmine.SpyObj<OnboardingService>;

  function setup(startResult: unknown): void {
    service = jasmine.createSpyObj<OnboardingService>('OnboardingService', ['start', 'submitStep']);
    (service.start as jasmine.Spy).and.returnValue(startResult);
    TestBed.configureTestingModule({
      imports: [OnboardingComponent],
      providers: [
        provideRouter([]),
        { provide: OnboardingService, useValue: service },
        {
          provide: ONBOARDING_CONFIG,
          useValue: { apiBaseUrl: '', routes: { targets: { LOW_AMOUNT_PAYMENTS_ENTRY: '/pagos' } } },
        },
        {
          provide: ONBOARDING_STEP_FIELDS,
          useValue: {
            IDENTITY_DOCUMENT: [{ name: 'documentNumber', label: 'Número de documento', required: true, maxLength: 12 }],
          },
        },
      ],
    });
    fixture = TestBed.createComponent(OnboardingComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  it('muestra "Paso 2 de 3" y marca el paso actual con aria-current', () => {
    setup(of(inProgress));
    expect(el().textContent).toContain('Paso 2 de 3');
    const current = el().querySelector('li[aria-current="step"]');
    expect(current?.textContent).toContain('Documento');
  });

  it('asocia la etiqueta con el campo', () => {
    setup(of(inProgress));
    const input = el().querySelector('#f-documentNumber') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(el().querySelector('label[for="f-documentNumber"]')).toBeTruthy();
  });

  it('bloquea el avance con un campo obligatorio vacío y muestra un alert', () => {
    setup(of(inProgress));
    (el().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(service.submitStep).not.toHaveBeenCalled();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Ingresa número de documento');
  });

  it('muestra un error con role=alert y Reintentar ante un 500', () => {
    setup(throwError(() => serverError));
    const alert = el().querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Problema temporal');
    expect(el().textContent).toContain('Reintentar');
  });

  it('navega a la ruta configurada al completar con wallet activa', () => {
    setup(of(completed));
    const router = TestBed.inject(Router);
    const nav = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    const btn = Array.from(el().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Ir a pagos de bajo monto'),
    ) as HTMLButtonElement;
    btn.click();
    expect(nav).toHaveBeenCalledWith('/pagos');
  });
});