// money.util.ts

/** Decimales de la moneda según Intl (SUPUESTO S11: coincide con la unidad menor del backend). */
export function currencyExponent(currency: string): number {
  try {
    return (
      new Intl.NumberFormat('es', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2
    );
  } catch {
    return 2;
  }
}

/** Convierte "12.50" o "12,50" a unidades menores sin usar punto flotante. null si el formato es inválido. */
export function parseAmountToMinor(text: string, exponent: number): number | null {
  const t = (text ?? '').trim().replace(',', '.');
  const re = exponent === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${exponent}})?$`);
  if (!re.test(t)) {
    return null;
  }
  const [int, frac = ''] = t.split('.');
  const n = Number(int + frac.padEnd(exponent, '0'));
  return Number.isSafeInteger(n) ? n : null;
}

export function formatMinor(amountMinor: number, currency: string): string {
  const exp = currencyExponent(currency);
  const value = amountMinor / Math.pow(10, exp);
  try {
    return new Intl.NumberFormat('es', { style: 'currency', currency }).format(value);
  } catch {
    return `${value.toFixed(exp)} ${currency}`;
  }
}