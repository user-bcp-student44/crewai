// payment-status.ts
import { PaymentStatus } from './payment.models';

export interface StatusInfo {
  label: string;
  description: string;
}

const STATUS_INFO: Record<PaymentStatus, StatusInfo> = {
  PENDING: { label: 'En proceso', description: 'Tu pago se está procesando. Actualiza el estado en unos instantes.' },
  CONFIRMED: { label: 'Confirmado', description: 'Tu pago fue confirmado.' },
  FAILED: { label: 'No completado', description: 'El pago no se pudo completar.' },
  EXPIRED: { label: 'Expirado', description: 'El pago expiró antes de completarse.' },
  REVERSED: { label: 'Revertido', description: 'El pago fue revertido.' },
};

const UNKNOWN: StatusInfo = {
  label: 'Estado no reconocido',
  description: 'No pudimos interpretar el estado del pago. Inténtalo más tarde.',
};

export function statusInfo(status: string | undefined): StatusInfo {
  return (status && STATUS_INFO[status as PaymentStatus]) || UNKNOWN;
}