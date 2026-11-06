// payment-error.mapper.ts
import { HttpErrorResponse } from '@angular/common/http';

export type ApiOperation = 'preview' | 'create' | 'poll' | 'history';

export interface UserError {
  /** Mensaje comprensible; nunca incluye texto técnico ni del servidor. */
  message: string;
  /** Nombres de campos señalados por el servidor (solo nombres, no textos). */
  fieldNames: string[];
  sessionExpired: boolean;
  /** true si el resultado de create es ambiguo y se debe reutilizar la Idempotency-Key. */
  keepIdempotencyKey: boolean;
}

const NO_DUPLICATE = ' Si vuelves a intentar, no se duplicará el cobro.';

function extractFieldNames(body: unknown): string[] {
  const fe = (body as { fieldErrors?: unknown } | null)?.fieldErrors;
  if (Array.isArray(fe)) {
    return fe
      .map((i) => (typeof i === 'string' ? i : (i as { field?: unknown })?.field))
      .filter((n): n is string => typeof n === 'string');
  }
  if (fe && typeof fe === 'object') {
    return Object.keys(fe as Record<string, unknown>);
  }
  return [];
}

export function toUserError(err: unknown, op: ApiOperation): UserError {
  const base: UserError = {
    message: 'Ocurrió un problema inesperado. Inténtalo nuevamente.',
    fieldNames: [],
    sessionExpired: false,
    keepIdempotencyKey: false,
  };

  if (!(err instanceof HttpErrorResponse)) {
    return base;
  }

  const status = err.status;
  const ambiguousCreate = op === 'create';

  if (op === 'poll' && status !== 401 && status !== 403) {
    return {
      ...base,
      message:
        'No pudimos consultar el estado de tu pago. Puede seguir en proceso: no lo repitas, vuelve a consultar.',
    };
  }

  if (status === 0) {
    return {
      ...base,
      message:
        'No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo.' +
        (ambiguousCreate ? NO_DUPLICATE : ''),
      keepIdempotencyKey: ambiguousCreate,
    };
  }

  switch (status) {
    case 400:
      return {
        ...base,
        message:
          'Revisa los datos ingresados. El monto debe estar dentro de los límites permitidos para este canal.',
        fieldNames: extractFieldNames(err.error),
      };
    case 401:
      return {
        ...base,
        message: 'Tu sesión ya no es válida. Inicia sesión nuevamente.',
        sessionExpired: true,
      };
    case 403:
      return { ...base, message: 'No tienes permiso para realizar esta operación.' };
    case 404:
      return {
        ...base,
        message:
          op === 'poll' ? 'No encontramos este pago.' : 'No encontramos el destino solicitado.',
      };
    case 409:
      return {
        ...base,
        message:
          'Ya hay un pago en curso o esta operación ya fue registrada. Espera su resultado para no duplicar el cobro.',
        keepIdempotencyKey: ambiguousCreate,
      };
    case 503:
      return {
        ...base,
        message:
          'El servicio de pagos no está disponible por ahora. Inténtalo en unos minutos.' +
          (ambiguousCreate ? NO_DUPLICATE : ''),
        keepIdempotencyKey: ambiguousCreate,
      };
    default:
      if (status >= 500) {
        return {
          ...base,
          message:
            'Ocurrió un problema inesperado. Inténtalo nuevamente.' +
            (ambiguousCreate ? NO_DUPLICATE : ''),
          keepIdempotencyKey: ambiguousCreate,
        };
      }
      return base;
  }
}