// api-error.mapper.ts
import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody, ErrorContext, UserFacingError } from './payment.models';

const GENERIC = 'Ocurrió un error inesperado. Inténtalo de nuevo.';

function readBody(raw: unknown): ApiErrorBody | null {
  if (raw && typeof raw === 'object') {
    return raw as ApiErrorBody;
  }
  return null;
}

/**
 * Traduce un error HTTP a un mensaje para el usuario.
 * Nunca muestra el `message` del servidor ni trazas: solo mensajes propios
 * y el traceId como código de referencia.
 */
export function mapHttpError(err: unknown, context: ErrorContext): UserFacingError {
  if (!(err instanceof HttpErrorResponse)) {
    return { message: GENERIC, action: 'none' };
  }

  const body = readBody(err.error);
  const traceId = typeof body?.traceId === 'string' ? body.traceId : undefined;
  const code = typeof body?.code === 'string' ? body.code : undefined;
  const make = (message: string, action: UserFacingError['action']): UserFacingError => ({
    message,
    action,
    traceId,
  });

  if (err.status === 0) {
    return make('No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo.', 'retry');
  }

  if (err.status === 409 && context === 'create') {
    if (code === 'IDEMPOTENCY_IN_PROGRESS') {
      return make('Tu pago se está procesando. Espera unos segundos y vuelve a intentarlo.', 'retry');
    }
    if (code === 'IDEMPOTENCY_KEY_REUSED') {
      return make('Esta solicitud ya se usó con otros datos. Inicia un pago nuevo.', 'new-attempt');
    }
  }

  switch (err.status) {
    case 400:
      return context === 'create'
        ? make('Revisa los datos del pago: alguno no es válido.', 'fix-form')
        : make('El identificador del pago no es válido.', 'none');
    case 401:
      return make('Tu sesión no es válida o venció. Vuelve a iniciar sesión.', 'none');
    case 403:
      return make('No tienes permiso para realizar esta acción.', 'none');
    case 404:
      return context === 'get'
        ? make('No encontramos este pago. Verifica el identificador.', 'none')
        : make('No pudimos completar la operación. Inténtalo de nuevo más tarde.', 'none');
    case 409:
      return make(
        'La operación entra en conflicto con otra ya registrada. Verifica el estado antes de volver a intentar.',
        'none',
      );
    default:
      break;
  }

  if (err.status >= 500) {
    return context === 'create'
      ? make(
          'El servicio no está disponible por ahora. Puedes reintentar: se usará la misma solicitud para evitar cobros duplicados.',
          'retry',
        )
      : make('El servicio no está disponible por ahora. Inténtalo en unos minutos.', 'retry');
  }

  return make(GENERIC, 'none');
}