// api-error.mapper.spec.ts
import { HttpErrorResponse } from '@angular/common/http';
import { mapHttpError } from './api-error.mapper';

describe('mapHttpError', () => {
  const err = (status: number, body?: unknown) => new HttpErrorResponse({ status, error: body });

  it('red caída (0) -> reintentar', () => {
    const r = mapHttpError(err(0), 'create');
    expect(r.action).toBe('retry');
  });

  it('409 IDEMPOTENCY_IN_PROGRESS -> reintentar con la misma clave', () => {
    const r = mapHttpError(err(409, { code: 'IDEMPOTENCY_IN_PROGRESS', traceId: 't-1' }), 'create');
    expect(r.action).toBe('retry');
    expect(r.traceId).toBe('t-1');
  });

  it('409 IDEMPOTENCY_KEY_REUSED -> pago nuevo', () => {
    const r = mapHttpError(err(409, { code: 'IDEMPOTENCY_KEY_REUSED' }), 'create');
    expect(r.action).toBe('new-attempt');
  });

  it('400 al crear -> corregir formulario', () => {
    expect(mapHttpError(err(400, { code: 'X' }), 'create').action).toBe('fix-form');
  });

  it('404 al consultar -> mensaje de no encontrado', () => {
    expect(mapHttpError(err(404), 'get').message).toContain('No encontramos');
  });

  it('5xx -> reintentar y no expone el message del servidor', () => {
    const r = mapHttpError(err(500, { message: 'SQL error en tabla payments' }), 'create');
    expect(r.action).toBe('retry');
    expect(r.message).not.toContain('SQL');
  });

  it('401 y 403 -> mensajes genéricos sin acción', () => {
    expect(mapHttpError(err(401), 'create').action).toBe('none');
    expect(mapHttpError(err(403), 'create').action).toBe('none');
  });
});