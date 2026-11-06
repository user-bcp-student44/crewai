// onboarding-error.mapper.ts
import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody, ApiFieldError, ErrorContext, UiError } from './onboarding.models';

const MAX_FIELD_ERRORS = 10;
const MAX_MESSAGE_LENGTH = 200;
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

function readBody(err: HttpErrorResponse): ApiErrorBody | null {
  return err.error && typeof err.error === 'object' ? (err.error as ApiErrorBody) : null;
}

function readFieldErrors(body: ApiErrorBody | null): ApiFieldError[] {
  if (!body || !Array.isArray(body.fieldErrors)) {
    return [];
  }
  return body.fieldErrors
    .filter((f) => f && typeof f.field === 'string' && typeof f.message === 'string')
    .slice(0, MAX_FIELD_ERRORS)
    .map((f) => ({ field: f.field, message: f.message.slice(0, MAX_MESSAGE_LENGTH) }));
}

function readCorrelationId(err: HttpErrorResponse, body: ApiErrorBody | null): string | undefined {
  const candidate = body?.correlationId ?? err.headers?.get('X-Correlation-Id') ?? undefined;
  return typeof candidate === 'string' && CORRELATION_ID_PATTERN.test(candidate) ? candidate : undefined;
}

/**
 * Traduce un error HTTP a un mensaje comprensible. Se decide por código HTTP:
 * los valores de `code` no están en el contrato (SP-6). No expone trazas ni el cuerpo crudo.
 */
export function toUiError(err: unknown, context: ErrorContext): UiError {
  const base: UiError = {
    status: -1,
    title: 'No se pudo completar la acción',
    message: 'No pudimos completar la acción. Inténtalo de nuevo.',
    fieldErrors: [],
    retryable: true,
    requiresLogin: false,
    refreshRequired: false,
  };

  if (!(err instanceof HttpErrorResponse)) {
    return base;
  }

  const body = readBody(err);
  const common = {
    ...base,
    status: err.status,
    correlationId: readCorrelationId(err, body),
    fieldErrors: readFieldErrors(body),
  };

  if (err.status === 0) {
    return {
      ...common,
      title: 'Sin conexión',
      message: 'No pudimos conectarnos. Revisa tu internet e inténtalo de nuevo.',
    };
  }

  switch (err.status) {
    case 400:
      return {
        ...common,
        title: 'Revisa los datos',
        message:
          context === 'start'
            ? 'No pudimos iniciar tu registro. Vuelve a abrir la app e inténtalo de nuevo.'
            : 'Hay datos incompletos o inválidos. Corrígelos y vuelve a intentarlo.',
        // En 'start' los errores por campo no son corregibles por el usuario.
        fieldErrors: context === 'start' ? [] : common.fieldErrors,
        retryable: context === 'start',
      };
    case 401:
      return {
        ...common,
        title: 'Tu sesión venció',
        message: 'Inicia sesión de nuevo para continuar con tu registro.',
        retryable: false,
        requiresLogin: true,
      };
    case 403:
      return {
        ...common,
        title: 'Sin permiso',
        message: 'No tienes permiso para continuar con este registro.',
        retryable: false,
      };
    case 404:
      return {
        ...common,
        title: 'Registro no encontrado',
        message: 'No encontramos tu registro. Puedes empezar de nuevo.',
      };
    case 409:
      return {
        ...common,
        title: 'Tu registro cambió',
        message:
          'Tu avance se actualizó desde otro dispositivo o ya se había enviado. Actualiza para ver el estado actual.',
        refreshRequired: true,
      };
    default:
      if (err.status >= 500) {
        return {
          ...common,
          title: 'Problema temporal',
          message: 'Tuvimos un problema de nuestro lado. Inténtalo de nuevo en unos minutos.',
        };
      }
      return common;
  }
}

export function notAvailableError(): UiError {
  return {
    status: -1,
    title: 'Paso no disponible',
    message: 'Esta función todavía no está disponible. Inténtalo más tarde.',
    fieldErrors: [],
    retryable: false,
    requiresLogin: false,
    refreshRequired: false,
  };
}