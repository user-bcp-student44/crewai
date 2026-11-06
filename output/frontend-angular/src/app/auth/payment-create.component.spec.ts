// payment-create.component.spec.ts
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { PaymentCreateComponent } from './payment-create.component';
import { UserFacingError } from './payment.models';
import { PaymentsService } from './payments.service';

describe('PaymentCreateComponent', () => {
  let fixture: ComponentFixture<PaymentCreateComponent>;
  let component: PaymentCreateComponent;
  let service: jasmine.SpyObj<PaymentsService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<PaymentsService>('PaymentsService', ['createPayment']);
    await TestBed.configureTestingModule({
      imports: [PaymentCreateComponent],
      providers: [{ provide: PaymentsService, useValue: service }, provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(PaymentCreateComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('no llama al servicio si el formulario es inválido', () => {
    component.submit();
    expect(service.createPayment).not.toHaveBeenCalled();
  });

  it('convierte el monto a céntimos y fija PEN', () => {
    service.createPayment.and.returnValue(
      of({ payment: { paymentId: 'p1', status: 'PENDING', amountCents: 1250, currency: 'PEN' }, replayed: false }),
    );
    component.form.setValue({ payeeId: 'merchant-demo', amount: '12,50' });
    component.submit();
    const [payload] = service.createPayment.calls.mostRecent().args;
    expect(payload).toEqual({ amountCents: 1250, currency: 'PEN', payeeId: 'merchant-demo' });
  });

  it('reutiliza la misma clave al reintentar con los mismos datos', () => {
    const fail: UserFacingError = { message: 'x', action: 'retry' };
    service.createPayment.and.returnValue(throwError(() => fail));
    component.form.setValue({ payeeId: 'merchant-demo', amount: '10' });
    component.submit();
    component.submit();
    const keys = service.createPayment.calls.allArgs().map((a) => a[1]);
    expect(keys.length).toBe(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('genera una clave nueva si cambian los datos', () => {
    const fail: UserFacingError = { message: 'x', action: 'retry' };
    service.createPayment.and.returnValue(throwError(() => fail));
    component.form.setValue({ payeeId: 'merchant-demo', amount: '10' });
    component.submit();
    component.form.setValue({ payeeId: 'merchant-demo', amount: '11' });
    component.submit();
    const keys = service.createPayment.calls.allArgs().map((a) => a[1]);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('la clave generada cumple el patrón del servidor', () => {
    service.createPayment.and.returnValue(throwError(() => ({ message: 'x', action: 'none' }) as UserFacingError));
    component.form.setValue({ payeeId: 'contact-demo', amount: '5' });
    component.submit();
    const key = service.createPayment.calls.mostRecent().args[1];
    expect(key).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  });
});