// onboarding.service.ts
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, tap, throwError } from 'rxjs';
import { ONBOARDING_CONFIG } from './onboarding.config';
import { notAvailableError, toUiError } from './onboarding-error.mapper';
import { OnboardingProgress, StartOnboardingRequest } from './onboarding.models';

export function generateIdempotencyKey(): string {
  const c = globalThis.crypto;
  if (typeof c?.randomUUID === 'function') {
    return c.randomUUID();
  }
  const b = new Uint8Array(16);
  c.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ONBOARDING_CONFIG);

  /** Clave en memoria: se reutiliza en reintentos por red/5xx y se descarta tras éxito o 4xx. */
  private startKey: string | null = null;

  /** E1: POST /onboarding/sessions. Inicia o reanuda el onboarding. */
  start(acquisitionChannel?: string): Observable<OnboardingProgress> {
    const key = this.startKey ?? (this.startKey = generateIdempotencyKey());
    const channel = acquisitionChannel?.trim();
    const body: StartOnboardingRequest = channel ? { acquisitionChannel: channel } : {};

    return this.http
      .post<OnboardingProgress>(`${this.config.apiBaseUrl}/onboarding/sessions`, body, {
        headers: new HttpHeaders({ 'Idempotency-Key': key }),
      })
      .pipe(
        map((p) => this.normalize(p)),
        tap(() => (this.startKey = null)),
        catchError((err: unknown) => {
          const ui = toUiError(err, 'start');
          if (ui.status >= 400 && ui.status < 500) {
            this.startKey = null;
          }
          return throwError(() => ui);
        }),
      );
  }

  /**
   * Enviar un paso: NO IMPLEMENTADO. El contrato visible no define ruta, método ni cuerpo (SP-1).
   * Devuelve un error controlado en lugar de inventar un endpoint.
   */
  submitStep(_progress: OnboardingProgress, _values: Record<string, string>): Observable<OnboardingProgress> {
    return throwError(() => notAvailableError());
  }

  private normalize(p: OnboardingProgress): OnboardingProgress {
    if (!p || !Array.isArray(p.steps)) {
      throw new Error('Respuesta de progreso inválida');
    }
    return { ...p, steps: [...p.steps].sort((a, b) => a.order - b.order) };
  }
}