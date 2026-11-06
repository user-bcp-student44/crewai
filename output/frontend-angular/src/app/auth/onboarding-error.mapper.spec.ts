// onboarding-error.mapper.spec.ts
import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { toUiError } from './onboarding-error.mapper';

describe('toUiError', () => {
  const http = (status: number, error?: unknown) =>
    new HttpErrorResponse({ status, error, headers: new HttpHeaders() });

  it('traduce estado 0 a sin conexión y reintentable', () => {
    const ui = toUiError(http(0), 'start');
    expect(ui.title).toBe('Sin conexión');
    expect(ui.retryable).toBeTrue();
  });

  it('400 en submit conserva fieldErrors saneados', () => {
    const ui = toUiError(
      http(400, { code: 'X', fieldErrors: [{ field: 'a', message: 'Falta a' }, { bad: 1 }] }),
      'submit',
    );
    expect(ui.fieldErrors).toEqual([{ field: 'a', message: 'Falta a' }]);
  });

  it('400 en start no muestra errores por campo', () => {
    const ui = toUiError(http(400, { fieldErrors: [{ field: 'acquisitionChannel', message: 'x' }] }), 'start');
    expect(ui.fieldErrors).toEqual([]);
  });

  it('401 pide iniciar sesión', () => {
    expect(toUiError(http(401), 'start').requiresLogin).toBeTrue();
  });

  it('403 no es reintentable', () => {
    expect(toUiError(http(403), 'submit').retryable).toBeFalse();
  });

  it('409 requiere actualizar', () => {
    expect(toUiError(http(409), 'submit').refreshRequired).toBeTrue();
  });

  it('5xx usa mensaje genérico sin exponer el cuerpo', () => {
    const ui = toUiError(http(500, { message: 'SQLException at line 3' }), 'start');
    expect(ui.message).not.toContain('SQL');
  });

  it('descarta un correlationId con caracteres no permitidos', () => {
    expect(toUiError(http(500, { correlationId: '<script>' }), 'start').correlationId).toBeUndefined();
    expect(toUiError(http(500, { correlationId: 'abc-123' }), 'start').correlationId).toBe('abc-123');
  });
});