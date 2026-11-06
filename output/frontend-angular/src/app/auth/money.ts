// money.ts

/**
 * Convierte texto en soles ("12.50", "12,5", "7") a céntimos enteros.
 * Devuelve null si el formato no es válido. No usa coma flotante.
 * Máximo 7 dígitos enteros y 2 decimales.
 */
export function toCents(input: string): number | null {
  const normalized = (input ?? '').trim().replace(',', '.');
  const match = /^(\d{1,7})(?:\.(\d{1,2}))?$/.exec(normalized);
  if (!match) {
    return null;
  }
  const whole = parseInt(match[1], 10);
  const fraction = parseInt((match[2] ?? '').padEnd(2, '0') || '0', 10);
  return whole * 100 + fraction;
}

export function formatCents(cents: number, currency = 'PEN'): string {
  return new Intl.NumberFormat('es-PE', { style: 'currency', currency }).format(cents / 100);
}