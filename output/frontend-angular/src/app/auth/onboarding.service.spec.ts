// onboarding.service.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OnboardingService } from './onboarding.service';
import { OnboardingProgress, UiError } from './onboarding.models';

const progress: OnboardingProgress = {
  sessionId: 's1',
  status: 'IN_PROGRESS',
  totalSteps: 2,
  currentStepIndex: 1,
  currentStepId: 'A',
  steps: [
    { stepId: 'B', order: 2, label: 'B', required: true, status: 'PENDING' },
    { stepId: 'A', order: 1, label: 'A', required: true, status: 'CURRENT' },
  ],
  version: 1,
  walletStatus: 'PENDING',
  nextAction: { type: 'CONTINUE_STEP', target: 'A' },
};

describe('OnboardingService', () => {
  let service: OnboardingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(OnboardingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('envía POST /onboarding/sessions con Idempotency-Key y ordena los pasos', () => {
    let result: OnboardingProgress | undefined;
    service.start().subscribe((p) => (result = p));
    const req = httpMock.expectOne('/onboarding/sessions');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(progress, { status: 201, statusText: 'Created' });
    expect(result?.steps.map((s) => s.stepId)).toEqual(['A', 'B']);
  });

  it('reutiliza la clave tras un 500 y la renueva tras un éxito', () => {
    service.start().subscribe({ error: () => undefined });
    const r1 = httpMock.expectOne('/onboarding/sessions');
    const key1 = r1.request.headers.get('Idempotency-Key');
    r1.flush({}, { status: 500, statusText: 'Server Error' });

    service.start().subscribe();
    const r2 = httpMock.expectOne('/onboarding/sessions');
    expect(r2.request.headers.get('Idempotency-Key')).toBe(key1);
    r2.flush(progress);

    service.start().subscribe();
    const r3 = httpMock.expectOne('/onboarding/sessions');
    expect(r3.request.headers.get('Idempotency-Key')).not.toBe(key1);
    r3.flush(progress);
  });

  it('traduce 401 a UiError', () => {
    let err: UiError | undefined;
    service.start().subscribe({ error: (e) => (err = e) });
    httpMock.expectOne('/onboarding/sessions').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(err?.requiresLogin).toBeTrue();
  });

  it('submitStep devuelve error controlado mientras el contrato esté pendiente (SP-1)', () => {
    let err: UiError | undefined;
    service.submitStep(progress, {}).subscribe({ error: (e) => (err = e) });
    expect(err?.title).toBe('Paso no disponible');
  });
});