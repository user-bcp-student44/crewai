// payments.service.spec.ts
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { UserFacingError } from './payment.models';
import { PaymentsService } from './payments.service';

describe('PaymentsService', () => {
  let service: PaymentsService;
  let http: HttpTestingController;
  const body = { amountCents: 1250, currency: 'PEN' as const, payeeId: 'merchant-demo' };
  const payment = { paymentId: 'p1', status: 'PENDING', amountCents: 1250, currency: 'PEN' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PaymentsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('envía el header Idempotency-Key y marca replayed=false en 201', () => {
    let replayed: boolean | undefined;
    service.createPayment(body, 'pay-12345678').subscribe((r) => (replayed = r.replayed));
    const req = http.expectOne('/api/v1/payments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('pay-12345678');
    req.flush(payment, { status: 201, statusText: 'Created' });
    expect(replayed).toBeFalse();
  });

  it('marca replayed=true en 200', () => {
    let replayed: boolean | undefined;
    service.createPayment(body, 'pay-12345678').subscribe((r) => (replayed = r.replayed));
    http.expectOne('/api/v1/payments').flush(payment, { status: 200, statusText: 'OK' });
    expect(replayed).toBeTrue();
  });

  it('traduce un 404 al consultar', () => {
    let error: UserFacingError | undefined;
    service.getPayment('nope').subscribe({ error: (e) => (error = e) });
    http.expectOne('/api/v1/payments/nope').flush({ code: 'PAYMENT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    expect(error?.message).toContain('No encontramos');
  });
});