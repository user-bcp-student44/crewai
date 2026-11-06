// payments.service.ts
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';
import { API_BASE_URL } from './api-config';
import { mapHttpError } from './api-error.mapper';
import { CreatePaymentRequest, CreatePaymentResult, PaymentResponse } from './payment.models';

@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /** POST /payments con Idempotency-Key obligatoria. 201 = nuevo, 200 = repetición idempotente. */
  createPayment(body: CreatePaymentRequest, idempotencyKey: string): Observable<CreatePaymentResult> {
    return this.http
      .post<PaymentResponse>(`${this.baseUrl}/payments`, body, {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
        observe: 'response',
      })
      .pipe(
        map((res) => ({ payment: res.body as PaymentResponse, replayed: res.status === 200 })),
        catchError((err) => throwError(() => mapHttpError(err, 'create'))),
      );
  }

  /** GET /payments/{paymentId}. */
  getPayment(paymentId: string): Observable<PaymentResponse> {
    return this.http
      .get<PaymentResponse>(`${this.baseUrl}/payments/${encodeURIComponent(paymentId)}`)
      .pipe(catchError((err) => throwError(() => mapHttpError(err, 'get'))));
  }
}