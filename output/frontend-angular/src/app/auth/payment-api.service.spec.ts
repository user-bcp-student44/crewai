// payment-api.service.spec.ts
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { POLL_INTERVAL_MS, PaymentApiService } from './payment-api.service';
import { PaymentRequest } from './payment.models';

describe('PaymentApiService', () => {
  let svc: PaymentApiService;
  let http: HttpTestingController;

  const req: PaymentRequest = {
    providerId: 'p1',
    channel: 'c1',
    destination: { type: 't', identifier: 'x' },
    amountMinor: 1250,
    currency: 'PEN',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(PaymentApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('create envía el header Idempotency-Key', () => {
    svc.create(req, 'key-1').subscribe();
    const r = http.expectOne('/payments');
    expect(r.request.method).toBe('POST');
    expect(r.request.headers.get('Idempotency-Key')).toBe('key-1');
    r.flush({ id: 'a', status: 'PENDING' });
  });

  it('poll se detiene en el primer estado terminal', fakeAsync(() => {
    const seen: string[] = [];
    let done = false;
    svc.poll('a').subscribe({ next: (p) => seen.push(p.status), complete: () => (done = true) });

    tick(0);
    http.expectOne('/payments/a').flush({ id: 'a', status: 'PENDING' });
    tick(POLL_INTERVAL_MS);
    http.expectOne('/payments/a').flush({ id: 'a', status: 'SUCCEEDED', transactionReference: 'R1' });

    expect(seen).toEqual(['PENDING', 'SUCCEEDED']);
    expect(done).toBeTrue();
    tick(POLL_INTERVAL_MS * 3); // no debe haber más peticiones (http.verify lo comprueba)
  }));
});