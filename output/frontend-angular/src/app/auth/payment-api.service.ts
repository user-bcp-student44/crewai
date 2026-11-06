// payment-api.service.ts
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, exhaustMap, takeUntil, takeWhile, timer } from 'rxjs';
import {
  HistoryPageResponse,
  PaymentRequest,
  PaymentResponse,
  PreviewResponse,
  isTerminal,
} from './payment.models';

/** SUPUESTO PENDIENTE (S1): prefijo de la API. */
export const PAYMENTS_API_BASE = new InjectionToken<string>('PAYMENTS_API_BASE', {
  providedIn: 'root',
  factory: () => '',
});

/** SUPUESTO PENDIENTE (S5): valores del cliente, a confirmar con Producto/Backend. */
export const POLL_INTERVAL_MS = 2000;
export const POLL_MAX_MS = 60000;

@Injectable({ providedIn: 'root' })
export class PaymentApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(PAYMENTS_API_BASE);

  preview(req: PaymentRequest): Observable<PreviewResponse> {
    return this.http.post<PreviewResponse>(`${this.base}/payments/preview`, req);
  }

  create(req: PaymentRequest, idempotencyKey: string): Observable<PaymentResponse> {
    return this.http.post<PaymentResponse>(`${this.base}/payments`, req, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  get(id: string): Observable<PaymentResponse> {
    return this.http.get<PaymentResponse>(`${this.base}/payments/${encodeURIComponent(id)}`);
  }

  /**
   * Consulta periódica hasta el primer estado terminal (se emite y se completa).
   * Si se alcanza el tope de tiempo sin terminal, completa sin emitir terminal:
   * el llamador debe tratarlo como "resultado aún no disponible", nunca como fallo.
   */
  poll(id: string): Observable<PaymentResponse> {
    return timer(0, POLL_INTERVAL_MS).pipe(
      exhaustMap(() => this.get(id)),
      takeWhile((p) => !isTerminal(p.status), true),
      takeUntil(timer(POLL_MAX_MS)),
    );
  }

  history(page: number, size: number): Observable<HistoryPageResponse> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<HistoryPageResponse>(`${this.base}/payments/history`, { params });
  }
}