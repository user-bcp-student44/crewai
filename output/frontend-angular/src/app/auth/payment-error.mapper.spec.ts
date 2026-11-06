// payment-error.mapper.spec.ts
import { HttpErrorResponse } from '@angular/common/http';
import { toUserError } from './payment-error.mapper';

function http(status: number, error: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, error, url: '/payments' });
}

describe('toUserError', () => {
  it('401 marca sesión expirada', () => {
    const r = toUserError(http(401), 'create');
    expect(r.sessionExpired).toBeTrue();
  });

  it('409 en create conserva la Idempotency-Key', () => {
    expect(toUserError(http(409), 'create').keepIdempotencyKey).toBeTrue();
  });

  it('400 en preview no conserva la clave y extrae nombres de campo', () => {
    const r = toUserError(http(400, { fieldErrors: [{ field: 'amountMinor' }] }), 'preview');
    expect(r.keepIdempotencyKey).toBeFalse();
    expect(r.fieldNames).toEqual(['amountMinor']);
  });

  it('5xx en create conserva la clave y no expone texto del servidor', () => {
    const r = toUserError(http(500, { message: 'SQL error en tabla payments' }), 'create');
    expect(r.keepIdempotencyKey).toBeTrue();
    expect(r.message).not.toContain('SQL');
  });

  it('error de red (0) en create conserva la clave', () => {
    expect(toUserError(http(0), 'create').keepIdempotencyKey).toBeTrue();
  });

  it('cualquier error de poll pide consultar de nuevo, no repetir', () => {
    expect(toUserError(http(503), 'poll').message).toContain('no lo repitas');
  });
});