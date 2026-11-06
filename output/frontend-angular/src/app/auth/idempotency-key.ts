// idempotency-key.ts

/**
 * Genera una clave que cumple el patrón del servidor ^[A-Za-z0-9_-]{8,64}$.
 * Formato: "pay-" + 32 caracteres hexadecimales (36 en total).
 * Usa crypto.getRandomValues, disponible también fuera de contextos seguros.
 */
export function generateIdempotencyKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `pay-${hex}`;
}